package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.payroll.application.SalaryAdvanceHandoffWorker;
import com.divalhr.core.payroll.application.SalaryAdvancePartnerGateway.InquiryOutcome;
import com.divalhr.core.payroll.domain.SalaryAdvanceHandoffOutcome;
import com.divalhr.core.payroll.domain.SalaryAdvanceHandoffSigner;
import com.divalhr.core.support.FakeSalaryAdvancePartnerGateway;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-120B (Issue #206, ADR 0042) against real PostgreSQL through the public API: the fresh
 * versioned consent, the single immutable handoff, delivery through the scripted synthetic
 * connector (never a socket), the ambiguous-delivery rule, signed callbacks, authority, isolation,
 * the database guards and the privacy of everything persisted. Synthetic data only.
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
      "divalhr.request-limits.subject.salary-advance-self-write=600",
      "divalhr.request-limits.subject.salary-advance-handoff-read=600",
      "divalhr.request-limits.subject.salary-advance-handoff-admin-read=600",
      "divalhr.request-limits.subject.salary-advance-handoff-preview=600",
      "divalhr.request-limits.subject.salary-advance-handoff-write=600",
      "divalhr.request-limits.subject.salary-advance-handoff-reconcile=600"
    })
class SalaryAdvanceHandoffApiIntegrationTest {

  private static final String SECRET_AMOUNT = "31337";
  private static final String REQUESTS = "/api/v1/me/salary-advance-requests";
  private static final String ADMIN_HANDOFFS = "/api/v1/salary-advance-handoffs";
  private static final String CALLBACKS = "/api/v1/public/partners/synthetic-test/callbacks";
  private static final String CALLBACK_KEY = "test-only-partner-callback-signing-key-001";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;
  @Autowired private FakeSalaryAdvancePartnerGateway partner;
  @Autowired private SalaryAdvanceHandoffWorker worker;

  private PayrollPackageWorld w;
  private PayrollPackageWorld.Linked linked;
  private String employee;
  private LocalDate today;

  @BeforeEach
  void world() throws Exception {
    // The database is shared by the tests of the context: whatever an earlier test left queued is
    // delivered and forgotten first, so this test's scripted results meet only its own handoffs.
    for (int i = 0; i < 25 && worker.runOnce() > 0; i++) {
      // drain
    }
    partner.reset();
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
    JsonNode preview = previewPolicy(policy());
    w.c.expect(
        CompensationWorld.json(
            policyPath(), w.admin, Organizations.newKey(), policyCommit(policy(), preview)),
        201);
  }

  // ------------------------------------------------------------------------------------------
  // Set-up helpers
  // ------------------------------------------------------------------------------------------

  private String policyPath() {
    return "/api/v1/legal-entities/" + w.entity + "/salary-advance-policies";
  }

