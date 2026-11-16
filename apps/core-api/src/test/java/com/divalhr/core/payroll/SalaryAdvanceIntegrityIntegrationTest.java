package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.platform.access.SalaryAdvanceEmployment;
import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Committed PostgreSQL evidence for the PR #205 review corrections (MVP-120A, Issue #204, ADR
 * 0041): R205-1 ambiguous placements fail closed, R205-2 nobody reviews their own request (service
 * and database guard), and R205-3 concurrency, audit/outbox rollback and database-guard
 * enforcement. Synthetic data only; nothing is sent to any institution.
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
class SalaryAdvanceIntegrityIntegrationTest {

  private static final String REQUESTS = "/api/v1/me/salary-advance-requests";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;
  @MockitoSpyBean private SalaryAdvanceEmployment employment;
  @MockitoSpyBean private OutboxWriter outbox;
  @MockitoSpyBean private AuditRecorder audit;

  private PayrollPackageWorld w;
  private PayrollPackageWorld.Linked linked;
  private String employee;
  private LocalDate today;

  @BeforeEach
  void world() throws Exception {
    w = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
    linked = w.readyLinked();
    w.approve(linked.chain());
    employee = linked.person().bearer();
    today =
        LocalDate.parse(
            w.c
                .expect(get(policyPath()).header("Authorization", w.admin), 200)
                .get("today")
                .asText());
  }

  @AfterEach
  void clean() {
    reset(employment);
    reset(AopTestUtils.<OutboxWriter>getUltimateTargetObject(outbox));
    reset(AopTestUtils.<AuditRecorder>getUltimateTargetObject(audit));
  }

  // ------------------------------------------------------------------------------------------
  // Calls
  // ------------------------------------------------------------------------------------------

  private String policyPath() {
    return "/api/v1/legal-entities/" + w.entity + "/salary-advance-policies";
  }

  private static Map<String, Object> money(String amount) {
    return Map.of("currency", "CDF", "amount", amount);
  }

  /** Commits an enabled policy that allows {@code open} open requests. */
  private void policyInForce(int open) throws Exception {
    Map<String, Object> policy = new LinkedHashMap<>();
    policy.put("currency", "CDF");
    policy.put("code", "SAP-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
    policy.put("effectiveFrom", today.toString());
    policy.put("effectiveTo", null);
    policy.put("enabled", true);
    policy.put("minTenureMonths", 0);
    policy.put("maxPercent", 30);
    policy.put("absoluteMax", null);
    policy.put("minRequest", money("1000"));
    policy.put("maxOpenRequests", open);
    policy.put("waitingDays", 30);
    policy.put("consentVersion", "SA-CONSENT-1");
    policy.put("partnerCode", "SYNTHETIC-TEST");
    JsonNode preview =
        w.c.expect(CompensationWorld.json(policyPath() + "/preview", w.admin, null, policy), 200);
    assertThat(preview.get("blockers")).as(preview.toString()).isEmpty();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("policy", policy);
    body.put("expectedVersion", preview.get("version").asInt());
    body.put("previewDigest", preview.get("previewDigest").asText());
    body.put("expiresAt", preview.get("expiresAt").asText());
    body.put("confirmed", true);
    w.c.expect(CompensationWorld.json(policyPath(), w.admin, Organizations.newKey(), body), 201);
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

  private Map<String, Object> submission(JsonNode preview, String amount) {
    Map<String, Object> consent = new LinkedHashMap<>();
    consent.put("version", "SA-CONSENT-1");
    consent.put("language", "fr");
    consent.put("affirmed", true);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("currency", "CDF");
    body.put("amount", money(amount));
    body.put("previewDigest", preview.get("previewDigest").asText());
    body.put("expiresAt", preview.get("expiresAt").asText());
    body.put("consent", consent);
    return body;
  }

  private JsonNode submit(String amount) throws Exception {
    JsonNode preview = previewRequest(amount);
    assertThat(preview.get("blockers")).as(preview.toString()).isEmpty();
    return w.c.expect(
        CompensationWorld.json(
            REQUESTS, employee, Organizations.newKey(), submission(preview, amount)),
        201);
  }

  private JsonNode eligibility() throws Exception {
    return w.c
        .expect(get("/api/v1/me/salary-advance-eligibility").header("Authorization", employee), 200)
        .get("items")
        .get(0);
  }

  private MockHttpServletRequestBuilder decision(
      String bearer, String key, String id, String verb, Map<String, Object> body)
      throws Exception {
    return CompensationWorld.json(
        "/api/v1/salary-advance-requests/" + id + "/" + verb, bearer, key, body);
  }

  private static Map<String, Object> confirming(int version) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("expectedVersion", version);
    body.put("confirmed", true);
    return body;
  }

  private static Map<String, Object> refusing(int version) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("expectedVersion", version);
    body.put("reasonCode", "OTHER_POLICY_REASON");
    body.put("note", null);
    body.put("confirmed", true);
    return body;
  }

