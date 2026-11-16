package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.divalhr.core.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-120A (Issue #204, ADR 0041) against real PostgreSQL through the public API: the employer
 * policy chain, the frozen payroll reference, the consent-bound request, the employer decision,
 * every lifecycle transition, idempotency, authority, isolation and the privacy of the persisted
 * evidence. Synthetic data only; nothing is sent to any institution.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@PayrollPackageWorld.HighLimits
@TestPropertySource(
    properties = {
      "divalhr.request-limits.subject.salary-advance-policy-read=600",
      "divalhr.request-limits.subject.salary-advance-policy-preview=600",
      "divalhr.request-limits.subject.salary-advance-policy-write=600",
      "divalhr.request-limits.subject.salary-advance-request-read=600",
      "divalhr.request-limits.subject.salary-advance-decision-write=600",
      "divalhr.request-limits.subject.salary-advance-self-read=600",
      "divalhr.request-limits.subject.salary-advance-self-preview=600",
      "divalhr.request-limits.subject.salary-advance-self-write=600"
    })
class SalaryAdvanceApiIntegrationTest {

  private static final String SECRET_AMOUNT = "31337";
  private static final String SECRET_NOTE = "NOTE-SECRET-ZZ91";
  private static final String REQUESTS = "/api/v1/me/salary-advance-requests";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;

  private PayrollPackageWorld w;
  private PayrollPackageWorld.Linked linked;
  private String pkg;
  private String employee;
  private LocalDate today;

  @BeforeEach
  void world() throws Exception {
    w = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
    linked = w.readyLinked();
    pkg = w.approve(linked.chain()).get("packageId").asText();
    employee = linked.person().bearer();
    today = LocalDate.parse(policies().get("today").asText());
  }

  // ------------------------------------------------------------------------------------------
  // Calls
  // ------------------------------------------------------------------------------------------

  private String policyPath() {
    return "/api/v1/legal-entities/" + w.entity + "/salary-advance-policies";
  }

  private JsonNode policies() throws Exception {
    return w.c.expect(get(policyPath()).header("Authorization", w.admin), 200);
  }

  private static Map<String, Object> money(String amount) {
    return Map.of("currency", "CDF", "amount", amount);
  }

  private Map<String, Object> policy(LocalDate from, LocalDate to, int open) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("currency", "CDF");
    body.put("code", "SAP-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
    body.put("effectiveFrom", from.toString());
    body.put("effectiveTo", to == null ? null : to.toString());
    body.put("enabled", true);
    body.put("minTenureMonths", 0);
    body.put("maxPercent", 30);
    body.put("absoluteMax", null);
    body.put("minRequest", money("1000"));
    body.put("maxOpenRequests", open);
    body.put("waitingDays", 30);
    body.put("consentVersion", "SA-CONSENT-1");
    body.put("partnerCode", "SYNTHETIC-TEST");
    return body;
  }

  private JsonNode previewPolicy(Map<String, Object> policy) throws Exception {
    return w.c.expect(
        CompensationWorld.json(policyPath() + "/preview", w.admin, null, policy), 200);
  }

  private Map<String, Object> policyCommit(Map<String, Object> policy, JsonNode preview) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("policy", policy);
    body.put("expectedVersion", preview.get("version").asInt());
    body.put("previewDigest", preview.get("previewDigest").asText());
    body.put("expiresAt", preview.get("expiresAt").asText());
    body.put("confirmed", true);
    return body;
  }

  /** Previews and commits a policy; returns the receipt. */
  private JsonNode addPolicy(Map<String, Object> policy) throws Exception {
    JsonNode preview = previewPolicy(policy);
    assertThat(preview.get("blockers")).as(preview.toString()).isEmpty();
    return w.c.expect(
        CompensationWorld.json(
            policyPath(), w.admin, Organizations.newKey(), policyCommit(policy, preview)),
        201);
  }

  private void policyInForce() throws Exception {
    addPolicy(policy(today, null, 1));
  }

  private JsonNode eligibility() throws Exception {
    return w.c
        .expect(
            get("/api/v1/me/salary-advance-eligibility").header("Authorization", employee), 200)
        .get("items")
        .get(0);
  }

  private JsonNode previewRequest(String amount) throws Exception {
    return w.c.expect(
        CompensationWorld.json(
            REQUESTS + "/preview",
            employee,
            null,
            Map.of("currency", "CDF", "amount", money(amount))),
        200);
  }

  private Map<String, Object> submission(JsonNode preview, String amount, boolean affirmed) {
    Map<String, Object> consent = new LinkedHashMap<>();
    consent.put("version", "SA-CONSENT-1");
    consent.put("language", "fr");
    consent.put("affirmed", affirmed);
    return submission(preview, amount, consent);
  }

  private Map<String, Object> submission(
      JsonNode preview, String amount, Map<String, Object> consent) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("currency", "CDF");
    body.put("amount", money(amount));
    body.put("previewDigest", preview.get("previewDigest").asText());
    body.put("expiresAt", preview.get("expiresAt").asText());
    body.put("consent", consent);
    return body;
  }

  /** Previews and submits a request with a valid consent; returns the created request. */
  private JsonNode submit(String amount) throws Exception {
    JsonNode preview = previewRequest(amount);
    assertThat(preview.get("blockers")).as(preview.toString()).isEmpty();
    return w.c.expect(
        CompensationWorld.json(
            REQUESTS, employee, Organizations.newKey(), submission(preview, amount, true)),
        201);
  }

  private MockHttpServletRequestBuilder decision(String id, String verb, Map<String, Object> body)
      throws Exception {
    return CompensationWorld.json(
        "/api/v1/salary-advance-requests/" + id + "/" + verb,
        w.admin,
        Organizations.newKey(),
        body);
  }

  private static Map<String, Object> confirming(int version) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("expectedVersion", version);
    body.put("confirmed", true);
    return body;
  }

  private static Map<String, Object> refusing(int version, String reason, String note) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("expectedVersion", version);
    body.put("reasonCode", reason);
    body.put("note", note);
    body.put("confirmed", true);
    return body;
  }

  private MockHttpServletRequestBuilder withdraw(JsonNode request) throws Exception {
    return CompensationWorld.json(
        REQUESTS + "/" + request.get("requestId").asText() + "/withdraw",
        employee,
        Organizations.newKey(),
        confirming(request.get("version").asInt()));
  }

  private int audits(String action) {
    return w.audits(action);
  }

  private int status(MockHttpServletRequestBuilder request) throws Exception {
    return w.c.call(request).getStatus();
  }

  // ------------------------------------------------------------------------------------------
  // Policy: bounds, non-overlap, cancellation, append-only history, idempotency
  // ------------------------------------------------------------------------------------------

  @Test
  void aPolicyIsPreviewedThenCommittedAndReplaysIdempotently() throws Exception {
    Map<String, Object> policy = policy(today, null, 1);
    JsonNode preview = previewPolicy(policy);
    Map<String, Object> body = policyCommit(policy, preview);
    String key = Organizations.newKey();
    JsonNode first = w.c.expect(CompensationWorld.json(policyPath(), w.admin, key, body), 201);
    JsonNode replay = w.c.expect(CompensationWorld.json(policyPath(), w.admin, key, body), 201);
    assertThat(replay).isEqualTo(first);
    assertThat(policies().get("items")).hasSize(1);
    assertThat(policies().get("items").get(0).get("status").asText()).isEqualTo("IN_FORCE");
    assertThat(audits("salary-advance-policy.create")).isEqualTo(1);
    Map<String, Object> changedPolicy = new LinkedHashMap<>(policy);
    changedPolicy.put("waitingDays", 31);
    Map<String, Object> other = new LinkedHashMap<>(body);
    other.put("policy", changedPolicy);
    w.c.problem(
        CompensationWorld.json(policyPath(), w.admin, key, other), 409, "IDEMPOTENCY_KEY_REUSED");
    assertThat(audits("salary-advance-policy.create")).isEqualTo(1);
  }

  @Test
  void overlapPastDatesAndOutOfBoundsValuesWriteNothing() throws Exception {
    policyInForce();
    JsonNode overlap = previewPolicy(policy(today.plusDays(5), null, 1));
    assertThat(overlap.get("blockers").toString()).contains("PERIOD_OVERLAP");
    assertThat(overlap.get("previewDigest").isNull()).isTrue();
    JsonNode past = previewPolicy(policy(today.minusDays(3), today.minusDays(1), 1));
    assertThat(past.get("blockers").toString()).contains("EFFECTIVE_DATE_INVALID");
    Map<String, Object> bad = policy(today.plusDays(400), null, 1);
    bad.put("maxPercent", 51);
    assertThat(status(CompensationWorld.json(policyPath() + "/preview", w.admin, null, bad)))
        .isIn(400, 422);
    Map<String, Object> partner = policy(today.plusDays(400), null, 1);
    partner.put("partnerCode", "REAL-BANK");
    assertThat(status(CompensationWorld.json(policyPath() + "/preview", w.admin, null, partner)))
        .isIn(400, 422);
    assertThat(policies().get("items")).hasSize(1);
    assertThat(audits("salary-advance-policy.create")).isEqualTo(1);
  }

  @Test
  void aStaleChainVersionIsRefusedAndOnlyAScheduledVersionCanBeCancelled() throws Exception {
    Map<String, Object> later = policy(today.plusDays(11), null, 1);
    JsonNode stalePreview = previewPolicy(later);
    addPolicy(policy(today, today.plusDays(10), 1));
    w.c.problem(
        CompensationWorld.json(
            policyPath(), w.admin, Organizations.newKey(), policyCommit(later, stalePreview)),
        409,
        "SALARY_ADVANCE_VERSION_CONFLICT");
    JsonNode scheduled = addPolicy(later);
    String scheduledId = scheduled.get("policyId").asText();
    String inForceId = null;
    for (JsonNode item : policies().get("items")) {
      if ("IN_FORCE".equals(item.get("status").asText())) {
        inForceId = item.get("policyId").asText();
      }
    }
    assertThat(inForceId).isNotNull();
    w.c.problem(
        CompensationWorld.json(
            policyPath() + "/" + inForceId + "/cancel",
            w.admin,
            Organizations.newKey(),
            confirming(1)),
        409,
        "SALARY_ADVANCE_TRANSITION_NOT_ALLOWED");
    w.c.problem(
        CompensationWorld.json(
            policyPath() + "/" + scheduledId + "/cancel",
            w.admin,
            Organizations.newKey(),
            confirming(scheduled.get("sequence").asInt() + 7)),
        409,
        "SALARY_ADVANCE_VERSION_CONFLICT");
    JsonNode cancelled =
        w.c.expect(
            CompensationWorld.json(
                policyPath() + "/" + scheduledId + "/cancel",
                w.admin,
                Organizations.newKey(),
                confirming(scheduled.get("sequence").asInt())),
            200);
    assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
    assertThat(audits("salary-advance-policy.cancel")).isEqualTo(1);
    // History is append-only: the cancelled version stays listed.
    assertThat(policies().get("items")).hasSize(2);
  }

  // ------------------------------------------------------------------------------------------
  // Authority and isolation
  // ------------------------------------------------------------------------------------------

  @Test
  void onlyAnMfaTenantAdministratorMayManagePoliciesAndReviewRequests() throws Exception {
    String manager = Hierarchy.bearer(w.tenant, "sub-sa-manager-" + UUID.randomUUID(), "manager");
    String noMfaSubject = "sub-sa-nomfa-" + UUID.randomUUID();
    Hierarchy.bearer(w.tenant, noMfaSubject, "tenant-admin");
    String noMfa =
        "Bearer "
            + TestTokens.token()
                .tenant(w.tenant)
                .subject(noMfaSubject)
                .roles(List.of("tenant-admin"))
                .acr(TestTokens.PASSWORD_ACR)
                .build();
    for (String bearer : List.of(employee, manager, noMfa)) {
      assertThat(status(get(policyPath()).header("Authorization", bearer))).isEqualTo(403);
      assertThat(status(get("/api/v1/salary-advance-requests").header("Authorization", bearer)))
          .isEqualTo(403);
    }
    assertThat(status(get(policyPath()))).isEqualTo(401);
    String foreign =
        Hierarchy.bearer(
            Hierarchy.newTenant(mvc), "sub-sa-foreign-" + UUID.randomUUID(), "tenant-admin");
    assertThat(status(get(policyPath()).header("Authorization", foreign))).isEqualTo(404);
    // An administrator cannot use the employee's own routes as an employee.
    assertThat(status(get("/api/v1/me/salary-advance-requests").header("Authorization", w.admin)))
        .isIn(403, 404);
  }

  @Test
  void anEmployeeSeesOnlyTheirOwnRequestsAndNoOneElsesIdentifierWorks() throws Exception {
    policyInForce();
    String id = submit("5000").get("requestId").asText();
    CompensationWorld.Person other = w.linkedEmployee("Ilunga", PayrollPackageWorld.GROSS);
    JsonNode mine =
        w.c.expect(get(REQUESTS).header("Authorization", employee), 200);
    assertThat(mine.get("items")).hasSize(1);
    assertThat(
            w.c.expect(get(REQUESTS).header("Authorization", other.bearer()), 200).get("items"))
        .isEmpty();
    w.c.problem(
        get(REQUESTS + "/" + id).header("Authorization", other.bearer()),
        404,
        "SALARY_ADVANCE_REQUEST_NOT_FOUND");
    String foreign =
        Hierarchy.bearer(
            Hierarchy.newTenant(mvc), "sub-sa-foreign-" + UUID.randomUUID(), "tenant-admin");
    w.c.problem(
        get("/api/v1/salary-advance-requests/" + id).header("Authorization", foreign),
        404,
        "SALARY_ADVANCE_REQUEST_NOT_FOUND");
  }

  // ------------------------------------------------------------------------------------------
  // Eligibility, ceiling, consent
  // ------------------------------------------------------------------------------------------

  @Test
  void theCeilingIsExactIntegerArithmeticOverTheFrozenPayslip() throws Exception {
    JsonNode none = eligibility();
    assertThat(none.get("status").asText()).isEqualTo("NOT_ELIGIBLE");
    assertThat(none.get("reasons").toString()).contains("NO_ENABLED_POLICY");
    policyInForce();
    JsonNode ok = eligibility();
    assertThat(ok.get("status").asText()).as(ok.toString()).isEqualTo("ELIGIBLE");
    Long net =
        jdbc.queryForObject(
            "SELECT net_minor FROM payroll.payroll_payslip WHERE tenant_id = ?"
                + " AND employee_id = ? AND package_id = CAST(? AS uuid)",
            Long.class,
            w.tenant,
            linked.person().employee(),
            pkg);
    assertThat(net).isNotNull();
    long ceiling = net * 30 / 100;
    assertThat(ok.get("ceiling").get("amount").asText()).isEqualTo(Long.toString(ceiling));
    JsonNode over = previewRequest(Long.toString(ceiling + 1));
    assertThat(over.get("blockers").toString()).contains("AMOUNT_OUT_OF_RANGE");
    assertThat(over.get("previewDigest").isNull()).isTrue();
    assertThat(previewRequest(Long.toString(ceiling)).get("blockers")).isEmpty();
    assertThat(previewRequest("999").get("blockers").toString()).contains("AMOUNT_OUT_OF_RANGE");
  }

  @Test
  void aVoidedPackageCannotEstablishEligibility() throws Exception {
    policyInForce();
    assertThat(eligibility().get("status").asText()).isEqualTo("ELIGIBLE");
    w.voidPackage(pkg, "ADMINISTRATIVE_ERROR", null);
    JsonNode after = eligibility();
    assertThat(after.get("status").asText()).isEqualTo("NOT_ELIGIBLE");
    assertThat(after.get("reasons").toString()).contains("PAYROLL_REFERENCE_MISSING");
  }

  @Test
  void consentIsRequiredVersionBoundAndPreviewBound() throws Exception {
    policyInForce();
    JsonNode preview = previewRequest("5000");
    assertThat(
            status(
                CompensationWorld.json(
                    REQUESTS,
                    employee,
                    Organizations.newKey(),
                    submission(preview, "5000", false))))
        .isIn(400, 422);
    Map<String, Object> old = new LinkedHashMap<>();
    old.put("version", "SA-CONSENT-0");
    old.put("language", "fr");
    old.put("affirmed", true);
    w.c.problem(
        CompensationWorld.json(
            REQUESTS, employee, Organizations.newKey(), submission(preview, "5000", old)),
        409,
        "SALARY_ADVANCE_CONSENT_VERSION_STALE");
    w.c.problem(
        CompensationWorld.json(
            REQUESTS, employee, Organizations.newKey(), submission(preview, "5001", true)),
        409,
        "SALARY_ADVANCE_POLICY_PREVIEW_STALE");
    assertThat(counts()).containsOnly(0);
  }

  @Test
  void aSubmissionRecordsTheConsentOnceAndTheOpenLimitStopsASecondRequest() throws Exception {
    policyInForce();
    JsonNode preview = previewRequest("5000");
    String key = Organizations.newKey();
    Map<String, Object> body = submission(preview, "5000", true);
    JsonNode first = w.c.expect(CompensationWorld.json(REQUESTS, employee, key, body), 201);
    JsonNode replay = w.c.expect(CompensationWorld.json(REQUESTS, employee, key, body), 201);
    assertThat(replay.get("requestId")).isEqualTo(first.get("requestId"));
    assertThat(first.get("status").asText()).isEqualTo("SUBMITTED");
    assertThat(first.get("notice").asText()).isEqualTo("DIVALHR_NOT_LENDER");
    assertThat(counts()).startsWith(1, 1, 1);
    assertThat(audits("salary-advance-request.submit")).isEqualTo(1);
    assertThat(eligibility().get("reasons").toString()).contains("OPEN_LIMIT_REACHED");
    assertThat(previewRequest("5000").get("blockers").toString()).contains("OPEN_LIMIT_REACHED");
    w.c.problem(
        CompensationWorld.json(
            REQUESTS, employee, Organizations.newKey(), submission(preview, "5000", true)),
        409,
        "SALARY_ADVANCE_OPEN_LIMIT_REACHED");
  }

  // ------------------------------------------------------------------------------------------
  // Lifecycle
  // ------------------------------------------------------------------------------------------

  @Test
  void everyLegalAndIllegalTransitionIsEnforced() throws Exception {
    policyInForce();
    JsonNode request = submit("5000");
    String id = request.get("requestId").asText();
    int version = request.get("version").asInt();
    w.c.problem(
        decision(id, "confirm-employer", confirming(version + 5)),
        409,
        "SALARY_ADVANCE_VERSION_CONFLICT");
    JsonNode confirmed = w.c.expect(decision(id, "confirm-employer", confirming(version)), 200);
    assertThat(confirmed.get("status").asText()).isEqualTo("EMPLOYER_CONFIRMED");
    int next = confirmed.get("version").asInt();
    w.c.problem(
        decision(id, "confirm-employer", confirming(next)),
        409,
        "SALARY_ADVANCE_TRANSITION_NOT_ALLOWED");
    w.c.problem(
        decision(id, "refuse-employer", refusing(next, "OTHER_POLICY_REASON", null)),
        409,
        "SALARY_ADVANCE_TRANSITION_NOT_ALLOWED");
    JsonNode withdrawn = w.c.expect(withdraw(confirmed), 200);
    assertThat(withdrawn.get("status").asText()).isEqualTo("WITHDRAWN");
    w.c.problem(withdraw(withdrawn), 409, "SALARY_ADVANCE_TRANSITION_NOT_ALLOWED");
    w.c.problem(
        decision(id, "confirm-employer", confirming(withdrawn.get("version").asInt())),
        409,
        "SALARY_ADVANCE_TRANSITION_NOT_ALLOWED");
    JsonNode view =
        w.c.expect(
            get("/api/v1/salary-advance-requests/" + id).header("Authorization", w.admin), 200);
    assertThat(view.get("lifecycle")).hasSize(3);
    assertThat(counts().get(2)).isEqualTo(3);
  }

  @Test
  void aSubmittedRequestCanBeWithdrawnAndFreesTheOpenSlot() throws Exception {
    policyInForce();
    JsonNode request = submit("5000");
    assertThat(w.c.expect(withdraw(request), 200).get("status").asText()).isEqualTo("WITHDRAWN");
    assertThat(eligibility().get("reasons").toString()).doesNotContain("OPEN_LIMIT_REACHED");
    assertThat(audits("salary-advance-request.withdraw")).isEqualTo(1);
  }

  @Test
  void aRefusalKeepsTheNoteInternalAndStartsTheWaitingPeriod() throws Exception {
    policyInForce();
    JsonNode request = submit("5000");
    String id = request.get("requestId").asText();
    JsonNode refused =
        w.c.expect(
            decision(
                id,
                "refuse-employer",
                refusing(request.get("version").asInt(), "EMPLOYMENT_ENDING", SECRET_NOTE)),
            200);
    assertThat(refused.get("status").asText()).isEqualTo("EMPLOYER_REFUSED");
    JsonNode adminView =
        w.c.expect(
            get("/api/v1/salary-advance-requests/" + id).header("Authorization", w.admin), 200);
    assertThat(adminView.get("internalNote").asText()).isEqualTo(SECRET_NOTE);
    JsonNode mine =
        w.c.expect(get(REQUESTS + "/" + id).header("Authorization", employee), 200);
    assertThat(mine.toString()).doesNotContain(SECRET_NOTE);
    assertThat(mine.get("internalNote").isNull()).isTrue();
    JsonNode after = eligibility();
    assertThat(after.get("reasons").toString()).contains("WAITING_PERIOD");
    assertThat(after.get("waitingUntil").isNull()).isFalse();
  }

  // ------------------------------------------------------------------------------------------
  // Privacy and rollback
  // ------------------------------------------------------------------------------------------

  @Test
  void auditEventsAndOutboxNeverCarryAnAmountANoteOrConsentDetail() throws Exception {
    policyInForce();
    JsonNode request = submit(SECRET_AMOUNT);
    String id = request.get("requestId").asText();
    w.c.expect(
        decision(
            id,
            "refuse-employer",
            refusing(request.get("version").asInt(), "OTHER_POLICY_REASON", SECRET_NOTE)),
        200);
    List<String> rows =
        new ArrayList<>(
            jdbc.queryForList(
                "SELECT metadata::text || coalesce(after_state_sha256, '') || actor_subject"
                    + " FROM platform.audit_event WHERE tenant_id = ?"
                    + " AND action LIKE 'salary-advance-%'",
                String.class,
                w.tenant));
    rows.addAll(
        jdbc.queryForList(
            "SELECT envelope::text FROM platform.outbox_event WHERE tenant_id = ?"
                + " AND event_type LIKE 'payroll.salary-advance-%'",
            String.class,
            w.tenant));
    assertThat(rows).isNotEmpty();
    String text = String.join("\n", rows);
    assertThat(text).doesNotContain(SECRET_AMOUNT).doesNotContain(SECRET_NOTE);
    assertThat(text).doesNotContain("SA-CONSENT-1");
    assertThat(audits("salary-advance-request.submit")).isEqualTo(1);
    assertThat(audits("salary-advance-request.employer-refuse")).isEqualTo(1);
    assertThat(
            w.c.count(
                "SELECT count(*) FROM platform.outbox_event WHERE tenant_id = ?"
                    + " AND event_type = 'payroll.salary-advance-request.employer-refused.v1'",
                w.tenant))
        .isEqualTo(1);
  }

  @Test
  void aRefusedCommandWritesNothingAndConsumesNoKey() throws Exception {
    policyInForce();
    JsonNode preview = previewRequest("5000");
    List<Integer> before = counts();
    String key = Organizations.newKey();
    assertThat(
            status(CompensationWorld.json(REQUESTS, employee, key, submission(preview, "5000", false))))
        .isIn(400, 422);
    assertThat(counts()).isEqualTo(before);
    w.c.expect(
        CompensationWorld.json(REQUESTS, employee, key, submission(preview, "5000", true)), 201);
    assertThat(counts()).isNotEqualTo(before);
  }

  private List<Integer> counts() {
    return List.of(
        w.c.count(
            "SELECT count(*) FROM payroll.salary_advance_request WHERE tenant_id = ?", w.tenant),
        w.c.count(
            "SELECT count(*) FROM payroll.salary_advance_consent WHERE tenant_id = ?", w.tenant),
        w.c.count(
            "SELECT count(*) FROM payroll.salary_advance_request_event WHERE tenant_id = ?",
            w.tenant),
        w.c.count(
            "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?"
                + " AND action IN ('salary-advance-request.submit',"
                + " 'salary-advance-request.withdraw', 'salary-advance-request.employer-confirm',"
                + " 'salary-advance-request.employer-refuse')",
            w.tenant),
        w.c.count(
            "SELECT count(*) FROM platform.outbox_event WHERE tenant_id = ?"
                + " AND event_type LIKE 'payroll.salary-advance-request.%'",
            w.tenant));
  }
}