  private Map<String, Object> policy() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("currency", "CDF");
    body.put("code", "SAP-HO-" + today.toString().replace("-", ""));
    body.put("effectiveFrom", today.toString());
    body.put("effectiveTo", null);
    body.put("enabled", true);
    body.put("minTenureMonths", 0);
    body.put("maxPercent", 30);
    body.put("absoluteMax", null);
    body.put("minRequest", Map.of("currency", "CDF", "amount", "1000"));
    body.put("maxOpenRequests", 3);
    body.put("waitingDays", 30);
    body.put("consentVersion", "SA-CONSENT-1");
    body.put("partnerCode", "SYNTHETIC-TEST");
    return body;
  }

  private JsonNode previewPolicy(Map<String, Object> policy) throws Exception {
    return w.c.expect(
        CompensationWorld.json(policyPath() + "/preview", w.admin, null, policy), 200);
  }

  private static Map<String, Object> policyCommit(Map<String, Object> policy, JsonNode preview) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("policy", policy);
    body.put("expectedVersion", preview.get("version").asInt());
    body.put("previewDigest", preview.get("previewDigest").asText());
    body.put("expiresAt", preview.get("expiresAt").asText());
    body.put("confirmed", true);
    return body;
  }

  private static Map<String, Object> confirming(int version) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("expectedVersion", version);
    body.put("confirmed", true);
    return body;
  }

  /** Submits a request with a valid 120A consent. */
  private JsonNode submit(String amount) throws Exception {
    Map<String, Object> money = Map.of("currency", "CDF", "amount", amount);
    JsonNode preview =
        w.c.expect(
            CompensationWorld.json(
                REQUESTS + "/preview", employee, null, Map.of("currency", "CDF", "amount", money)),
            200);
    assertThat(preview.get("blockers")).as(preview.toString()).isEmpty();
    Map<String, Object> consent = new LinkedHashMap<>();
    consent.put("version", "SA-CONSENT-1");
    consent.put("language", "fr");
    consent.put("affirmed", true);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("currency", "CDF");
    body.put("amount", money);
    body.put("previewDigest", preview.get("previewDigest").asText());
    body.put("expiresAt", preview.get("expiresAt").asText());
    body.put("consent", consent);
    return w.c.expect(
        CompensationWorld.json(REQUESTS, employee, Organizations.newKey(), body), 201);
  }

  /** A submitted and employer-confirmed request: the precondition of every handoff. */
  private JsonNode confirmed(String amount) throws Exception {
    JsonNode request = submit(amount);
    return w.c.expect(
        CompensationWorld.json(
            "/api/v1/salary-advance-requests/"
                + request.get("requestId").asText()
                + "/confirm-employer",
            w.admin,
            Organizations.newKey(),
            confirming(request.get("version").asInt())),
        200);
  }

  private static String handoffPath(String requestId) {
    return REQUESTS + "/" + requestId + "/handoff";
  }

  private JsonNode previewHandoff(String requestId) throws Exception {
    return w.c.expect(
        CompensationWorld.json(handoffPath(requestId) + "/preview", employee, null, Map.of()), 200);
  }

  private static Map<String, Object> authorization(
      JsonNode preview, boolean affirmed, boolean understands) {
    Map<String, Object> consent = new LinkedHashMap<>();
    consent.put("version", preview.get("consent").get("version").asText());
    consent.put("language", "en");
    consent.put("affirmed", affirmed);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("expectedRequestVersion", preview.get("requestVersion").asInt());
    body.put(
        "previewDigest",
        preview.get("previewDigest").isNull()
            ? "0".repeat(64)
            : preview.get("previewDigest").asText());
    body.put(
        "expiresAt",
        preview.get("expiresAt").isNull()
            ? "2030-01-01T00:00:00Z"
            : preview.get("expiresAt").asText());
    body.put("consent", consent);
    body.put("understandsSyntheticTest", understands);
    return body;
  }

  private MockHttpServletRequestBuilder authorize(
      String requestId, String key, Map<String, Object> body) throws Exception {
    return CompensationWorld.json(handoffPath(requestId), employee, key, body);
  }

  /** Previews and authorizes; returns the handoff view. */
  private JsonNode authorized(String requestId) throws Exception {
    JsonNode preview = previewHandoff(requestId);
    assertThat(preview.get("blockers")).as(preview.toString()).isEmpty();
    return w.c.expect(
        authorize(requestId, Organizations.newKey(), authorization(preview, true, true)), 201);
  }

  private JsonNode myHandoff(String requestId) throws Exception {
    return w.c.expect(get(handoffPath(requestId)).header("Authorization", employee), 200);
  }

  private JsonNode adminHandoff(String handoffId) throws Exception {
    return w.c.expect(get(ADMIN_HANDOFFS + "/" + handoffId).header("Authorization", w.admin), 200);
  }

  private MockHttpServletRequestBuilder withdrawHandoff(String requestId, int version)
      throws Exception {
    return CompensationWorld.json(
        handoffPath(requestId) + "/withdraw",
        employee,
        Organizations.newKey(),
        confirming(version));
  }

  private MockHttpServletRequestBuilder reconcile(String handoffId, int version) throws Exception {
    return reconcileAs(w.admin, handoffId, version);
  }

  private MockHttpServletRequestBuilder reconcileAs(String bearer, String handoffId, int version)
      throws Exception {
    return CompensationWorld.json(
        ADMIN_HANDOFFS + "/" + handoffId + "/reconcile",
        bearer,
        Organizations.newKey(),
        confirming(version));
  }

  private String requestStatus(String requestId) throws Exception {
    return w.c
        .expect(get(REQUESTS + "/" + requestId).header("Authorization", employee), 200)
        .get("status")
        .asText();
  }

  private int status(MockHttpServletRequestBuilder request) throws Exception {
    return w.c.call(request).getStatus();
  }

  private int audits(String action) {
    return w.audits(action);
  }

  // ------------------------------------------------------------------------------------------
  // Callback helpers
  // ------------------------------------------------------------------------------------------

  private MockHttpServletRequestBuilder callback(
      UUID application, String state, String reason, long timestamp, UUID id, String key) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("applicationId", application.toString());
    body.put("reviewState", state);
    body.put("reasonCode", reason);
    body.put("issuedAt", clock.instant().toString());
    byte[] raw;
    try {
      raw = CompensationWorld.JSON.writeValueAsBytes(body);
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
    String signature = new SalaryAdvanceHandoffSigner(key).signCallback(timestamp, id, raw);
    return post(CALLBACKS)
        .contentType("application/json")
        .header("X-Divalhr-Timestamp", Long.toString(timestamp))
        .header("X-Divalhr-Callback-Id", id.toString())
        .header("X-Divalhr-Signature", signature)
        .content(raw);
  }

  private MockHttpServletRequestBuilder callback(UUID application, String state, String reason) {
    return callback(
        application,
        state,
        reason,
        clock.instant().getEpochSecond(),
        UUID.randomUUID(),
        CALLBACK_KEY);
  }

  private void deliver() {
    assertThat(worker.runOnce()).isGreaterThanOrEqualTo(1);
  }

  // ------------------------------------------------------------------------------------------
  // Preconditions and consent
  // ------------------------------------------------------------------------------------------

  @Test
  void aHandoffNeedsAnEmployerConfirmedRequestAndAFreshAffirmedConsent() throws Exception {
    JsonNode submitted = submit("5000");
    String id = submitted.get("requestId").asText();
    JsonNode early = previewHandoff(id);
    assertThat(early.get("blockers")).isNotEmpty();
    assertThat(early.get("previewDigest").isNull()).isTrue();
    w.c.problem(
        authorize(id, Organizations.newKey(), authorization(early, true, true)),
        409,
        "SALARY_ADVANCE_EMPLOYER_CONFIRMATION_REQUIRED");

    String confirmedId = confirmed("6000").get("requestId").asText();
    JsonNode preview = previewHandoff(confirmedId);
    assertThat(preview.get("blockers")).isEmpty();
    assertThat(preview.get("consent").get("version").asText()).isEqualTo("SA-HANDOFF-1");
    assertThat(preview.get("notice").asText()).isEqualTo("DIVALHR_NOT_LENDER");
    assertThat(preview.get("handoffNotice").asText()).isEqualTo("DIVALHR_HANDOFF_SYNTHETIC");
    for (Map<String, Object> body :
        List.of(authorization(preview, false, true), authorization(preview, true, false))) {
      w.c.problem(
          authorize(confirmedId, Organizations.newKey(), body),
          422,
          "SALARY_ADVANCE_HANDOFF_CONSENT_REQUIRED");
    }
    Map<String, Object> wrongVersion = authorization(preview, true, true);
    @SuppressWarnings("unchecked")
    Map<String, Object> consent = (Map<String, Object>) wrongVersion.get("consent");
    consent.put("version", "SA-HANDOFF-0");
    w.c.problem(
        authorize(confirmedId, Organizations.newKey(), wrongVersion),
        409,
        "SALARY_ADVANCE_HANDOFF_CONSENT_STALE");
    Map<String, Object> staleDigest = authorization(preview, true, true);
    staleDigest.put("previewDigest", "0".repeat(64));
    w.c.problem(
        authorize(confirmedId, Organizations.newKey(), staleDigest),
        409,
        "SALARY_ADVANCE_HANDOFF_PREVIEW_STALE");
    assertThat(handoffCount()).isZero();
    assertThat(audits("salary-advance-handoff.authorize")).isZero();
  }

  @Test
  void authorizingCreatesOneImmutableHandoffAndReplaysIdempotently() throws Exception {
    String id = confirmed("5000").get("requestId").asText();
    JsonNode preview = previewHandoff(id);
    Map<String, Object> body = authorization(preview, true, true);
    String key = Organizations.newKey();
    JsonNode first = w.c.expect(authorize(id, key, body), 201);
    JsonNode replay = w.c.expect(authorize(id, key, body), 201);
    assertThat(replay).isEqualTo(first);
    assertThat(first.get("state").asText()).isEqualTo("QUEUED");
    assertThat(first.get("notice").asText()).isEqualTo("DIVALHR_NOT_LENDER");
    assertThat(handoffCount()).isEqualTo(1);
    assertThat(audits("salary-advance-handoff.authorize")).isEqualTo(1);
    // A different key, a second authorization: one handoff per request.
    w.c.problem(
        authorize(id, Organizations.newKey(), authorization(preview, true, true)),
        409,
        "SALARY_ADVANCE_HANDOFF_EXISTS");
    assertThat(handoffCount()).isEqualTo(1);
    // The key cannot be reused for a different body.
    Map<String, Object> other = authorization(preview, true, true);
    other.put("expectedRequestVersion", preview.get("requestVersion").asInt() + 1);
    w.c.problem(authorize(id, key, other), 409, "IDEMPOTENCY_KEY_REUSED");
    assertThat(audits("salary-advance-handoff.authorize")).isEqualTo(1);
  }

  @Test
  void aLiveHandoffBlocksTheRequestWithdrawalOfMvp120a() throws Exception {
    JsonNode request = confirmed("5000");
    String id = request.get("requestId").asText();
    authorized(id);
    JsonNode current = w.c.expect(get(REQUESTS + "/" + id).header("Authorization", employee), 200);
    w.c.problem(
        CompensationWorld.json(
            REQUESTS + "/" + id + "/withdraw",
            employee,
            Organizations.newKey(),
            confirming(current.get("version").asInt())),
        409,
        "SALARY_ADVANCE_HANDOFF_EXISTS");
    assertThat(requestStatus(id)).isEqualTo("EMPLOYER_CONFIRMED");
  }

  // ------------------------------------------------------------------------------------------
  // Delivery, receipt, withdrawal
  // ------------------------------------------------------------------------------------------

  @Test
  void aDeliveredHandoffIsReceivedOnceAndNeverResentAfterAcknowledgement() throws Exception {
    String id = confirmed("5000").get("requestId").asText();
    JsonNode handoff = authorized(id);
    deliver();
    JsonNode mine = myHandoff(id);
    assertThat(mine.get("state").asText()).isEqualTo("RECEIVED");
    assertThat(mine.get("reviewState").asText()).isEqualTo("RECEIVED");
    assertThat(partner.calls()).hasSize(1);
    FakeSalaryAdvancePartnerGateway.Call call = partner.calls().get(0);
    assertThat(call.signature()).startsWith("v1=");
    assertThat(call.text()).contains("\"schemaVersion\"").doesNotContain("employeeName");
    // Further cycles and the passage of time never send it again.
    w.advance(3600);
    worker.runOnce();
    worker.runOnce();
    assertThat(partner.calls()).hasSize(1);
    assertThat(
            w.c.count(
                "SELECT count(*) FROM payroll.salary_advance_handoff_ack WHERE tenant_id = ?",
                w.tenant))
        .isEqualTo(1);
    assertThat(requestStatus(id)).isEqualTo("EMPLOYER_CONFIRMED");
    JsonNode admin = adminHandoff(handoff.get("handoffId").asText());
    assertThat(admin.get("state").asText()).isEqualTo("RECEIVED");
    assertThat(admin.get("attempts")).hasSize(1);
  }

  @Test
  void anEmployeeMayWithdrawBeforeReceiptButNotAfter() throws Exception {
    String after = confirmed("7000").get("requestId").asText();
    authorized(after);
    deliver();
    JsonNode received = myHandoff(after);
    assertThat(received.get("state").asText()).isEqualTo("RECEIVED");
    w.c.problem(
        withdrawHandoff(after, received.get("version").asInt()),
        409,
        "SALARY_ADVANCE_HANDOFF_TRANSITION_NOT_ALLOWED");
    assertThat(requestStatus(after)).isEqualTo("EMPLOYER_CONFIRMED");

    String before = confirmed("5000").get("requestId").asText();
    JsonNode queued = authorized(before);
    JsonNode withdrawn = w.c.expect(withdrawHandoff(before, queued.get("version").asInt()), 200);
    assertThat(withdrawn.get("state").asText()).isEqualTo("WITHDRAWN");
    assertThat(requestStatus(before)).isEqualTo("WITHDRAWN");
    int sent = partner.calls().size();
    worker.runOnce();
    assertThat(partner.calls()).hasSize(sent);
    w.c.problem(
        withdrawHandoff(before, withdrawn.get("version").asInt()),
        409,
        "SALARY_ADVANCE_HANDOFF_TRANSITION_NOT_ALLOWED");
    assertThat(audits("salary-advance-handoff.withdraw")).isEqualTo(1);
  }

  @Test
  void aTransientRefusalIsRetriedAndExhaustionEndsFailedNeverReceived() throws Exception {
    String id = confirmed("5000").get("requestId").asText();
    authorized(id);
    for (int i = 0; i < 6; i++) {
      partner.then(SalaryAdvanceHandoffOutcome.TRANSIENT_NOT_SENT, null);
    }
    for (int i = 0; i < 12; i++) {
      w.advance(86_400);
      worker.runOnce();
    }
    JsonNode view = myHandoff(id);
    assertThat(view.get("state").asText()).isEqualTo("FAILED");
    assertThat(partner.calls().size()).isLessThanOrEqualTo(6);
    assertThat(view.get("reviewState").isNull()).isTrue();
  }

  // ------------------------------------------------------------------------------------------
  // Ambiguous delivery and reconcile
  // ------------------------------------------------------------------------------------------

  @Test
  void anAmbiguousTimeoutIsNeverResentAndAnAdministratorReconcilesIt() throws Exception {
    String id = confirmed("5000").get("requestId").asText();
    JsonNode handoff = authorized(id);
    String handoffId = handoff.get("handoffId").asText();
    partner.then(SalaryAdvanceHandoffOutcome.UNKNOWN_TIMEOUT, null);
    deliver();
    assertThat(myHandoff(id).get("state").asText()).isEqualTo("DELIVERY_UNKNOWN");
    w.advance(86_400);
    worker.runOnce();
    worker.runOnce();
    assertThat(partner.calls()).as("an ambiguous delivery is never resent").hasSize(1);

    JsonNode unknown = adminHandoff(handoffId);
    // Inconclusive inquiry: refused, nothing changes.
    partner.thenInquiry(InquiryOutcome.INCONCLUSIVE, null);
    w.c.problem(
        reconcile(handoffId, unknown.get("version").asInt()),
        409,
        "SALARY_ADVANCE_HANDOFF_DELIVERY_UNKNOWN");
    assertThat(adminHandoff(handoffId).get("state").asText()).isEqualTo("DELIVERY_UNKNOWN");
    // Conclusive: the partner holds it.
    partner.thenInquiry(InquiryOutcome.RECEIVED, "SYNTH-RECON-1");
    JsonNode reconciled = w.c.expect(reconcile(handoffId, unknown.get("version").asInt()), 200);
    assertThat(reconciled.get("state").asText()).isEqualTo("RECEIVED");
    assertThat(partner.calls()).hasSize(1);
    assertThat(partner.inquiryCalls()).hasSize(2);
    assertThat(audits("salary-advance-handoff.reconcile")).isEqualTo(1);
  }

  @Test
  void aReconcileThatFindsNothingReceivedRequeuesAndTheSendHappensAgainOnlyThen() throws Exception {
    String id = confirmed("5000").get("requestId").asText();
    String handoffId = authorized(id).get("handoffId").asText();
    partner.then(SalaryAdvanceHandoffOutcome.UNKNOWN_BAD_RESPONSE, null);
    deliver();
    JsonNode unknown = adminHandoff(handoffId);
    assertThat(unknown.get("state").asText()).isEqualTo("DELIVERY_UNKNOWN");
    partner.thenInquiry(InquiryOutcome.NOT_RECEIVED, null);
    JsonNode queued = w.c.expect(reconcile(handoffId, unknown.get("version").asInt()), 200);
    assertThat(queued.get("state").asText()).isEqualTo("QUEUED");
    deliver();
    assertThat(partner.calls()).hasSize(2);
    assertThat(myHandoff(id).get("state").asText()).isEqualTo("RECEIVED");
  }

  @Test
  void aTooManyRequestsOrServerErrorAnswerIsNeverRetriedAndOnlyASignedNotReceivedRequeuesIt()
      throws Exception {
    // The adapter reports every 429 and 5xx as UNKNOWN_BAD_RESPONSE (the request was already
    // written, so the partner may hold the application). The worker must treat it as unknown.
    String id = confirmed("5000").get("requestId").asText();
    String handoffId = authorized(id).get("handoffId").asText();
    partner.then(SalaryAdvanceHandoffOutcome.UNKNOWN_BAD_RESPONSE, null);
    deliver();
    for (int day = 0; day < 4; day++) {
      w.advance(86_400);
      worker.runOnce();
    }
    assertThat(partner.calls()).as("never resent by the worker, however long it waits").hasSize(1);
    JsonNode unknown = adminHandoff(handoffId);
    assertThat(unknown.get("state").asText()).isEqualTo("DELIVERY_UNKNOWN");
    assertThat(unknown.get("lastOutcomeCode").asText()).isEqualTo("UNKNOWN_BAD_RESPONSE");
    assertThat(myHandoff(id).get("state").asText()).isEqualTo("DELIVERY_UNKNOWN");

    // Neither an inconclusive answer nor a missing one allows a resend.
    partner.thenInquiry(InquiryOutcome.INCONCLUSIVE, null);
    w.c.problem(
        reconcile(handoffId, unknown.get("version").asInt()),
        409,
        "SALARY_ADVANCE_HANDOFF_DELIVERY_UNKNOWN");
    w.advance(86_400);
    worker.runOnce();
    assertThat(partner.calls()).hasSize(1);
    assertThat(adminHandoff(handoffId).get("state").asText()).isEqualTo("DELIVERY_UNKNOWN");

    // A signed, application-bound NOT_RECEIVED is the only proof that requeues it, once.
    partner.thenInquiry(InquiryOutcome.NOT_RECEIVED, null);
    JsonNode queued = w.c.expect(reconcile(handoffId, unknown.get("version").asInt()), 200);
    assertThat(queued.get("state").asText()).isEqualTo("QUEUED");
    assertThat(partner.calls()).as("reconcile itself delivers nothing").hasSize(1);
    deliver();
    assertThat(partner.calls()).hasSize(2);
    assertThat(partner.calls().get(1).applicationId())
        .as("the same application id is reused")
        .isEqualTo(partner.calls().get(0).applicationId());
    assertThat(myHandoff(id).get("state").asText()).isEqualTo("RECEIVED");
    w.advance(86_400);
    worker.runOnce();
    assertThat(partner.calls()).as("no resend after the acknowledgement").hasSize(2);
  }

  @Test
  void aReceiverThatStoredTheApplicationBeforeA503IsNeverResentOnceItProvesReceipt()
      throws Exception {
    String id = confirmed("5000").get("requestId").asText();
    String handoffId = authorized(id).get("handoffId").asText();
    partner.then(SalaryAdvanceHandoffOutcome.UNKNOWN_BAD_RESPONSE, null);
    deliver();
    JsonNode unknown = adminHandoff(handoffId);
    assertThat(unknown.get("state").asText()).isEqualTo("DELIVERY_UNKNOWN");
    partner.thenInquiry(InquiryOutcome.RECEIVED, "SYNTH-STORED-1");
    JsonNode received = w.c.expect(reconcile(handoffId, unknown.get("version").asInt()), 200);
    assertThat(received.get("state").asText()).isEqualTo("RECEIVED");
    for (int day = 0; day < 3; day++) {
      w.advance(86_400);
      worker.runOnce();
    }
    assertThat(partner.calls()).as("the partner holds it: exactly one delivery").hasSize(1);
    assertThat(myHandoff(id).get("state").asText()).isEqualTo("RECEIVED");
  }

  @Test
  void anAdministratorWhoAuthorizedNothingCannotReconcileAReceivedHandoffAndStatesAreGuarded()
      throws Exception {
    String id = confirmed("5000").get("requestId").asText();
    String handoffId = authorized(id).get("handoffId").asText();
    deliver();
    JsonNode received = adminHandoff(handoffId);
    w.c.problem(
        reconcile(handoffId, received.get("version").asInt()),
        409,
        "SALARY_ADVANCE_HANDOFF_TRANSITION_NOT_ALLOWED");
    w.c.problem(
        reconcile(handoffId, received.get("version").asInt() + 5),
        409,
        "SALARY_ADVANCE_HANDOFF_VERSION_CONFLICT");
  }

  // ------------------------------------------------------------------------------------------
  // Signed callbacks
  // ------------------------------------------------------------------------------------------

  @Test
  void aSignedCallbackMovesTheReviewForwardAndEverythingElseIsOneGenericRefusal() throws Exception {
    String id = confirmed("5000").get("requestId").asText();
    JsonNode handoff = authorized(id);
    UUID application =
        UUID.fromString(
            adminHandoff(handoff.get("handoffId").asText()).get("applicationId").asText());
    // No callback is accepted before the partner holds the request.
    assertThat(status(callback(application, "UNDER_REVIEW", null))).isEqualTo(400);
    deliver();

    long now = clock.instant().getEpochSecond();
    UUID replayId = UUID.randomUUID();
    assertThat(status(callback(application, "UNDER_REVIEW", null, now, replayId, CALLBACK_KEY)))
        .isEqualTo(204);
    assertThat(myHandoff(id).get("reviewState").asText()).isEqualTo("UNDER_REVIEW");
    // Replay of the same callback id, a bad signature, a stale timestamp, an unknown application,
    // an illegal transition and a malformed reason: the same generic answer.
    List<MockHttpServletRequestBuilder> refused =
        List.of(
            callback(
                application,
                "MORE_INFORMATION_REQUIRED",
                "CLARIFICATION",
                now,
                replayId,
                CALLBACK_KEY),
            callback(
                application, "DECLINED", "PARTNER_POLICY", now, UUID.randomUUID(), "x".repeat(40)),
            callback(
                application,
                "DECLINED",
                "PARTNER_POLICY",
                now - 3600,
                UUID.randomUUID(),
                CALLBACK_KEY),
            callback(UUID.randomUUID(), "DECLINED", "PARTNER_POLICY"),
            callback(application, "UNDER_REVIEW", null),
            callback(application, "DECLINED", "FREE_TEXT_REASON"),
            callback(application, "APPROVED", null));
    for (MockHttpServletRequestBuilder request : refused) {
      JsonNode problem = w.c.expect(request, 400);
      assertThat(problem.get("code").asText()).isEqualTo("PARTNER_CALLBACK_INVALID");
    }
    assertThat(myHandoff(id).get("reviewState").asText()).isEqualTo("UNDER_REVIEW");
    assertThat(status(callback(application, "DECLINED", "PARTNER_POLICY"))).isEqualTo(204);
    assertThat(myHandoff(id).get("reviewState").asText()).isEqualTo("DECLINED");
    // Terminal: nothing moves it again, and nothing in the delivery state changed.
    assertThat(status(callback(application, "UNDER_REVIEW", null))).isEqualTo(400);
    assertThat(myHandoff(id).get("state").asText()).isEqualTo("RECEIVED");
    assertThat(requestStatus(id)).isEqualTo("EMPLOYER_CONFIRMED");
  }

  // ------------------------------------------------------------------------------------------
  // Authority and isolation
  // ------------------------------------------------------------------------------------------

  @Test
  void onlyTheOwnerAndAnMfaTenantAdministratorSeeAHandoffAndEveryoneElseGetsTheSameNotFound()
      throws Exception {
    String id = confirmed("5000").get("requestId").asText();
    JsonNode handoff = authorized(id);
    String handoffId = handoff.get("handoffId").asText();
    CompensationWorld.Person other = w.linkedEmployee("Ilunga", PayrollPackageWorld.GROSS);
    w.c.problem(
        get(handoffPath(id)).header("Authorization", other.bearer()),
        404,
        "SALARY_ADVANCE_HANDOFF_NOT_FOUND");
    w.c.problem(
        CompensationWorld.json(handoffPath(id) + "/preview", other.bearer(), null, Map.of()),
        404,
        "SALARY_ADVANCE_REQUEST_NOT_FOUND");
    assertThat(
            w.c
                .expect(
                    get("/api/v1/me/salary-advance-handoffs")
                        .header("Authorization", other.bearer()),
                    200)
                .get("items"))
        .isEmpty();

    String manager = Hierarchy.bearer(w.tenant, "sub-ho-manager-" + UUID.randomUUID(), "manager");
    String noMfaSubject = "sub-ho-nomfa-" + UUID.randomUUID();
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
      assertThat(status(get(ADMIN_HANDOFFS).header("Authorization", bearer))).isEqualTo(403);
      assertThat(status(get(ADMIN_HANDOFFS + "/" + handoffId).header("Authorization", bearer)))
          .isEqualTo(403);
      assertThat(status(reconcileAs(bearer, handoffId, 1))).isEqualTo(403);
    }
    assertThat(status(get(ADMIN_HANDOFFS))).isEqualTo(401);
    String foreign =
        Hierarchy.bearer(
            Hierarchy.newTenant(mvc), "sub-ho-foreign-" + UUID.randomUUID(), "tenant-admin");
    w.c.problem(
        get(ADMIN_HANDOFFS + "/" + handoffId).header("Authorization", foreign),
        404,
        "SALARY_ADVANCE_HANDOFF_NOT_FOUND");
    w.c.problem(reconcileAs(foreign, handoffId, 1), 404, "SALARY_ADVANCE_HANDOFF_NOT_FOUND");
    // An administrator is not an employee on the self-service routes.
    assertThat(status(get(handoffPath(id)).header("Authorization", w.admin))).isIn(403, 404);
    assertThat(status(get("/api/v1/me/salary-advance-handoffs").header("Authorization", w.admin)))
        .isIn(403, 404);
    // The employee's view hides the administrator-only fields.
    assertThat(myHandoff(id).has("applicationId") && !myHandoff(id).get("applicationId").isNull())
        .isFalse();
    assertThat(adminHandoff(handoffId).get("applicationId").asText()).isNotBlank();
  }

  @Test
  void theListsAreBoundedAndCursorsCarryNothingSensitive() throws Exception {
    for (String amount : List.of("5000", "5100", "5200")) {
      authorized(confirmed(amount).get("requestId").asText());
    }
    JsonNode page =
        w.c.expect(get(ADMIN_HANDOFFS + "?limit=2").header("Authorization", w.admin), 200);
    assertThat(page.get("items")).hasSize(2);
    String cursor = page.get("nextCursor").asText();
    assertThat(cursor).doesNotContain("5000").doesNotContain("SA-HANDOFF");
    JsonNode rest =
        w.c.expect(
            get(ADMIN_HANDOFFS + "?limit=2&cursor=" + cursor).header("Authorization", w.admin),
            200);
    assertThat(rest.get("items")).hasSize(1);
    assertThat(
            w.c
                .expect(
                    get("/api/v1/me/salary-advance-handoffs").header("Authorization", employee),
                    200)
                .get("items"))
        .hasSize(3);
  }

  // ------------------------------------------------------------------------------------------
  // Atomicity, privacy, direct-SQL guards, concurrency
  // ------------------------------------------------------------------------------------------

  @Test
  void aRefusedAuthorizationWritesNothingAndConsumesNoKey() throws Exception {
    String id = confirmed("5000").get("requestId").asText();
    JsonNode preview = previewHandoff(id);
    List<Integer> before = counts();
    String key = Organizations.newKey();
    w.c.problem(
        authorize(id, key, authorization(preview, false, true)),
        422,
        "SALARY_ADVANCE_HANDOFF_CONSENT_REQUIRED");
    assertThat(counts()).isEqualTo(before);
    w.c.expect(authorize(id, key, authorization(preview, true, true)), 201);
    assertThat(counts()).isNotEqualTo(before);
  }

  @Test
  void auditOutboxAndPersistedEvidenceNeverCarryAmountsConsentOrSignatures() throws Exception {
    String id = confirmed(SECRET_AMOUNT).get("requestId").asText();
    JsonNode handoff = authorized(id);
    deliver();
    UUID application =
        UUID.fromString(
            adminHandoff(handoff.get("handoffId").asText()).get("applicationId").asText());
    assertThat(status(callback(application, "UNDER_REVIEW", null))).isEqualTo(204);
    List<String> rows =
        new ArrayList<>(
            jdbc.queryForList(
                "SELECT metadata::text || coalesce(after_state_sha256, '') || actor_subject"
                    + " FROM platform.audit_event WHERE tenant_id = ?"
                    + " AND action LIKE 'salary-advance-handoff.%'",
                String.class, w.tenant));
    rows.addAll(
        jdbc.queryForList(
            "SELECT envelope::text FROM platform.outbox_event WHERE tenant_id = ?"
                + " AND event_type LIKE 'payroll.salary-advance-handoff.%'",
            String.class, w.tenant));
    // The idempotency record keeps the response body for replay, exactly as in MVP-120A; the
    // contract forbids amounts and consent evidence in audit, outbox, logs and metrics only.
    assertThat(rows).isNotEmpty();
    String text = String.join("\n", rows);
    assertThat(text).doesNotContain(SECRET_AMOUNT).doesNotContain("SA-HANDOFF-1");
    assertThat(text).doesNotContain("v1=");
    assertThat(audits("salary-advance-handoff.authorize")).isEqualTo(1);
    assertThat(audits("salary-advance-handoff.delivery")).isGreaterThanOrEqualTo(1);
    assertThat(audits("salary-advance-handoff.partner-status")).isEqualTo(1);
    // The signed delivery body is sent, never stored.
    assertThat(
            jdbc.queryForList(
                "SELECT outcome FROM payroll.salary_advance_handoff_attempt"
                    + " WHERE tenant_id = ?",
                String.class,
                w.tenant))
        .containsExactly("RECEIVED_ACKNOWLEDGED");
  }

  @Test
  void theDatabaseRefusesDirectEditsOfEvidenceAndOfIllegalStates() throws Exception {
    String id = confirmed("5000").get("requestId").asText();
    String handoffId = authorized(id).get("handoffId").asText();
    deliver();
    UUID handoff = UUID.fromString(handoffId);
    assertRefused(
        "UPDATE payroll.salary_advance_handoff_consent SET language = 'fr' WHERE handoff_id = ?",
        handoff);
    assertRefused("DELETE FROM payroll.salary_advance_handoff_event WHERE handoff_id = ?", handoff);
    assertRefused(
        "UPDATE payroll.salary_advance_handoff_ack SET partner_reference = 'X' WHERE handoff_id = ?",
        handoff);
    assertRefused(
        "DELETE FROM payroll.salary_advance_handoff_attempt WHERE handoff_id = ?", handoff);
    assertRefused("DELETE FROM payroll.salary_advance_handoff WHERE id = ?", handoff);
    assertRefused(
        "UPDATE payroll.salary_advance_handoff SET state = 'WITHDRAWN' WHERE id = ?", handoff);
    assertRefused(
        "UPDATE payroll.salary_advance_handoff SET state = 'QUEUED' WHERE id = ?", handoff);
    assertRefused(
        "UPDATE payroll.salary_advance_handoff SET review_state = 'DECLINED' WHERE id = ?",
        handoff);
    // The frozen evidence of a handoff never changes, even with a correct version step.
    assertRefused(
        "UPDATE payroll.salary_advance_handoff SET payload_sha256 = repeat('a', 64),"
            + " row_version = row_version + 1 WHERE id = ?",
        handoff);
    assertRefused(
        "UPDATE payroll.salary_advance_handoff SET authorized_by = 'someone-else',"
            + " row_version = row_version + 1 WHERE id = ?",
        handoff);
    assertThat(adminHandoff(handoffId).get("state").asText()).isEqualTo("RECEIVED");
  }

  @Test
  void eachDatabaseGuardRefusesOnItsOwnWhenTheDeferredCompletenessCheckIsOff() throws Exception {
    // The schema is layered: a bare update is also refused by the deferred completeness trigger.
    // With that layer switched off for this test only, the frozen-evidence guard and the closed
    // lifecycle must each still refuse, so neither can be removed unnoticed.
    String id = confirmed("5000").get("requestId").asText();
    UUID handoff = UUID.fromString(authorized(id).get("handoffId").asText());
    String completeness =
        "payroll.salary_advance_handoff TRIGGER payroll_salary_advance_handoff_complete";
    jdbc.execute("ALTER TABLE " + completeness.replace(" TRIGGER ", " DISABLE TRIGGER "));
    try {
      assertRefused(
          "UPDATE payroll.salary_advance_handoff SET state = 'SENDING',"
              + " attempt_count = attempt_count + 1, row_version = row_version + 1,"
              + " next_attempt_at = NULL, lease_until = now() + interval '2 minutes',"
              + " payload_sha256 = repeat('a', 64) WHERE id = ?",
          handoff);
      assertRefused(
          "UPDATE payroll.salary_advance_handoff SET state = 'DELIVERY_UNKNOWN',"
              + " next_attempt_at = NULL, row_version = row_version + 1 WHERE id = ?",
          handoff);
      assertRefused(
          "UPDATE payroll.salary_advance_handoff SET state = 'RECEIVED', review_state = 'RECEIVED',"
              + " next_attempt_at = NULL, row_version = row_version + 1 WHERE id = ?",
          handoff);
    } finally {
      jdbc.execute("ALTER TABLE " + completeness.replace(" TRIGGER ", " ENABLE TRIGGER "));
    }
    assertThat(adminHandoff(handoff.toString()).get("state").asText()).isEqualTo("QUEUED");
  }

  private void assertRefused(String sql, Object... args) {
    boolean refused = false;
    try {
      jdbc.update(sql, args);
    } catch (RuntimeException expected) {
      refused = true;
    }
    assertThat(refused).as(sql).isTrue();
  }

  @Test
  void twoSimultaneousAuthorizationsCreateExactlyOneHandoff() throws Exception {
    String id = confirmed("5000").get("requestId").asText();
    JsonNode preview = previewHandoff(id);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Callable<Integer>> tasks = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        String key = Organizations.newKey();
        tasks.add(() -> status(authorize(id, key, authorization(preview, true, true))));
      }
      List<Integer> codes = new ArrayList<>();
      for (Future<Integer> future : pool.invokeAll(tasks)) {
        codes.add(future.get());
      }
      assertThat(codes).containsExactlyInAnyOrder(201, 409);
    } finally {
      pool.shutdownNow();
    }
    assertThat(handoffCount()).isEqualTo(1);
    assertThat(audits("salary-advance-handoff.authorize")).isEqualTo(1);
  }

  @Test
  void aWithdrawalRacingTheReceiptEndsInExactlyOneConsistentState() throws Exception {
    String id = confirmed("5000").get("requestId").asText();
    JsonNode queued = authorized(id);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    int version = queued.get("version").asInt();
    try {
      Future<Integer> withdrawal = pool.submit(() -> status(withdrawHandoff(id, version)));
      Future<Integer> delivery = pool.submit(() -> worker.runOnce());
      withdrawal.get();
      delivery.get();
    } finally {
      pool.shutdownNow();
    }
    JsonNode view = myHandoff(id);
    String state = view.get("state").asText();
    assertThat(state).isIn("WITHDRAWN", "RECEIVED", "DELIVERY_UNKNOWN");
    if ("WITHDRAWN".equals(state)) {
      assertThat(requestStatus(id)).isEqualTo("WITHDRAWN");
      assertThat(
              w.c.count(
                  "SELECT count(*) FROM payroll.salary_advance_handoff_ack WHERE tenant_id = ?",
                  w.tenant))
          .isZero();
    } else {
      assertThat(requestStatus(id)).isEqualTo("EMPLOYER_CONFIRMED");
    }
    assertThat(partner.calls().size()).isLessThanOrEqualTo(1);
  }

  @Test
  void nothingRealIsConfiguredOrContacted() {
    assertThat(
            jdbc.queryForList(
                "SELECT DISTINCT partner_code FROM payroll.salary_advance_handoff", String.class))
        .allMatch("SYNTHETIC-TEST"::equals);
  }

  private int handoffCount() {
    return w.c.count(
        "SELECT count(*) FROM payroll.salary_advance_handoff WHERE tenant_id = ?", w.tenant);
  }

  private List<Integer> counts() {
    return List.of(
        handoffCount(),
        w.c.count(
            "SELECT count(*) FROM payroll.salary_advance_handoff_consent WHERE tenant_id = ?",
            w.tenant),
        w.c.count(
            "SELECT count(*) FROM payroll.salary_advance_handoff_event WHERE tenant_id = ?",
            w.tenant),
        w.c.count(
            "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?"
                + " AND action LIKE 'salary-advance-handoff.%'",
            w.tenant),
        w.c.count(
            "SELECT count(*) FROM platform.outbox_event WHERE tenant_id = ?"
                + " AND event_type LIKE 'payroll.salary-advance-handoff.%'",
            w.tenant));
  }
}