  private MockHttpServletRequestBuilder withdraw(JsonNode request, String key) throws Exception {
    return CompensationWorld.json(
        REQUESTS + "/" + request.get("requestId").asText() + "/withdraw",
        employee,
        key,
        confirming(request.get("version").asInt()));
  }

  /** The employee who is also a tenant administrator: the same verified subject holding the administrator role, MFA. */
  private String requesterAsAdministrator() {
    jdbc.update(
        "UPDATE identity.tenant_membership SET role = 'tenant-admin'"
            + " WHERE tenant_id = ? AND subject = ?",
        w.tenant,
        linked.person().subject());
    return "Bearer "
        + TestTokens.token()
            .tenant(w.tenant)
            .subject(linked.person().subject())
            .roles(List.of("tenant-admin"))
            .build();
  }

  /** True when the call did not succeed: an exception or a server error answer. */
  private boolean failed(MockHttpServletRequestBuilder request) {
    try {
      return w.c.call(request).getStatus() >= 500;
    } catch (Exception thrown) {
      return true;
    }
  }

  private int count(String table, UUID id) {
    return w.c.count(
        "SELECT count(*) FROM " + table + " WHERE tenant_id = ? AND request_id = ?", w.tenant, id);
  }

  private List<Integer> totals() {
    return List.of(
        w.c.count(
            "SELECT count(*) FROM payroll.salary_advance_request WHERE tenant_id = ?", w.tenant),
        w.c.count(
            "SELECT count(*) FROM payroll.salary_advance_consent WHERE tenant_id = ?", w.tenant),
        w.c.count(
            "SELECT count(*) FROM payroll.salary_advance_employer_decision WHERE tenant_id = ?",
            w.tenant),
        w.c.count(
            "SELECT count(*) FROM payroll.salary_advance_request_event WHERE tenant_id = ?",
            w.tenant),
        w.c.count(
            "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?"
                + " AND action LIKE 'salary-advance-request.%'",
            w.tenant),
        w.c.count(
            "SELECT count(*) FROM platform.outbox_event WHERE tenant_id = ?"
                + " AND event_type LIKE 'payroll.salary-advance-request.%'",
            w.tenant));
  }

  private String statusOf(UUID id) {
    return jdbc.queryForObject(
        "SELECT status FROM payroll.salary_advance_request WHERE tenant_id = ? AND id = ?",
        String.class,
        w.tenant,
        id);
  }

  // ------------------------------------------------------------------------------------------
  // R205-1: placements (zero, one, several)
  // ------------------------------------------------------------------------------------------

