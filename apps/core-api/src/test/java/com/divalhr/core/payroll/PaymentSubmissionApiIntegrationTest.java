package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.payroll.PayrollPackageWorld.Chain;
import com.divalhr.core.payroll.application.PaymentSubmissionWorker;
import com.divalhr.core.payroll.domain.PaymentSubmissionOutcome;
import com.divalhr.core.payroll.domain.PaymentSubmissionSigner;
import com.divalhr.core.support.FakePaymentProviderGateway;
import com.divalhr.core.support.FakePaymentProviderGateway.Call;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MVP-095B (Issue #187, ADR 0037) against real PostgreSQL through the public API with a scripted
 * connector: preview and commit of exactly one durable submission, signed canonical delivery, every
 * delivery outcome and its safe state, no blind resend of an unknown delivery, the batch-cancel and
 * payroll-void guards, stale evidence, authority and privacy. Synthetic data only: no socket is
 * opened and no money exists.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@PayrollPackageWorld.HighLimits
@PaymentWorld.HighLimits
@PaymentSubmissionWorld.HighLimits
class PaymentSubmissionApiIntegrationTest {

  private static final String SIGNING_KEY = "test-only-payment-submission-signing-key-001";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;
  @Autowired private FakePaymentProviderGateway provider;
  @Autowired private PaymentSubmissionWorker worker;
  @Autowired private PlatformTransactionManager transactions;

  private PayrollPackageWorld w;
  private PaymentWorld p;
  private PaymentSubmissionWorld s;
  private CompensationWorld.Person ana;
  private String pkg;
  private String batch;

  @BeforeEach
  void world() throws Exception {
    // Settle submissions left by other classes so that scripted results reach this class only.
    provider.reset();
    for (int i = 0; i < 50 && worker.runOnce() > 0; i++) {
      // drain
    }
    provider.reset();
    w = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
    p = new PaymentWorld(w);
    s = new PaymentSubmissionWorld(p);
    UUID pack = StatutoryWorld.verifiedPack(jdbc);
    w.s.activate(pack, w.month);
    ana = w.linkedEmployee("Mukendi", PayrollPackageWorld.GROSS);
    String run = w.s.cdfRun();
    String calculation = w.s.create(run).get("calculationId").asText();
    pkg = w.approve(new Chain(pack, ana.employee(), run, calculation)).get("packageId").asText();
    p.record(ana.employee(), p.bank(PaymentWorld.ACCOUNT, p.early(), "INITIAL_SETUP"));
    batch = p.prepare(pkg).get("batchId").asText();
  }

  @AfterEach
  void reset() {
    clock.reset();
    provider.reset();
  }

  private List<Call> callsFor(UUID request) {
    return provider.calls().stream().filter(call -> call.requestId().equals(request)).toList();
  }

  private void advance(Duration by) {
    clock.set(Instant.now().plus(by));
  }

  // ------------------------------------------------------------------------------------------
  // Preview and commit
  // ------------------------------------------------------------------------------------------

  @Test
  void aPreviewShowsTheSyntheticProviderAndExactTotalsWithoutAnyDestination() throws Exception {
    JsonNode preview = s.preview(batch);
    assertThat(preview.get("status").asText()).isEqualTo("READY");
    assertThat(preview.get("provider").get("code").asText()).isEqualTo("SYNTHETIC-TEST");
    assertThat(preview.get("provider").get("synthetic").asBoolean()).isTrue();
    assertThat(preview.get("currency").asText()).isEqualTo("CDF");
    assertThat(preview.get("lineCount").asInt()).isEqualTo(1);
    assertThat(preview.get("blockers")).isEmpty();
    assertThat(preview.get("previewDigest").asText()).matches("[0-9a-f]{64}");
    assertThat(preview.toString())
        .doesNotContain(PaymentWorld.ACCOUNT)
        .doesNotContain(PaymentWorld.HOLDER);
    JsonNode detail = w.c.expect(PayrollRunWorld.get(w.admin, p.batchPath(batch)), 200);
    assertThat(preview.get("totalNetMinor").asLong())
        .isEqualTo(detail.get("batch").get("totalNetMinor").asLong());
    assertThat(provider.calls()).isEmpty();
  }

  @Test
  void aCommitCreatesExactlyOneQueuedSubmissionAndSendsNothingByItself() throws Exception {
    JsonNode receipt = s.submit(batch);
    assertThat(receipt.get("state").asText()).isEqualTo("QUEUED");
    assertThat(receipt.get("version").asInt()).isEqualTo(1);
    assertThat(receipt.get("providerCode").asText()).isEqualTo("SYNTHETIC-TEST");
    assertThat(s.evidence()).containsExactly(1, 1, 1, 1);
    assertThat(s.state(batch)).isEqualTo("QUEUED");
    assertThat(provider.calls()).isEmpty();
    JsonNode view = s.view(batch);
    assertThat(view.get("state").asText()).isEqualTo("QUEUED");
    assertThat(view.get("attemptCount").asInt()).isZero();
    assertThat(view.has("providerReference")).isTrue();
    assertThat(view.get("providerReference").isNull()).isTrue();
    assertThat(s.attempts(batch).get("items")).hasSize(1);
  }

  @Test
  void theSameIdempotencyKeyReplaysAndASecondSubmissionIsRefused() throws Exception {
    JsonNode preview = s.preview(batch);
    Map<String, Object> body = PaymentSubmissionWorld.committing(preview);
    String key = Organizations.newKey();
    JsonNode first = w.c.expect(s.commitRequest(w.admin, batch, key, body), 201);
    MockHttpServletResponse replay = w.c.call(s.commitRequest(w.admin, batch, key, body));
    assertThat(replay.getStatus()).isEqualTo(201);
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(replay.getContentAsString()).contains(first.get("submissionId").asText());
    w.c.problem(
        s.commitRequest(w.admin, batch, Organizations.newKey(), body),
        409,
        "PAYMENT_SUBMISSION_EXISTS");
    assertThat(s.evidence()).containsExactly(1, 1, 1, 1);
  }

  @Test
  void staleOrAlteredEvidenceCreatesNothing() throws Exception {
    JsonNode preview = s.preview(batch);

    Map<String, Object> version = PaymentSubmissionWorld.committing(preview);
    version.put("expectedBatchVersion", preview.get("batchVersion").asInt() + 1);
    w.c.problem(
        s.commitRequest(w.admin, batch, Organizations.newKey(), version),
        409,
        "PAYMENT_SUBMISSION_VERSION_CONFLICT");

    Map<String, Object> digest = PaymentSubmissionWorld.committing(preview);
    digest.put("previewDigest", "0".repeat(64));
    w.c.problem(
        s.commitRequest(w.admin, batch, Organizations.newKey(), digest),
        409,
        "PAYMENT_SUBMISSION_PREVIEW_STALE");

    Map<String, Object> expired = PaymentSubmissionWorld.committing(preview);
    advance(Duration.ofMinutes(20));
    w.c.problem(
        s.commitRequest(w.admin, batch, Organizations.newKey(), expired),
        409,
        "PAYMENT_SUBMISSION_PREVIEW_STALE");
    clock.reset();

    Map<String, Object> unconfirmed = PaymentSubmissionWorld.committing(preview);
    unconfirmed.put("understandAcceptedIsNotPaid", false);
    assertThat(
            w.c
                .call(s.commitRequest(w.admin, batch, Organizations.newKey(), unconfirmed))
                .getStatus())
        .isEqualTo(400);
    assertThat(s.evidence().get(0)).isZero();
  }

  // ------------------------------------------------------------------------------------------
  // Delivery outcomes
  // ------------------------------------------------------------------------------------------

  @Test
  void anAcceptedDeliveryIsSignedCanonicalAndNeverMeansPaid() throws Exception {
    s.submit(batch);
    UUID request = s.requestId(batch);
    assertThat(worker.runOnce()).isGreaterThanOrEqualTo(1);

    List<Call> calls = callsFor(request);
    assertThat(calls).hasSize(1);
    Call call = calls.get(0);
    assertThat(
            new PaymentSubmissionSigner(SIGNING_KEY).verify(request, call.body(), call.signature()))
        .isTrue();
    assertThat(call.keyVersion()).isEqualTo(1);
    assertThat(call.text())
        .contains("\"requestId\":\"" + request + "\"")
        .contains("\"lineCount\":1")
        .contains("\"currency\":\"CDF\"")
        .contains(PaymentWorld.ACCOUNT);
    JsonNode detail = w.c.expect(PayrollRunWorld.get(w.admin, p.batchPath(batch)), 200);
    assertThat(call.text())
        .contains("\"totalMinor\":" + detail.get("batch").get("totalNetMinor").asLong());

    JsonNode view = s.view(batch);
    assertThat(view.get("state").asText()).isEqualTo("ACCEPTED_FOR_PROCESSING");
    assertThat(view.get("outcomeCode").asText()).isEqualTo("ACCEPTED");
    assertThat(view.get("providerReference").asText()).matches("[A-Z0-9][A-Z0-9._-]{5,63}");
    assertThat(view.get("attemptCount").asInt()).isEqualTo(1);
    assertThat(view.toString()).doesNotContainIgnoringCase("paid").doesNotContain("settle");

    // Terminal: another cycle never sends it again.
    advance(Duration.ofDays(2));
    worker.runOnce();
    assertThat(callsFor(request)).hasSize(1);
    assertThat(s.attempts(batch).get("items")).hasSize(3);
  }

  @Test
  void aRejectionIsFinalAndNeverRetried() throws Exception {
    provider.then(PaymentSubmissionOutcome.REJECTED_SIGNATURE, null);
    s.submit(batch);
    UUID request = s.requestId(batch);
    worker.runOnce();
    JsonNode view = s.view(batch);
    assertThat(view.get("state").asText()).isEqualTo("REJECTED");
    assertThat(view.get("outcomeCode").asText()).isEqualTo("REJECTED_SIGNATURE");
    advance(Duration.ofDays(2));
    worker.runOnce();
    assertThat(callsFor(request)).hasSize(1);
  }

  @Test
  void aTimeoutAfterTheWriteIsUnknownAndIsNeverBlindlyResent() throws Exception {
    provider.then(PaymentSubmissionOutcome.UNKNOWN_TIMEOUT, null);
    s.submit(batch);
    UUID request = s.requestId(batch);
    worker.runOnce();
    JsonNode view = s.view(batch);
    assertThat(view.get("state").asText()).isEqualTo("DELIVERY_UNKNOWN");
    assertThat(view.get("outcomeCode").asText()).isEqualTo("UNKNOWN_TIMEOUT");
    assertThat(view.get("providerReference").isNull()).isTrue();
    advance(Duration.ofDays(2));
    worker.runOnce();
    worker.runOnce();
    assertThat(callsFor(request)).hasSize(1);
    assertThat(s.state(batch)).isEqualTo("DELIVERY_UNKNOWN");
  }

  @Test
  void aTransientFailureIsRetriedWithTheSameRequestIdThenAccepted() throws Exception {
    provider.then(PaymentSubmissionOutcome.TRANSIENT_SERVER, null);
    s.submit(batch);
    UUID request = s.requestId(batch);
    worker.runOnce();
    JsonNode queued = s.view(batch);
    assertThat(queued.get("state").asText()).isEqualTo("QUEUED");
    assertThat(queued.get("outcomeCode").asText()).isEqualTo("TRANSIENT_SERVER");
    assertThat(queued.get("nextAttemptAt").isNull()).isFalse();

    // Not due yet: nothing is sent.
    worker.runOnce();
    assertThat(callsFor(request)).hasSize(1);

    advance(Duration.ofMinutes(2));
    worker.runOnce();
    List<Call> calls = callsFor(request);
    assertThat(calls).hasSize(2);
    assertThat(calls.get(1).requestId()).isEqualTo(calls.get(0).requestId());
    assertThat(calls.get(1).text()).isEqualTo(calls.get(0).text());
    assertThat(s.view(batch).get("state").asText()).isEqualTo("ACCEPTED_FOR_PROCESSING");
    assertThat(s.view(batch).get("attemptCount").asInt()).isEqualTo(2);
  }

  @Test
  void sixTransientFailuresEndInManualInterventionWithoutAnotherSend() throws Exception {
    for (int i = 0; i < 8; i++) {
      provider.then(PaymentSubmissionOutcome.TRANSIENT_CONNECT, null);
    }
    s.submit(batch);
    UUID request = s.requestId(batch);
    Duration[] waits = {
      Duration.ofMinutes(2),
      Duration.ofMinutes(6),
      Duration.ofMinutes(31),
      Duration.ofHours(3),
      Duration.ofHours(13)
    };
    worker.runOnce();
    Instant at = Instant.now();
    for (Duration wait : waits) {
      at = at.plus(wait);
      clock.set(at);
      worker.runOnce();
    }
    assertThat(callsFor(request)).hasSize(6);
    JsonNode view = s.view(batch);
    assertThat(view.get("state").asText()).isEqualTo("MANUAL_INTERVENTION");
    assertThat(view.get("outcomeCode").asText()).isEqualTo("RETRIES_EXHAUSTED");
    clock.set(at.plus(Duration.ofDays(5)));
    worker.runOnce();
    assertThat(callsFor(request)).hasSize(6);
  }

  @Test
  void aWorkerThatDiesMidSendLeavesAnUnknownDeliveryNotAResend() throws Exception {
    provider.thenDo(
        () -> {
          clock.set(Instant.now().plus(Duration.ofMinutes(10)));
          throw new IllegalStateException("worker died");
        });
    s.submit(batch);
    UUID request = s.requestId(batch);
    worker.runOnce();
    assertThat(s.state(batch)).isEqualTo("SENDING");
    worker.runOnce();
    JsonNode view = s.view(batch);
    assertThat(view.get("state").asText()).isEqualTo("DELIVERY_UNKNOWN");
    assertThat(view.get("outcomeCode").asText()).isEqualTo("UNKNOWN_LEASE_EXPIRED");
    advance(Duration.ofDays(2));
    worker.runOnce();
    assertThat(callsFor(request)).hasSize(1);
  }

  /**
   * Damages the sealed bytes of the batch's destination (bypassing the triggers, as a fault would).
   */
  private void corruptDestination() {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              jdbc.execute("SET LOCAL session_replication_role = replica");
              jdbc.update(
                  "UPDATE payroll.payment_destination SET sealed = decode(repeat('ab', 40), 'hex')"
                      + " WHERE tenant_id = ? AND employee_id = ?",
                  w.tenant,
                  ana.employee());
            });
  }

  @Test
  void anUnreadableDestinationBlocksTheWholeBatchBeforeAnythingIsQueued() throws Exception {
    corruptDestination();
    JsonNode preview = s.preview(batch);
    assertThat(preview.get("status").asText()).isEqualTo("BLOCKED");
    assertThat(PayrollPackageWorld.texts(preview.get("blockers")))
        .contains("DESTINATION_UNREADABLE");
    assertThat(preview.get("previewDigest").isNull()).isTrue();
    Map<String, Object> body = PaymentSubmissionWorld.committing(preview);
    body.put("previewDigest", "0".repeat(64));
    body.put("expiresAt", Instant.now().plusSeconds(60).toString());
    w.c.problem(
        s.commitRequest(w.admin, batch, Organizations.newKey(), body),
        409,
        "PAYMENT_SUBMISSION_BLOCKED");
    assertThat(s.evidence().get(0)).isZero();
    assertThat(provider.calls()).isEmpty();
  }

  @Test
  void aDestinationThatBecomesUnreadableAfterQueuingIsNeverSentAndNeedsAPerson() throws Exception {
    s.submit(batch);
    UUID request = s.requestId(batch);
    corruptDestination();
    worker.runOnce();
    JsonNode view = s.view(batch);
    assertThat(view.get("state").asText()).isEqualTo("MANUAL_INTERVENTION");
    assertThat(view.get("outcomeCode").asText()).isEqualTo("NOT_SENT_UNREADABLE");
    assertThat(callsFor(request)).isEmpty();
    advance(Duration.ofDays(2));
    worker.runOnce();
    assertThat(callsFor(request)).isEmpty();
  }

  // ------------------------------------------------------------------------------------------
  // Guards
  // ------------------------------------------------------------------------------------------

  @Test
  void aSubmittedBatchCannotBeCancelledAndItsPackageCannotBeVoided() throws Exception {
    s.submit(batch);

    JsonNode preview = w.c.expect(p.cancelPreview(w.admin, batch, "PREPARED_IN_ERROR"), 200);
    assertThat(preview.get("status").asText()).isEqualTo("BLOCKED");
    assertThat(PayrollPackageWorld.texts(preview.get("blockers"))).contains("ALREADY_SUBMITTED");

    Map<String, Object> cancel = PaymentWorld.cancelCommand("PREPARED_IN_ERROR");
    cancel.put("batchVersion", preview.get("batchVersion").asInt());
    cancel.put("batchDigest", preview.get("batchDigest").asText());
    cancel.put("previewDigest", "0".repeat(64));
    cancel.put("expiresAt", Instant.now().plusSeconds(60).toString());
    cancel.put("noMoneySentAcknowledged", true);
    w.c.problem(
        p.cancelRequest(w.admin, batch, Organizations.newKey(), cancel),
        409,
        "PAYMENT_BATCH_ALREADY_SUBMITTED");

    JsonNode voidPreview = w.voidPreview(pkg, "ADMINISTRATIVE_ERROR", null);
    assertThat(voidPreview.get("status").asText()).isEqualTo("BLOCKED");
    assertThat(PayrollPackageWorld.texts(voidPreview.get("blockers"))).contains("PAYMENT_EXISTS");
    assertThat(p.evidence().get(3)).as("no cancellation evidence").isZero();
  }

  @Test
  void theDatabaseRefusesCancellationAndDeletionOfASubmittedBatchEvenWithoutTheApplication()
      throws Exception {
    s.submit(batch);
    UUID id = UUID.fromString(batch);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE payroll.payment_batch SET status = 'CANCELLED' WHERE tenant_id = ?"
                        + " AND id = ?",
                    w.tenant,
                    id))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbc.update("DELETE FROM payroll.payment_submission WHERE tenant_id = ?", w.tenant))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE payroll.payment_submission SET state = 'ACCEPTED_FOR_PROCESSING'"
                        + " WHERE tenant_id = ?",
                    w.tenant))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE payroll.payment_submission_attempt SET state = 'REJECTED'"
                        + " WHERE tenant_id = ?",
                    w.tenant))
        .isInstanceOf(DataAccessException.class);
    assertThat(s.state(batch)).isEqualTo("QUEUED");
  }

  @Test
  void aCancelledBatchCannotBeSubmitted() throws Exception {
    p.cancel(batch, "PREPARED_IN_ERROR");
    JsonNode preview = s.preview(batch);
    assertThat(preview.get("status").asText()).isEqualTo("BLOCKED");
    assertThat(preview.get("previewDigest").isNull()).isTrue();
    Map<String, Object> body = PaymentSubmissionWorld.committing(preview);
    body.put("previewDigest", "0".repeat(64));
    body.put("expiresAt", Instant.now().plusSeconds(60).toString());
    assertThat(w.c.call(s.commitRequest(w.admin, batch, Organizations.newKey(), body)).getStatus())
        .isEqualTo(409);
    assertThat(s.evidence().get(0)).isZero();
  }

  // ------------------------------------------------------------------------------------------
  // Authority, tenant isolation, headers, privacy
  // ------------------------------------------------------------------------------------------

  @Test
  void aForeignAdministratorAndANonAdministratorReachNothing() throws Exception {
    s.submit(batch);
    PayrollPackageWorld other = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
    List<Integer> before = s.evidence();
    String base = p.batchPath(batch);
    for (String path : List.of(base + "/submission", base + "/submission/attempts")) {
      JsonNode problem = w.c.expect(PayrollRunWorld.get(other.admin, path), 404);
      assertThat(problem.get("code").asText())
          .isIn("PAYMENT_BATCH_NOT_FOUND", "LEGAL_ENTITY_NOT_FOUND");
      assertThat(w.c.call(PayrollRunWorld.get(ana.bearer(), path)).getStatus()).isIn(403, 404);
    }
    assertThat(w.c.call(s.previewRequest(other.admin, batch)).getStatus()).isEqualTo(404);
    assertThat(w.c.call(s.previewRequest(ana.bearer(), batch)).getStatus()).isIn(403, 404);
    JsonNode preview = s.preview(batch);
    assertThat(preview.get("status").asText()).isEqualTo("BLOCKED");
    assertThat(
            w.c
                .call(
                    s.commitRequest(
                        ana.bearer(),
                        batch,
                        Organizations.newKey(),
                        PaymentSubmissionWorld.committing(preview)))
                .getStatus())
        .isIn(403, 404);
    assertThat(s.evidence().subList(0, 2)).isEqualTo(before.subList(0, 2));
  }

  @Test
  void unknownAndMalformedIdentifiersGiveTheSameSafeNotFound() throws Exception {
    String missing = UUID.randomUUID().toString();
    JsonNode unknown =
        w.c.expect(PayrollRunWorld.get(w.admin, p.batchPath(missing) + "/submission"), 404);
    JsonNode malformed =
        w.c.expect(PayrollRunWorld.get(w.admin, p.batchPath("not-a-uuid") + "/submission"), 404);
    assertThat(unknown.get("code").asText()).isEqualTo(malformed.get("code").asText());
    // A real batch without a submission has its own stable code.
    w.c.problem(
        PayrollRunWorld.get(w.admin, p.batchPath(batch) + "/submission"),
        404,
        "PAYMENT_SUBMISSION_NOT_FOUND");
  }

  @Test
  void everyResponseIsPrivateAndNotStored() throws Exception {
    s.submit(batch);
    String base = p.batchPath(batch);
    List<MockHttpServletResponse> responses = new ArrayList<>();
    responses.add(w.c.call(s.previewRequest(w.admin, batch)));
    responses.add(w.c.call(PayrollRunWorld.get(w.admin, base + "/submission")));
    responses.add(w.c.call(PayrollRunWorld.get(w.admin, base + "/submission/attempts")));
    for (MockHttpServletResponse response : responses) {
      assertThat(response.getHeader("Cache-Control")).contains("no-store").contains("private");
    }
  }

  @Test
  void nothingPersistedLoggedOrReturnedHoldsADestinationSignatureOrKey() throws Exception {
    s.submit(batch);
    worker.runOnce();
    UUID request = s.requestId(batch);
    String signature = callsFor(request).get(0).signature();
    String all =
        s.persistedEvidence()
            + s.view(batch)
            + s.attempts(batch)
            + s.preview(batch)
            + jdbc.queryForList(
                "SELECT to_jsonb(t)::text FROM payroll.payment_submission t WHERE tenant_id = ?",
                String.class,
                w.tenant)
            + jdbc.queryForList(
                "SELECT to_jsonb(t)::text FROM payroll.payment_submission_attempt t"
                    + " WHERE tenant_id = ?",
                String.class,
                w.tenant);
    assertThat(all)
        .doesNotContain("SYNTHACCT")
        .doesNotContain("Sentinel")
        .doesNotContain("KIN-01")
        .doesNotContain(signature)
        .doesNotContain(SIGNING_KEY);
    assertThat(s.persistedEvidence()).contains("payment-submission").isNotBlank();
  }

  @Test
  void auditAndEventsCarryIdentifiersStateAndCountsOnly() throws Exception {
    s.submit(batch);
    worker.runOnce();
    s.view(batch);
    s.attempts(batch);
    s.preview(batch);
    assertThat(
            jdbc.queryForList(
                "SELECT DISTINCT action FROM platform.audit_event WHERE tenant_id = ?"
                    + " AND action LIKE 'payment-submission.%'",
                String.class, w.tenant))
        .contains(
            "payment-submission.preview", "payment-submission.create", "payment-submission.read");
    String evidence = s.persistedEvidence();
    assertThat(evidence).doesNotContainPattern("amount|total_net|totalNet|SYNTH-[A-F0-9]{12}");
  }
}
