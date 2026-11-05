package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.payroll.PayrollPackageWorld.Chain;
import com.divalhr.core.payroll.application.PaymentReconciliationRecorder;
import com.divalhr.core.payroll.application.PaymentReconciliationWorker;
import com.divalhr.core.payroll.application.PaymentSubmissionWorker;
import com.divalhr.core.payroll.domain.PaymentInquiryOutcome;
import com.divalhr.core.payroll.domain.PaymentReconciliationDigests;
import com.divalhr.core.support.FakePaymentProviderGateway;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.divalhr.core.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * MVP-095C (Issue #190, ADR 0038) against real PostgreSQL through the public API with a scripted
 * connector: preview and creation of exactly one durable reconciliation of an uncertain submission,
 * every stale-evidence and validation refusal, authority, identical 404s, the inquiry history and
 * its cursors, headers and audit. No API call and no refusal ever contacts the provider (neither an
 * inquiry nor a payment). Synthetic data only.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@PayrollPackageWorld.HighLimits
@PaymentWorld.HighLimits
@PaymentSubmissionWorld.HighLimits
@PaymentReconciliationWorld.HighLimits
class PaymentReconciliationApiIntegrationTest {

  private static final String STALE = "PAYMENT_RECONCILIATION_PREVIEW_STALE";
  private static final String NOT_FOUND = "PAYMENT_RECONCILIATION_NOT_FOUND";
  private static final String HEX12 = "[0-9a-f]{12}";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;
  @Autowired private FakePaymentProviderGateway provider;
  @Autowired private PaymentSubmissionWorker submissions;
  @Autowired private PaymentReconciliationWorker worker;
  @Autowired private PlatformTransactionManager transactions;

  private PayrollPackageWorld w;
  private PaymentWorld p;
  private PaymentSubmissionWorld s;
  private PaymentReconciliationWorld r;
  private CompensationWorld.Person ana;
  private String batch;

  @BeforeEach
  void world() throws Exception {
    PaymentReconciliationWorld.drain(clock, provider, submissions, worker);
    w = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
    p = new PaymentWorld(w);
    s = new PaymentSubmissionWorld(p);
    r = new PaymentReconciliationWorld(s);
    UUID pack = StatutoryWorld.verifiedPack(jdbc);
    w.s.activate(pack, w.month);
    ana = w.linkedEmployee("Mukendi", PayrollPackageWorld.GROSS);
    String run = w.s.cdfRun();
    String calculation = w.s.create(run).get("calculationId").asText();
    String pkg =
        w.approve(new Chain(pack, ana.employee(), run, calculation)).get("packageId").asText();
    p.record(ana.employee(), p.bank(PaymentWorld.ACCOUNT, p.early(), "INITIAL_SETUP"));
    batch = p.prepare(pkg).get("batchId").asText();
  }

  @AfterEach
  void reset() {
    clock.reset();
    provider.reset();
  }

  /** Drives the batch into DELIVERY_UNKNOWN, then forgets the original send. */
  private void unknown() throws Exception {
    r.deliveryUnknown(batch, provider, submissions);
    provider.reset();
  }

  private void noProviderCalls() {
    assertThat(provider.inquiryCalls()).as("inquiries").isEmpty();
    assertThat(provider.calls()).as("payment sends").isEmpty();
  }

  private void advance(Duration by) {
    clock.set(clock.instant().plus(by));
  }

  private List<String> fields(JsonNode problem) {
    List<String> found = new ArrayList<>();
    problem
        .get("params")
        .get("fields")
        .forEach(f -> found.add(f.get("field").asText() + ":" + f.get("constraint").asText()));
    return found;
  }

  // ------------------------------------------------------------------------------------------
  // Preview
  // ------------------------------------------------------------------------------------------

  @Test
  void aPreviewOfAnUnknownDeliveryIsReadyAndShowsOnlyAFingerprintNeverTheRequestId()
      throws Exception {
    unknown();
    UUID request = s.requestId(batch);
    JsonNode preview = r.preview(batch);
    assertThat(preview.get("status").asText()).isEqualTo("READY");
    assertThat(preview.get("submissionState").asText()).isEqualTo("DELIVERY_UNKNOWN");
    assertThat(preview.get("submissionOutcomeCode").asText()).isEqualTo("UNKNOWN_TIMEOUT");
    assertThat(preview.get("submissionVersion").asInt()).isEqualTo(r.submissionVersion(batch));
    assertThat(preview.get("provider").get("code").asText()).isEqualTo("SYNTHETIC-TEST");
    assertThat(preview.get("provider").get("synthetic").asBoolean()).isTrue();
    assertThat(preview.get("blockers")).isEmpty();
    assertThat(preview.get("previous").isNull()).isTrue();
    assertThat(preview.get("previewDigest").asText()).matches("[0-9a-f]{64}");
    assertThat(preview.get("expiresAt").isNull()).isFalse();
    assertThat(preview.get("requestFingerprint").asText()).matches(HEX12);
    assertThat(preview.get("requestFingerprint").asText())
        .isEqualTo(PaymentReconciliationDigests.requestFingerprint(request));
    assertThat(preview.has("requestId")).isFalse();
    assertThat(preview.has("inquiryId")).isFalse();
    assertThat(preview.toString())
        .doesNotContain(request.toString())
        .doesNotContain(request.toString().replace("-", ""))
        .doesNotContain(PaymentWorld.ACCOUNT)
        .doesNotContain(PaymentWorld.HOLDER);
    assertThat(r.evidence()).containsExactly(0, 0, 0, 0);
    noProviderCalls();
  }

  @Test
  void aPreviewOfAManualInterventionIsReadyAndItsStateIsPartOfTheEvidence() throws Exception {
    r.manualIntervention(batch, ana.employee(), transactions, submissions);
    provider.reset();
    JsonNode preview = r.preview(batch);
    assertThat(preview.get("status").asText()).isEqualTo("READY");
    assertThat(preview.get("submissionState").asText()).isEqualTo("MANUAL_INTERVENTION");
    assertThat(preview.get("submissionOutcomeCode").asText()).isEqualTo("NOT_SENT_UNREADABLE");

    Map<String, Object> wrongState = PaymentReconciliationWorld.committing(preview);
    wrongState.put("expectedSubmissionState", "DELIVERY_UNKNOWN");
    w.c.problem(r.commitRequest(w.admin, batch, Organizations.newKey(), wrongState), 409, STALE);
    assertThat(r.evidence().get(0)).isZero();

    JsonNode receipt =
        w.c.expect(
            r.commitRequest(
                w.admin,
                batch,
                Organizations.newKey(),
                PaymentReconciliationWorld.committing(preview)),
            201);
    assertThat(receipt.get("state").asText()).isEqualTo("OPEN");
    assertThat(r.view(batch).get("submissionState").asText()).isEqualTo("MANUAL_INTERVENTION");
    noProviderCalls();
  }

  @Test
  void aSubmissionThatIsNotUncertainIsBlockedAndRefusedWithoutAnyInquiry() throws Exception {
    s.submit(batch);
    JsonNode queued = r.preview(batch);
    assertThat(queued.get("status").asText()).isEqualTo("BLOCKED");
    assertThat(PayrollPackageWorld.texts(queued.get("blockers")))
        .containsExactly("SUBMISSION_NOT_ELIGIBLE");
    assertThat(queued.get("previewDigest").isNull()).isTrue();
    assertThat(queued.get("expiresAt").isNull()).isTrue();
    w.c.problem(
        r.commitRequest(
            w.admin, batch, Organizations.newKey(), PaymentReconciliationWorld.committing(queued)),
        409,
        "PAYMENT_RECONCILIATION_NOT_ELIGIBLE");

    // An accepted delivery is not uncertain either.
    submissions.runOnce();
    assertThat(s.state(batch)).isEqualTo("ACCEPTED_FOR_PROCESSING");
    provider.reset();
    JsonNode accepted = r.preview(batch);
    assertThat(PayrollPackageWorld.texts(accepted.get("blockers")))
        .containsExactly("SUBMISSION_NOT_ELIGIBLE");
    w.c.problem(
        r.commitRequest(
            w.admin,
            batch,
            Organizations.newKey(),
            PaymentReconciliationWorld.committing(accepted)),
        409,
        "PAYMENT_RECONCILIATION_NOT_ELIGIBLE");
    assertThat(r.evidence()).containsExactly(0, 0, 0, 0);
    noProviderCalls();
  }

  @Test
  void anExistingInvestigationBlocksAnotherPreviewAndAnotherCreation() throws Exception {
    unknown();
    JsonNode preview = r.preview(batch);
    JsonNode receipt =
        w.c.expect(
            r.commitRequest(
                w.admin,
                batch,
                Organizations.newKey(),
                PaymentReconciliationWorld.committing(preview)),
            201);
    assertThat(receipt.get("state").asText()).isEqualTo("OPEN");
    assertThat(receipt.get("version").asInt()).isEqualTo(1);
    assertThat(receipt.get("providerCode").asText()).isEqualTo("SYNTHETIC-TEST");
    assertThat(receipt.get("batchId").asText()).isEqualTo(batch);
    assertThat(receipt.get("reconciliationId").asText())
        .isEqualTo(r.reconciliationId(batch).toString());
    assertThat(r.evidence()).containsExactly(1, 1, 1, 1);

    JsonNode again = r.preview(batch);
    assertThat(again.get("status").asText()).isEqualTo("BLOCKED");
    assertThat(PayrollPackageWorld.texts(again.get("blockers")))
        .containsExactly("RECONCILIATION_EXISTS");
    assertThat(again.get("previous").get("state").asText()).isEqualTo("OPEN");
    assertThat(again.get("previous").get("outcomeCode").isNull()).isTrue();
    w.c.problem(
        r.commitRequest(
            w.admin, batch, Organizations.newKey(), PaymentReconciliationWorld.committing(preview)),
        409,
        "PAYMENT_RECONCILIATION_EXISTS");

    JsonNode view = r.view(batch);
    assertThat(view.get("state").asText()).isEqualTo("OPEN");
    assertThat(view.get("attemptCount").asInt()).isZero();
    assertThat(view.get("outcomeCode").isNull()).isTrue();
    assertThat(view.get("nextAttemptAt").isNull()).isFalse();
    assertThat(view.get("submissionState").asText()).isEqualTo("DELIVERY_UNKNOWN");
    assertThat(view.get("requestFingerprint").asText())
        .isEqualTo(preview.get("requestFingerprint").asText());
    assertThat(s.state(batch)).isEqualTo("DELIVERY_UNKNOWN");
    assertThat(r.evidence()).containsExactly(1, 1, 1, 1);
    noProviderCalls();
  }

  // ------------------------------------------------------------------------------------------
  // Create, replay and stale evidence
  // ------------------------------------------------------------------------------------------

  @Test
  void theSameKeyReplaysTheReceiptAndAnotherCommandWithItIsAConflict() throws Exception {
    unknown();
    Map<String, Object> body = PaymentReconciliationWorld.committing(r.preview(batch));
    String key = Organizations.newKey();
    MockHttpServletResponse first = w.c.call(r.commitRequest(w.admin, batch, key, body));
    assertThat(first.getStatus()).isEqualTo(201);
    assertThat(first.getHeader("Idempotent-Replayed")).isNull();
    MockHttpServletResponse replay = w.c.call(r.commitRequest(w.admin, batch, key, body));
    assertThat(replay.getStatus()).isEqualTo(201);
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
    w.c.problem(
        r.commitRequest(
            w.admin, batch, key, PayrollPackageWorld.with(body, "previewDigest", "c".repeat(64))),
        409,
        "IDEMPOTENCY_KEY_REUSED");
    assertThat(r.evidence()).containsExactly(1, 1, 1, 1);
    noProviderCalls();
  }

  @Test
  void staleOrAlteredEvidenceCreatesNothing() throws Exception {
    unknown();
    JsonNode preview = r.preview(batch);

    Map<String, Object> version = PaymentReconciliationWorld.committing(preview);
    version.put("expectedSubmissionVersion", preview.get("submissionVersion").asInt() + 1);
    w.c.problem(r.commitRequest(w.admin, batch, Organizations.newKey(), version), 409, STALE);

    Map<String, Object> state = PaymentReconciliationWorld.committing(preview);
    state.put("expectedSubmissionState", "MANUAL_INTERVENTION");
    w.c.problem(r.commitRequest(w.admin, batch, Organizations.newKey(), state), 409, STALE);

    Map<String, Object> digest = PaymentReconciliationWorld.committing(preview);
    digest.put("previewDigest", "0".repeat(64));
    w.c.problem(r.commitRequest(w.admin, batch, Organizations.newKey(), digest), 409, STALE);

    Instant expiry = Instant.parse(preview.get("expiresAt").asText());
    Map<String, Object> later = PaymentReconciliationWorld.committing(preview);
    later.put("expiresAt", expiry.plus(Duration.ofMinutes(15)).toString());
    w.c.problem(r.commitRequest(w.admin, batch, Organizations.newKey(), later), 409, STALE);

    Map<String, Object> offGrid = PaymentReconciliationWorld.committing(preview);
    offGrid.put("expiresAt", expiry.plusSeconds(1).toString());
    w.c.problem(r.commitRequest(w.admin, batch, Organizations.newKey(), offGrid), 409, STALE);

    Map<String, Object> expired = PaymentReconciliationWorld.committing(preview);
    advance(Duration.ofMinutes(31));
    w.c.problem(r.commitRequest(w.admin, batch, Organizations.newKey(), expired), 409, STALE);
    clock.reset();

    assertThat(r.evidence()).containsExactly(0, 0, 0, 0);
    // Honest, fresh evidence still works once the clock is back.
    w.c.expect(
        r.commitRequest(
            w.admin,
            batch,
            Organizations.newKey(),
            PaymentReconciliationWorld.committing(r.preview(batch))),
        201);
    noProviderCalls();
  }

  @Test
  void wrongConfirmationsAndMalformedFieldsAreFieldErrorsThatNeverEchoTheValue() throws Exception {
    unknown();
    JsonNode preview = r.preview(batch);
    Map<String, Object> good = PaymentReconciliationWorld.committing(preview);

    for (String field : List.of("understandInquiryDoesNotResend", "understandAcceptedIsNotPaid")) {
      JsonNode refused =
          w.c.problem(
              r.commitRequest(
                  w.admin,
                  batch,
                  Organizations.newKey(),
                  PayrollPackageWorld.with(good, field, false)),
              400,
              "VALIDATION_FAILED");
      assertThat(fields(refused)).containsExactly(field + ":REQUIRED");
      refused =
          w.c.problem(
              r.commitRequest(
                  w.admin,
                  batch,
                  Organizations.newKey(),
                  PayrollPackageWorld.with(good, field, "true")),
              400,
              "VALIDATION_FAILED");
      assertThat(fields(refused)).containsExactly(field + ":FORMAT");
      Map<String, Object> missing = new LinkedHashMap<>(good);
      missing.remove(field);
      refused =
          w.c.problem(
              r.commitRequest(w.admin, batch, Organizations.newKey(), missing),
              400,
              "VALIDATION_FAILED");
      assertThat(fields(refused)).containsExactly(field + ":REQUIRED");
    }

    Map<String, Object> invalid = new LinkedHashMap<>();
    invalid.put("expectedSubmissionVersion:RANGE", 0);
    invalid.put("expectedSubmissionVersion:FORMAT", "1");
    invalid.put("expectedSubmissionState:FORMAT", "QUEUED");
    invalid.put("previewDigest:FORMAT", "xyz");
    invalid.put("expiresAt:FORMAT", "tomorrow");
    for (Map.Entry<String, Object> entry : invalid.entrySet()) {
      String field = entry.getKey().substring(0, entry.getKey().indexOf(':'));
      JsonNode refused =
          w.c.problem(
              r.commitRequest(
                  w.admin,
                  batch,
                  Organizations.newKey(),
                  PayrollPackageWorld.with(good, field, entry.getValue())),
              400,
              "VALIDATION_FAILED");
      assertThat(fields(refused)).containsExactly(entry.getKey());
      assertThat(refused.get("params").toString()).doesNotContain("xyz").doesNotContain("tomorrow");
    }

    JsonNode unknownProperty =
        w.c.problem(
            r.commitRequest(
                w.admin, batch, Organizations.newKey(), PayrollPackageWorld.with(good, "extra", 1)),
            400,
            "VALIDATION_FAILED");
    assertThat(fields(unknownProperty)).containsExactly("body:UNKNOWN_PROPERTY");

    JsonNode noKey =
        w.c.problem(r.commitRequest(w.admin, batch, null, good), 400, "VALIDATION_FAILED");
    assertThat(fields(noKey)).containsExactly("Idempotency-Key:REQUIRED");
    assertThat(r.evidence()).containsExactly(0, 0, 0, 0);
    noProviderCalls();
  }

  // ------------------------------------------------------------------------------------------
  // Authority and identical not-found
  // ------------------------------------------------------------------------------------------

  private record Operation(String name, MockHttpServletRequestBuilder request) {}

  /** The four operations, well formed so that a refusal is never a validation error. */
  private List<Operation> operations(String bearer, PaymentReconciliationWorld world, Object id)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("expectedSubmissionVersion", 1);
    body.put("expectedSubmissionState", "DELIVERY_UNKNOWN");
    body.put("previewDigest", "0".repeat(64));
    body.put("expiresAt", Instant.now().plusSeconds(600).toString());
    body.put("understandInquiryDoesNotResend", true);
    body.put("understandAcceptedIsNotPaid", true);
    return List.of(
        new Operation("previewPaymentReconciliation", world.previewRequest(bearer, id)),
        new Operation(
            "createPaymentReconciliation",
            world.commitRequest(bearer, id, Organizations.newKey(), body)),
        new Operation("getPaymentReconciliation", world.getRequest(bearer, id)),
        new Operation(
            "listPaymentReconciliationInquiries", world.inquiriesRequest(bearer, id, null)));
  }

  @Test
  void everyOperationNeedsATenantAdministratorWithMfaAndNothingIsEverSentWhenRefused()
      throws Exception {
    unknown();
    r.open(batch);
    List<Integer> before = r.evidence();
    int audits =
        r.audits("payment-reconciliation.read") + r.audits("payment-reconciliation.preview");

    // Unauthenticated.
    for (Operation call : operations("Bearer ", r, batch)) {
      MockHttpServletRequestBuilder anonymous =
          call.name().startsWith("create") || call.name().startsWith("preview")
              ? MockMvcRequestBuilders.post(
                      r.path(batch) + (call.name().startsWith("preview") ? "/preview" : ""))
                  .contentType("application/json")
                  .content("{}")
              : MockMvcRequestBuilders.get(
                  r.path(batch) + (call.name().startsWith("list") ? "/inquiries" : ""));
      assertThat(w.c.call(anonymous).getStatus()).as(call.name()).isEqualTo(401);
    }

    // Password-only and otherwise insufficient assurance.
    for (Object acr :
        Arrays.asList(null, TestTokens.PASSWORD_ACR, "0", "1", "urn:divalhr:loa:mfa ", "")) {
      String admin =
          PaymentReconciliationWorld.bearer(w.tenant, "sub-rec-mfa", acr, "tenant-admin");
      for (Operation call : operations(admin, r, batch)) {
        MockHttpServletResponse response = w.c.call(call.request());
        assertThat(response.getStatus()).as(call.name() + " " + acr).isEqualTo(403);
        assertThat(
                CompensationWorld.JSON.readTree(response.getContentAsString()).get("code").asText())
            .isEqualTo("MFA_REQUIRED");
      }
    }

    // Employees, managers, platform administrators and a linked employee.
    for (String role : List.of("employee", "manager", "platform-admin")) {
      for (Object acr : List.of(TestTokens.PASSWORD_ACR, TestTokens.MFA_ACR)) {
        String caller = PaymentReconciliationWorld.bearer(w.tenant, "sub-rec-" + role, acr, role);
        for (Operation call : operations(caller, r, batch)) {
          MockHttpServletResponse response = w.c.call(call.request());
          assertThat(response.getStatus()).as(call.name() + " " + role).isEqualTo(403);
          assertThat(
                  CompensationWorld.JSON
                      .readTree(response.getContentAsString())
                      .get("code")
                      .asText())
              .isEqualTo("ACCESS_DENIED");
        }
      }
    }
    for (Operation call : operations(ana.bearer(), r, batch)) {
      assertThat(w.c.call(call.request()).getStatus()).as(call.name()).isEqualTo(403);
    }

    assertThat(r.evidence()).isEqualTo(before);
    assertThat(r.audits("payment-reconciliation.read") + r.audits("payment-reconciliation.preview"))
        .isEqualTo(audits);
    noProviderCalls();
  }

  @Test
  void foreignTenantUnknownMalformedAndSubmissionLessBatchesGiveOneIdenticalNotFound()
      throws Exception {
    // This batch has no submission yet: it is the same 404 as everything below.
    String noSubmission = batch;
    PayrollPackageWorld other = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
    PaymentReconciliationWorld foreign =
        new PaymentReconciliationWorld(new PaymentSubmissionWorld(new PaymentWorld(other)));
    List<Integer> before = r.evidence();

    List<String> expected = null;
    List<Operation> calls = new ArrayList<>();
    calls.addAll(operations(w.admin, r, noSubmission));
    calls.addAll(operations(w.admin, r, UUID.randomUUID()));
    calls.addAll(operations(w.admin, r, "not-a-uuid"));
    // A foreign administrator on this tenant's identifiers and on their own entity.
    String theirs = "/api/v1/legal-entities/" + other.entity + "/payment-batches/" + noSubmission;
    String mine = "/api/v1/legal-entities/" + w.entity + "/payment-batches/" + noSubmission;
    calls.addAll(operations(other.admin, r, noSubmission));
    calls.addAll(operations(other.admin, foreign, noSubmission));
    assertThat(theirs).isNotEqualTo(mine);
    for (Operation call : calls) {
      MockHttpServletResponse response = w.c.call(call.request());
      assertThat(response.getStatus()).as(call.name()).isEqualTo(404);
      List<String> refusal =
          PaymentReconciliationWorld.refusal(
              CompensationWorld.JSON.readTree(response.getContentAsString()));
      assertThat(refusal.get(2)).isEqualTo(NOT_FOUND);
      if (expected == null) {
        expected = refusal;
      }
      assertThat(refusal).as(call.name()).isEqualTo(expected);
    }

    // A real submission of this tenant is equally invisible to a foreign administrator.
    unknown();
    r.open(batch);
    for (Operation call : operations(other.admin, r, batch)) {
      JsonNode problem = w.c.expect(call.request(), 404);
      assertThat(PaymentReconciliationWorld.refusal(problem)).as(call.name()).isEqualTo(expected);
    }
    for (Operation call : operations(other.admin, foreign, batch)) {
      JsonNode problem = w.c.expect(call.request(), 404);
      assertThat(PaymentReconciliationWorld.refusal(problem)).as(call.name()).isEqualTo(expected);
    }
    // This tenant's batch with an existing submission but no reconciliation is the same 404 too.
    assertThat(r.evidence().get(0)).isEqualTo(1);
    assertThat(before.get(0)).isZero();
    noProviderCalls();
  }

  @Test
  void aSubmissionWithoutAnInvestigationHasNoCurrentStateOrHistory() throws Exception {
    unknown();
    for (MockHttpServletRequestBuilder request :
        List.of(r.getRequest(w.admin, batch), r.inquiriesRequest(w.admin, batch, null))) {
      w.c.problem(request, 404, NOT_FOUND);
    }
    noProviderCalls();
  }

  // ------------------------------------------------------------------------------------------
  // History, pagination and cursors
  // ------------------------------------------------------------------------------------------

  private List<Integer> sequences(JsonNode page) {
    List<Integer> found = new ArrayList<>();
    page.get("items").forEach(item -> found.add(item.get("sequence").asInt()));
    return found;
  }

  @Test
  void theHistoryIsNewestFirstPagedByAKeysetAndBoundToItsScope() throws Exception {
    unknown();
    Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    clock.set(base);
    r.open(batch);
    for (int i = 0; i < 3; i++) {
      provider.thenInquiry(PaymentInquiryOutcome.TRANSIENT_SERVER);
    }
    worker.runOnce();
    clock.set(base.plusSeconds(30));
    worker.runOnce();
    clock.set(base.plusSeconds(150));
    worker.runOnce();
    JsonNode view = r.view(batch);
    assertThat(view.get("state").asText()).isEqualTo("OPEN");
    assertThat(view.get("version").asInt()).isEqualTo(7);
    assertThat(view.get("attemptCount").asInt()).isEqualTo(3);
    assertThat(provider.calls()).as("no payment is ever sent").isEmpty();

    JsonNode all = r.inquiries(batch);
    assertThat(sequences(all)).containsExactly(7, 6, 5, 4, 3, 2, 1);
    assertThat(all.get("nextCursor").isNull()).isTrue();
    JsonNode opening = all.get("items").get(6);
    assertThat(opening.get("state").asText()).isEqualTo("OPEN");
    assertThat(opening.get("attemptNumber").asInt()).isZero();
    assertThat(opening.get("outcomeCode").isNull()).isTrue();
    assertThat(opening.get("authenticated").asBoolean()).isFalse();
    assertThat(opening.get("occurredAt").isNull()).isFalse();
    assertThat(all.get("items").get(0).get("outcomeCode").asText()).isEqualTo("TRANSIENT_SERVER");

    JsonNode first = r.inquiries(batch, "limit=3");
    assertThat(sequences(first)).containsExactly(7, 6, 5);
    String cursor = first.get("nextCursor").asText();
    JsonNode second = r.inquiries(batch, "limit=3&cursor=" + cursor);
    assertThat(sequences(second)).containsExactly(4, 3, 2);
    JsonNode third = r.inquiries(batch, "limit=3&cursor=" + second.get("nextCursor").asText());
    assertThat(sequences(third)).containsExactly(1);
    assertThat(third.get("nextCursor").isNull()).isTrue();
    assertThat(sequences(r.inquiries(batch, "limit=1"))).containsExactly(7);
    assertThat(sequences(r.inquiries(batch, "limit=100"))).hasSize(7);

    // Page bounds.
    for (String bad : List.of("limit=0", "limit=101", "limit=abc", "limit=-1", "limit=1.5")) {
      JsonNode refused =
          w.c.problem(r.inquiriesRequest(w.admin, batch, bad), 400, "VALIDATION_FAILED");
      assertThat(fields(refused)).containsExactly("limit:RANGE");
    }

    // A tampered, foreign or garbage cursor is one stable refusal.
    int at = 10;
    char swapped = cursor.charAt(at) == 'A' ? 'B' : 'A';
    String tampered = cursor.substring(0, at) + swapped + cursor.substring(at + 1);
    w.c.problem(
        r.inquiriesRequest(w.admin, batch, "limit=3&cursor=" + tampered), 400, "CURSOR_INVALID");
    w.c.problem(
        r.inquiriesRequest(w.admin, batch, "limit=3&cursor=garbage"), 400, "CURSOR_INVALID");
    // Another page size is another scope.
    w.c.problem(
        r.inquiriesRequest(w.admin, batch, "limit=2&cursor=" + cursor), 400, "CURSOR_INVALID");
    // Another administrator cannot use it.
    String colleague =
        PaymentReconciliationWorld.bearer(
            w.tenant, "sub-rec-colleague", TestTokens.MFA_ACR, "tenant-admin");
    w.c.problem(
        PayrollRunWorld.get(colleague, r.path(batch) + "/inquiries?limit=3&cursor=" + cursor),
        400,
        "CURSOR_INVALID");
    // A cursor of the submission's attempt history is not one of the inquiry history.
    JsonNode attemptPage =
        w.c.expect(
            PayrollRunWorld.get(w.admin, s.path(batch) + "/submission/attempts?limit=1"), 200);
    assertThat(attemptPage.get("nextCursor").isNull()).isFalse();
    String attempts = attemptPage.get("nextCursor").asText();
    w.c.problem(
        r.inquiriesRequest(w.admin, batch, "limit=1&cursor=" + attempts), 400, "CURSOR_INVALID");
    // The cursor still works for its own scope.
    assertThat(sequences(r.inquiries(batch, "limit=3&cursor=" + cursor))).containsExactly(4, 3, 2);
  }

  // ------------------------------------------------------------------------------------------
  // Headers and audit
  // ------------------------------------------------------------------------------------------

  @Test
  void everyResponseIsPrivateAndNotStored() throws Exception {
    unknown();
    List<MockHttpServletResponse> responses = new ArrayList<>();
    responses.add(w.c.call(r.previewRequest(w.admin, batch)));
    JsonNode preview = r.preview(batch);
    String key = Organizations.newKey();
    Map<String, Object> body = PaymentReconciliationWorld.committing(preview);
    responses.add(w.c.call(r.commitRequest(w.admin, batch, key, body)));
    responses.add(w.c.call(r.commitRequest(w.admin, batch, key, body)));
    responses.add(w.c.call(r.getRequest(w.admin, batch)));
    responses.add(w.c.call(r.inquiriesRequest(w.admin, batch, null)));
    List<Integer> statuses = new ArrayList<>();
    for (MockHttpServletResponse response : responses) {
      statuses.add(response.getStatus());
      assertThat(response.getHeader("Cache-Control")).contains("no-store").contains("private");
    }
    assertThat(statuses).containsExactly(200, 201, 201, 200, 200);
    noProviderCalls();
  }

  @Test
  void auditAndEventsCarryAllowListedIdentifiersStateAndCountsOnly() throws Exception {
    unknown();
    r.preview(batch);
    r.open(batch);
    r.view(batch);
    r.inquiries(batch);
    provider.thenInquiry(PaymentInquiryOutcome.PENDING);
    worker.runOnce();

    assertThat(
            jdbc.queryForList(
                "SELECT DISTINCT action FROM platform.audit_event WHERE tenant_id = ?"
                    + " AND action LIKE 'payment-reconciliation.%'",
                String.class, w.tenant))
        .containsExactlyInAnyOrder(
            "payment-reconciliation.preview",
            "payment-reconciliation.create",
            "payment-reconciliation.read",
            "payment-reconciliation.inquiry");
    // preview (the one in open) + the explicit one; create; view + history; claim + conclusion.
    assertThat(r.audits("payment-reconciliation.preview")).isEqualTo(2);
    assertThat(r.audits("payment-reconciliation.create")).isEqualTo(1);
    assertThat(r.audits("payment-reconciliation.read")).isEqualTo(2);
    assertThat(r.audits("payment-reconciliation.inquiry")).isEqualTo(2);

    Set<String> allowed = PaymentReconciliationRecorder.ALLOWED;
    List<String> keys =
        jdbc.queryForList(
            "SELECT DISTINCT k FROM platform.audit_event e, jsonb_object_keys(e.metadata::jsonb) k"
                + " WHERE e.tenant_id = ? AND e.action LIKE 'payment-reconciliation.%'",
            String.class, w.tenant);
    assertThat(new HashSet<>(keys)).isSubsetOf(allowed);
    List<String> eventKeys =
        jdbc.queryForList(
            "SELECT DISTINCT k FROM platform.outbox_event e,"
                + " jsonb_object_keys((e.envelope::jsonb) -> 'data') k WHERE e.tenant_id = ?"
                + " AND e.event_type LIKE 'payroll.payment-reconciliation.%'",
            String.class, w.tenant);
    assertThat(new HashSet<>(eventKeys)).isSubsetOf(allowed);
    assertThat(r.persistedEvidence())
        .doesNotContainPattern("(?i)amount|total_?net|totalMinor|SYNTH-[A-F0-9]{12}")
        .doesNotContain(s.requestId(batch).toString())
        .doesNotContain(r.inquiryId(batch).toString())
        .doesNotContain(PaymentWorld.ACCOUNT);
    assertThat(provider.calls()).isEmpty();
  }
}