  @Test
  void zeroOneAndSeveralActivePlacementsAreEvaluatedWithoutPickingOne() throws Exception {
    policyInForce(1);

    // One placement (the real data): eligible, no ambiguity reason.
    assertThat(eligibility().get("reasons")).isEmpty();
    JsonNode valid = previewRequest("5000");

    // None: nothing to evaluate against, and never the ambiguity reason.
    doReturn(List.of()).when(employment).placementEntities(any(), any(), any());
    assertThat(eligibility().get("reasons").toString())
        .doesNotContain("MULTIPLE_ACTIVE_PLACEMENTS")
        .contains("NO_ENABLED_POLICY");

    // Several: the only reason, whatever policy exists, and no request can be made.
    doReturn(List.of(UUID.randomUUID(), UUID.randomUUID()))
        .when(employment)
        .placementEntities(any(), any(), any());
    JsonNode reasons = eligibility().get("reasons");
    assertThat(reasons).hasSize(1);
    assertThat(reasons.get(0).asText()).isEqualTo("MULTIPLE_ACTIVE_PLACEMENTS");
    JsonNode preview = previewRequest("5000");
    assertThat(preview.get("blockers")).hasSize(1);
    assertThat(preview.get("blockers").toString()).contains("MULTIPLE_ACTIVE_PLACEMENTS");
    assertThat(valid.get("blockers")).isEmpty();
    List<Integer> before = totals();
    w.c.problem(
        CompensationWorld.json(
            REQUESTS, employee, Organizations.newKey(), submission(valid, "5000")),
        422,
        "SALARY_ADVANCE_NOT_ELIGIBLE");
    assertThat(totals()).isEqualTo(before);

    // The ambiguity goes away: the same employee is eligible again.
    reset(employment);
    assertThat(eligibility().get("reasons")).isEmpty();
  }

  // ------------------------------------------------------------------------------------------
  // R205-2: nobody reviews their own request
  // ------------------------------------------------------------------------------------------

  @Test
  void aRequesterWhoIsAlsoAnAdministratorCannotConfirmOrRefuseTheirOwnRequest() throws Exception {
    policyInForce(2);
    JsonNode request = submit("5000");
    UUID id = UUID.fromString(request.get("requestId").asText());
    int version = request.get("version").asInt();
    String self = requesterAsAdministrator();
    List<Integer> before = totals();

    JsonNode refused =
        w.c.problem(
            decision(
                self,
                Organizations.newKey(),
                id.toString(),
                "confirm-employer",
                confirming(version)),
            409,
            "SALARY_ADVANCE_SELF_REVIEW_FORBIDDEN");
    assertThat(refused.toString()).doesNotContain(linked.person().subject()).doesNotContain("5000");
    w.c.problem(
        decision(self, Organizations.newKey(), id.toString(), "refuse-employer", refusing(version)),
        409,
        "SALARY_ADVANCE_SELF_REVIEW_FORBIDDEN");
    assertThat(totals()).isEqualTo(before);
    assertThat(statusOf(id)).isEqualTo("SUBMITTED");
    assertThat(count("payroll.salary_advance_employer_decision", id)).isZero();

    // A different tenant administrator may decide the same request.
    JsonNode confirmed =
        w.c.expect(
            decision(
                w.colleague(),
                Organizations.newKey(),
                id.toString(),
                "confirm-employer",
                confirming(version)),
            200);
    assertThat(confirmed.get("status").asText()).isEqualTo("EMPLOYER_CONFIRMED");
    assertThat(count("payroll.salary_advance_employer_decision", id)).isEqualTo(1);
  }

  @Test
  void theDatabaseRefusesADecisionBySubmittingSubject() throws Exception {
    policyInForce(1);
    UUID id = UUID.fromString(submit("5000").get("requestId").asText());
    String submitter =
        jdbc.queryForObject(
            "SELECT submitted_by FROM payroll.salary_advance_request WHERE id = ?",
            String.class,
            id);
    List<Integer> before = totals();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO payroll.salary_advance_employer_decision (tenant_id, request_id,"
                        + " decision, reason_code, internal_note, decided_by, decided_at,"
                        + " mfa_verified, confirmed)"
                        + " VALUES (?, ?, 'CONFIRMED', NULL, NULL, ?, now(), true, true)",
                    w.tenant,
                    id,
                    submitter))
        .hasMessageContaining("never decided by the subject who submitted it");
    assertThat(totals()).isEqualTo(before);
  }

  // ------------------------------------------------------------------------------------------
  // R205-3 (1): a decision racing a withdrawal has exactly one winner
  // ------------------------------------------------------------------------------------------

  @Test
  void aDecisionRacingAWithdrawalProducesExactlyOneOutcomeAndOneConsistentHistory()
      throws Exception {
    policyInForce(3);
    for (String verb : List.of("confirm-employer", "refuse-employer")) {
      JsonNode request = submit("5000");
      UUID id = UUID.fromString(request.get("requestId").asText());
      int version = request.get("version").asInt();
      Map<String, Object> body =
          "confirm-employer".equals(verb) ? confirming(version) : refusing(version);
      CountDownLatch start = new CountDownLatch(1);
      ExecutorService pool = Executors.newFixedThreadPool(2);
      try {
        Future<Integer> deciding =
            pool.submit(
                () -> {
                  start.await();
                  return w.c
                      .call(decision(w.admin, Organizations.newKey(), id.toString(), verb, body))
                      .getStatus();
                });
        Future<Integer> withdrawing =
            pool.submit(
                () -> {
                  start.await();
                  return w.c.call(withdraw(request, Organizations.newKey())).getStatus();
                });
        start.countDown();
        int decided = deciding.get(30, TimeUnit.SECONDS);
        int withdrawn = withdrawing.get(30, TimeUnit.SECONDS);
        assertThat(List.of(decided, withdrawn))
            .as("%s: exactly one command wins", verb)
            .containsExactlyInAnyOrder(200, 409);

        String winner =
            decided == 200
                ? ("confirm-employer".equals(verb) ? "EMPLOYER_CONFIRMED" : "EMPLOYER_REFUSED")
                : "WITHDRAWN";
        assertThat(statusOf(id)).isEqualTo(winner);
        assertThat(
                jdbc.queryForObject(
                    "SELECT row_version FROM payroll.salary_advance_request WHERE id = ?",
                    Integer.class,
                    id))
            .isEqualTo(2);
        List<String> chain =
            jdbc.queryForList(
                "SELECT coalesce(from_status, '-') || '>' || to_status FROM"
                    + " payroll.salary_advance_request_event WHERE request_id = ? ORDER BY sequence",
                String.class,
                id);
        assertThat(chain).containsExactly("->SUBMITTED", "SUBMITTED>" + winner);
        assertThat(count("payroll.salary_advance_employer_decision", id))
            .isEqualTo(decided == 200 ? 1 : 0);
      } finally {
        pool.shutdownNow();
      }
    }
  }

  // ------------------------------------------------------------------------------------------
  // R205-3 (2): an injected audit or outbox failure rolls everything back
  // ------------------------------------------------------------------------------------------

  @Test
  void anOutboxFailureRollsBackSubmissionAndDecisionAndFreesTheKeys() throws Exception {
    failureRollsBack(
        () ->
            doThrow(new IllegalStateException("simulated outbox failure"))
                .when(AopTestUtils.<OutboxWriter>getUltimateTargetObject(outbox))
                .append(any(EventEnvelope.class)));
  }

  @Test
  void anAuditFailureRollsBackSubmissionAndDecisionAndFreesTheKeys() throws Exception {
    failureRollsBack(
        () ->
            doThrow(new IllegalStateException("simulated audit failure"))
                .when(AopTestUtils.<AuditRecorder>getUltimateTargetObject(audit))
                .record(any(AuditEvent.class)));
  }

  private interface Injection {
    void install();
  }

  private void failureRollsBack(Injection failure) throws Exception {
    policyInForce(2);
    JsonNode preview = previewRequest("5000");
    String submitKey = Organizations.newKey();
    List<Integer> empty = totals();

    failure.install();
    assertThat(
            failed(
                CompensationWorld.json(REQUESTS, employee, submitKey, submission(preview, "5000"))))
        .isTrue();
    reset(AopTestUtils.<OutboxWriter>getUltimateTargetObject(outbox));
    reset(AopTestUtils.<AuditRecorder>getUltimateTargetObject(audit));
    assertThat(totals()).as("nothing of the submission survived").isEqualTo(empty);
    assertThat(
            w.c.count(
                "SELECT count(*) FROM platform.idempotency_record WHERE idempotency_key = ?",
                submitKey))
        .as("no completed idempotency evidence")
        .isZero();

    // The same key succeeds once the failure is gone.
    JsonNode request =
        w.c.expect(
            CompensationWorld.json(REQUESTS, employee, submitKey, submission(preview, "5000")),
            201);
    UUID id = UUID.fromString(request.get("requestId").asText());
    int version = request.get("version").asInt();
    List<Integer> submitted = totals();

    String decisionKey = Organizations.newKey();
    failure.install();
    assertThat(
            failed(
                decision(
                    w.admin, decisionKey, id.toString(), "confirm-employer", confirming(version))))
        .isTrue();
    reset(AopTestUtils.<OutboxWriter>getUltimateTargetObject(outbox));
    reset(AopTestUtils.<AuditRecorder>getUltimateTargetObject(audit));
    assertThat(totals()).as("nothing of the decision survived").isEqualTo(submitted);
    assertThat(statusOf(id)).isEqualTo("SUBMITTED");
    assertThat(count("payroll.salary_advance_employer_decision", id)).isZero();
    assertThat(
            w.c.count(
                "SELECT count(*) FROM platform.idempotency_record WHERE idempotency_key = ?",
                decisionKey))
        .isZero();

    JsonNode confirmed =
        w.c.expect(
            decision(w.admin, decisionKey, id.toString(), "confirm-employer", confirming(version)),
            200);
    assertThat(confirmed.get("status").asText()).isEqualTo("EMPLOYER_CONFIRMED");
  }

  // ------------------------------------------------------------------------------------------
  // R205-3 (3): direct SQL cannot bypass the database guards
  // ------------------------------------------------------------------------------------------

  private void rejected(String message, String sql, Object... args) {
    assertThatThrownBy(() -> jdbc.update(sql, args))
        .as(message)
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void directSqlCannotCreateADecisionWithoutItsLegalTransition() throws Exception {
    policyInForce(2);
    UUID submitted = UUID.fromString(submit("5000").get("requestId").asText());
    JsonNode second = submit("6000");
    UUID withdrawn = UUID.fromString(second.get("requestId").asText());
    w.c.expect(withdraw(second, Organizations.newKey()), 200);
    List<Integer> before = totals();
    String insert =
        "INSERT INTO payroll.salary_advance_employer_decision (tenant_id, request_id, decision,"
            + " reason_code, internal_note, decided_by, decided_at, mfa_verified, confirmed)"
            + " VALUES (?, ?, 'CONFIRMED', NULL, NULL, 'sub-other-admin', now(), true, true)";

    // A decision on a request that is no longer SUBMITTED.
    assertThatThrownBy(() -> jdbc.update(insert, w.tenant, withdrawn))
        .hasMessageContaining("applies to a SUBMITTED request only");
    // A decision on a SUBMITTED request without its transition event and header move: the
    // deferred completeness check refuses at commit.
    rejected("decision without transition", insert, w.tenant, submitted);
    // A free status edit without event or decision.
    rejected(
        "status edit",
        "UPDATE payroll.salary_advance_request SET status = 'EMPLOYER_CONFIRMED', row_version = 2"
            + " WHERE tenant_id = ? AND id = ?",
        w.tenant,
        submitted);
    // An illegal event: a refused request reopened by an employee, and an event that skips the
    // decision.
    rejected(
        "illegal event",
        "INSERT INTO payroll.salary_advance_request_event (tenant_id, request_id, sequence,"
            + " from_status, to_status, actor_role, actor_subject, occurred_at, occurred_on)"
            + " VALUES (?, ?, 2, 'SUBMITTED', 'EMPLOYER_CONFIRMED', 'EMPLOYEE', 'sub-x', now(),"
            + " current_date)",
        w.tenant,
        submitted);

    assertThat(totals()).isEqualTo(before);
    assertThat(statusOf(submitted)).isEqualTo("SUBMITTED");
    assertThat(statusOf(withdrawn)).isEqualTo("WITHDRAWN");
  }

  @Test
  void directSqlCannotAlterConsentEvidenceDecisionHistoryOrTheFrozenRequest() throws Exception {
    policyInForce(2);
    JsonNode request = submit("5000");
    UUID id = UUID.fromString(request.get("requestId").asText());
    w.c.expect(
        decision(
            w.admin,
            Organizations.newKey(),
            id.toString(),
            "confirm-employer",
            confirming(request.get("version").asInt())),
        200);
    List<Integer> before = totals();
    List<String> snapshot = new ArrayList<>();
    String frozen =
        "SELECT (SELECT row_to_json(c)::text FROM payroll.salary_advance_consent c WHERE c.request_id = ?)"
            + " || (SELECT row_to_json(d)::text FROM payroll.salary_advance_employer_decision d WHERE d.request_id = ?)"
            + " || (SELECT string_agg(row_to_json(e)::text, ',' ORDER BY e.sequence) FROM payroll.salary_advance_request_event e WHERE e.request_id = ?)"
            + " || (SELECT row_to_json(r)::text FROM payroll.salary_advance_request r WHERE r.id = ?)";
    snapshot.add(jdbc.queryForObject(frozen, String.class, id, id, id, id));

    rejected(
        "consent version",
        "UPDATE payroll.salary_advance_consent SET consent_version = 'SA-CONSENT-9' WHERE request_id = ?",
        id);
    rejected(
        "consent digest",
        "UPDATE payroll.salary_advance_consent SET disclosure_sha256 = repeat('0', 64) WHERE request_id = ?",
        id);
    rejected(
        "consent delete", "DELETE FROM payroll.salary_advance_consent WHERE request_id = ?", id);
    rejected(
        "decision value",
        "UPDATE payroll.salary_advance_employer_decision SET decision = 'REFUSED', reason_code = 'OTHER_POLICY_REASON' WHERE request_id = ?",
        id);
    rejected(
        "decision actor",
        "UPDATE payroll.salary_advance_employer_decision SET decided_by = 'sub-forged' WHERE request_id = ?",
        id);
    rejected(
        "decision delete",
        "DELETE FROM payroll.salary_advance_employer_decision WHERE request_id = ?",
        id);
    rejected(
        "event edit",
        "UPDATE payroll.salary_advance_request_event SET actor_subject = 'sub-forged' WHERE request_id = ?",
        id);
    rejected(
        "event delete",
        "DELETE FROM payroll.salary_advance_request_event WHERE request_id = ?",
        id);
    rejected(
        "amount", "UPDATE payroll.salary_advance_request SET requested_minor = 1 WHERE id = ?", id);
    rejected(
        "submitter",
        "UPDATE payroll.salary_advance_request SET submitted_by = 'sub-forged' WHERE id = ?",
        id);
    rejected("request delete", "DELETE FROM payroll.salary_advance_request WHERE id = ?", id);
    rejected("consent truncate", "TRUNCATE payroll.salary_advance_consent");
    rejected("decision truncate", "TRUNCATE payroll.salary_advance_employer_decision");
    rejected("event truncate", "TRUNCATE payroll.salary_advance_request_event");

    assertThat(totals()).isEqualTo(before);
    assertThat(jdbc.queryForObject(frozen, String.class, id, id, id, id))
        .as("every evidence row is byte-for-byte unchanged")
        .isEqualTo(snapshot.get(0));
  }
}
