package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.payroll.PayrollPackageWorld.Chain;
import com.divalhr.core.payroll.application.PaymentProviderGateway.InquiryResult;
import com.divalhr.core.payroll.application.PaymentReconciliationRecorder;
import com.divalhr.core.payroll.application.PaymentReconciliationWorker;
import com.divalhr.core.payroll.application.PaymentSubmissionWorker;
import com.divalhr.core.payroll.domain.PaymentInquiryBody;
import com.divalhr.core.payroll.domain.PaymentInquiryOutcome;
import com.divalhr.core.payroll.domain.PaymentSubmissionSigner;
import com.divalhr.core.support.FakePaymentProviderGateway;
import com.divalhr.core.support.FakePaymentProviderGateway.InquiryCall;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-095C (Issue #190, ADR 0038) against real PostgreSQL with a scripted connector: every one of
 * the 17 inquiry outcomes is recorded to its state, the bounded retry schedule, the stable request
 * and inquiry IDs, lease recovery, a lost lease, two racing workers, and the no-resend rule: the
 * worker only ever asks for the status, so the payment {@code send} is never called and the
 * original submission and its attempts stay row for row as they were. Synthetic data only.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@PayrollPackageWorld.HighLimits
@PaymentWorld.HighLimits
@PaymentSubmissionWorld.HighLimits
@PaymentReconciliationWorld.HighLimits
class PaymentReconciliationWorkerIntegrationTest {

  private static final String SIGNING_KEY = "test-only-payment-submission-signing-key-001";
  private static final String METRIC = "divalhr.payment.reconciliation.transitions";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;
  @Autowired private FakePaymentProviderGateway provider;
  @Autowired private PaymentSubmissionWorker submissions;
  @Autowired private PaymentReconciliationWorker worker;
  @Autowired private MeterRegistry registry;

  private PayrollPackageWorld w;
  private PaymentWorld p;
  private PaymentSubmissionWorld s;
  private PaymentReconciliationWorld r;
  private String batch;
  private UUID request;
  private UUID inquiry;
  private String submissionBefore;
  private Instant base;

  @BeforeEach
  void world() throws Exception {
    PaymentReconciliationWorld.drain(clock, provider, submissions, worker);
    w = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
    p = new PaymentWorld(w);
    s = new PaymentSubmissionWorld(p);
    r = new PaymentReconciliationWorld(s);
    UUID pack = StatutoryWorld.verifiedPack(jdbc);
    w.s.activate(pack, w.month);
    CompensationWorld.Person ana = w.linkedEmployee("Mukendi", PayrollPackageWorld.GROSS);
    String run = w.s.cdfRun();
    String calculation = w.s.create(run).get("calculationId").asText();
    String pkg =
        w.approve(new Chain(pack, ana.employee(), run, calculation)).get("packageId").asText();
    p.record(ana.employee(), p.bank(PaymentWorld.ACCOUNT, p.early(), "INITIAL_SETUP"));
    batch = p.prepare(pkg).get("batchId").asText();
    r.deliveryUnknown(batch, provider, submissions);
    provider.reset();
    request = s.requestId(batch);
    submissionBefore = r.submissionSnapshot();
    base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    clock.set(base);
    r.open(batch);
    inquiry = r.inquiryId(batch);
  }

  @AfterEach
  void reset() {
    clock.reset();
    provider.reset();
  }

  private void advance(Duration by) {
    clock.set(clock.instant().plus(by));
  }

  /** The original submission and its attempts are as they were and nothing was ever sent. */
  private void untouched() throws Exception {
    assertThat(r.submissionSnapshot()).isEqualTo(submissionBefore);
    assertThat(s.state(batch)).isEqualTo("DELIVERY_UNKNOWN");
    assertThat(s.requestId(batch)).isEqualTo(request);
    assertThat(provider.calls()).as("payment sends").isEmpty();
  }

  private static String expectedState(PaymentInquiryOutcome outcome) {
    return switch (outcome) {
      case ACCEPTED_FOR_PROCESSING -> "CONFIRMED_ACCEPTED_FOR_PROCESSING";
      case REJECTED_INVALID_REQUEST,
          REJECTED_SIGNATURE,
          REJECTED_UNSUPPORTED,
          REJECTED_DUPLICATE_MISMATCH,
          REJECTED_LIMIT,
          REJECTED_OTHER ->
          "CONFIRMED_REJECTED";
      case NOT_FOUND, PENDING, UNKNOWN, BAD_RESPONSE -> "STILL_UNRESOLVED";
      case TRANSIENT_CONNECT,
          TRANSIENT_TLS,
          TRANSIENT_TIMEOUT,
          TRANSIENT_RATE_LIMITED,
          TRANSIENT_SERVER ->
          "OPEN";
      case RETRIES_EXHAUSTED -> "MANUAL_REVIEW_REQUIRED";
    };
  }

  private static boolean expectedAuthenticated(PaymentInquiryOutcome outcome) {
    return switch (outcome) {
      case ACCEPTED_FOR_PROCESSING,
          REJECTED_INVALID_REQUEST,
          REJECTED_SIGNATURE,
          REJECTED_UNSUPPORTED,
          REJECTED_DUPLICATE_MISMATCH,
          REJECTED_LIMIT,
          REJECTED_OTHER,
          NOT_FOUND,
          PENDING,
          UNKNOWN ->
          true;
      default -> false;
    };
  }

  private double transitions(String state, String outcome) {
    Counter counter =
        registry
            .find(METRIC)
            .tag("provider", "SYNTHETIC-TEST")
            .tag("state", state)
            .tag("outcome", outcome)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  // ------------------------------------------------------------------------------------------
  // Outcomes
  // ------------------------------------------------------------------------------------------

  @ParameterizedTest
  @EnumSource(
      value = PaymentInquiryOutcome.class,
      mode = EnumSource.Mode.EXCLUDE,
      names = "RETRIES_EXHAUSTED")
  void everyProviderOutcomeIsRecordedToItsStateWithItsAuthenticationFlag(
      PaymentInquiryOutcome outcome) throws Exception {
    String state = expectedState(outcome);
    double claimed = transitions("INQUIRY_IN_PROGRESS", "none");
    double recorded = transitions(state, outcome.name());
    provider.thenInquiry(outcome);

    assertThat(worker.runOnce()).isEqualTo(1);

    JsonNode view = r.view(batch);
    assertThat(view.get("state").asText()).isEqualTo(state);
    assertThat(view.get("outcomeCode").asText()).isEqualTo(outcome.name());
    assertThat(view.get("attemptCount").asInt()).isEqualTo(1);
    assertThat(view.get("version").asInt()).isEqualTo(3);
    if ("OPEN".equals(state)) {
      assertThat(Instant.parse(view.get("nextAttemptAt").asText())).isEqualTo(base.plusSeconds(30));
    } else {
      assertThat(view.get("nextAttemptAt").isNull()).isTrue();
    }
    JsonNode items = r.inquiries(batch).get("items");
    assertThat(items).hasSize(3);
    assertThat(items.get(0).get("sequence").asInt()).isEqualTo(3);
    assertThat(items.get(0).get("state").asText()).isEqualTo(state);
    assertThat(items.get(0).get("outcomeCode").asText()).isEqualTo(outcome.name());
    assertThat(items.get(0).get("authenticated").asBoolean())
        .isEqualTo(expectedAuthenticated(outcome));
    assertThat(items.get(1).get("state").asText()).isEqualTo("INQUIRY_IN_PROGRESS");
    assertThat(items.get(1).get("attemptNumber").asInt()).isEqualTo(1);
    assertThat(items.get(2).get("state").asText()).isEqualTo("OPEN");
    assertThat(items.get(2).get("attemptNumber").asInt()).isZero();

    assertThat(provider.inquiryCalls()).hasSize(1);
    assertThat(transitions("INQUIRY_IN_PROGRESS", "none")).isEqualTo(claimed + 1);
    assertThat(transitions(state, outcome.name())).isEqualTo(recorded + 1);
    untouched();

    if (!"OPEN".equals(state)) {
      // A conclusion is final: no later cycle asks again or changes it.
      advance(Duration.ofDays(30));
      provider.thenInquiry(PaymentInquiryOutcome.ACCEPTED_FOR_PROCESSING);
      assertThat(worker.runOnce()).isZero();
      assertThat(provider.inquiryCalls()).hasSize(1);
      JsonNode again = r.view(batch);
      assertThat(again.get("state").asText()).isEqualTo(state);
      assertThat(again.get("version").asInt()).isEqualTo(3);
      untouched();
    }
  }

  @Test
  void theInquiryIsSignedMinimalAndCarriesOnlyTheStableIdentifiers() throws Exception {
    provider.thenInquiry(PaymentInquiryOutcome.PENDING);
    worker.runOnce();
    assertThat(provider.inquiryCalls()).hasSize(1);
    InquiryCall call = provider.inquiryCalls().get(0);
    assertThat(call.requestId()).isEqualTo(request);
    assertThat(call.inquiryId()).isEqualTo(inquiry);
    assertThat(call.keyVersion()).isEqualTo(1);
    assertThat(call.body()).isEqualTo(PaymentInquiryBody.canonical(request, inquiry));
    assertThat(call.text())
        .isEqualTo(
            "{\"inquiryId\":\""
                + inquiry
                + "\",\"requestId\":\""
                + request
                + "\",\"schemaVersion\":1}")
        .doesNotContain(PaymentWorld.ACCOUNT)
        .doesNotContain("amount")
        .doesNotContain("currency");
    assertThat(
            new PaymentSubmissionSigner(SIGNING_KEY)
                .verifyInquiry(request, inquiry, call.body(), call.signature()))
        .isTrue();
    assertThat(inquiry).isNotEqualTo(request);
    untouched();
  }

  @Test
  void aConnectorFailureIsAnInquiryRetryNeverAPayment() throws Exception {
    provider.thenInquiryDo(
        () -> {
          throw new IllegalStateException("simulated connector failure");
        });
    assertThat(worker.runOnce()).isEqualTo(1);
    JsonNode view = r.view(batch);
    assertThat(view.get("state").asText()).isEqualTo("OPEN");
    assertThat(view.get("outcomeCode").asText()).isEqualTo("TRANSIENT_CONNECT");
    untouched();
  }

  // ------------------------------------------------------------------------------------------
  // Retry schedule
  // ------------------------------------------------------------------------------------------

  @Test
  void transientFailuresFollowTheBoundedScheduleWithTheSameIdsThenNeedManualReview()
      throws Exception {
    for (int i = 0; i < 8; i++) {
      provider.thenInquiry(PaymentInquiryOutcome.TRANSIENT_SERVER);
    }
    Duration[] delays = {
      Duration.ofSeconds(30),
      Duration.ofMinutes(2),
      Duration.ofMinutes(10),
      Duration.ofHours(1),
      Duration.ofHours(6)
    };
    Instant at = base;
    for (int attempt = 1; attempt <= 5; attempt++) {
      assertThat(worker.runOnce()).isEqualTo(1);
      JsonNode view = r.view(batch);
      assertThat(view.get("state").asText()).isEqualTo("OPEN");
      assertThat(view.get("outcomeCode").asText()).isEqualTo("TRANSIENT_SERVER");
      assertThat(view.get("attemptCount").asInt()).isEqualTo(attempt);
      Instant next = Instant.parse(view.get("nextAttemptAt").asText());
      assertThat(next).isEqualTo(at.plus(delays[attempt - 1]));
      // One second early nothing is asked.
      clock.set(next.minusSeconds(1));
      assertThat(worker.runOnce()).isZero();
      assertThat(provider.inquiryCalls()).hasSize(attempt);
      at = next;
      clock.set(at);
    }
    assertThat(worker.runOnce()).isEqualTo(1);
    JsonNode view = r.view(batch);
    assertThat(view.get("state").asText()).isEqualTo("MANUAL_REVIEW_REQUIRED");
    assertThat(view.get("outcomeCode").asText()).isEqualTo("RETRIES_EXHAUSTED");
    assertThat(view.get("attemptCount").asInt()).isEqualTo(6);
    assertThat(view.get("nextAttemptAt").isNull()).isTrue();
    JsonNode newest = r.inquiries(batch).get("items").get(0);
    assertThat(newest.get("state").asText()).isEqualTo("MANUAL_REVIEW_REQUIRED");
    assertThat(newest.get("outcomeCode").asText()).isEqualTo("RETRIES_EXHAUSTED");
    assertThat(newest.get("authenticated").asBoolean()).isFalse();

    // No seventh inquiry, ever.
    advance(Duration.ofDays(30));
    assertThat(worker.runOnce()).isZero();
    assertThat(provider.inquiryCalls()).hasSize(6);
    assertThat(r.view(batch).get("state").asText()).isEqualTo("MANUAL_REVIEW_REQUIRED");

    // Every attempt asked the same question about the same request.
    PaymentSubmissionSigner signer = new PaymentSubmissionSigner(SIGNING_KEY);
    for (InquiryCall call : provider.inquiryCalls()) {
      assertThat(call.requestId()).isEqualTo(request);
      assertThat(call.inquiryId()).isEqualTo(inquiry);
      assertThat(call.body()).isEqualTo(provider.inquiryCalls().get(0).body());
      assertThat(signer.verifyInquiry(request, inquiry, call.body(), call.signature())).isTrue();
    }
    assertThat(r.inquiryId(batch)).isEqualTo(inquiry);
    untouched();
  }

  // ------------------------------------------------------------------------------------------
  // Leases
  // ------------------------------------------------------------------------------------------

  @Test
  void aWorkerThatDiesMidInquiryIsRecoveredToOpenAndAsksAgainWithTheSameInquiryId()
      throws Exception {
    provider.thenInquiryDo(
        () -> {
          advance(Duration.ofMinutes(10));
          throw new AssertionError("worker died");
        });
    assertThatThrownBy(() -> worker.runOnce()).isInstanceOf(AssertionError.class);
    assertThat(r.state(batch)).isEqualTo("INQUIRY_IN_PROGRESS");
    assertThat(provider.inquiryCalls()).hasSize(1);

    // The lease has expired: the next cycle recovers it to OPEN on the schedule.
    worker.runOnce();
    JsonNode view = r.view(batch);
    assertThat(view.get("state").asText()).isEqualTo("OPEN");
    assertThat(view.get("outcomeCode").asText()).isEqualTo("TRANSIENT_TIMEOUT");
    assertThat(view.get("attemptCount").asInt()).isEqualTo(1);
    assertThat(Instant.parse(view.get("nextAttemptAt").asText()))
        .isEqualTo(clock.instant().plusSeconds(30));
    assertThat(provider.inquiryCalls()).hasSize(1);

    advance(Duration.ofSeconds(31));
    provider.thenInquiry(PaymentInquiryOutcome.ACCEPTED_FOR_PROCESSING);
    worker.runOnce();
    List<InquiryCall> calls = provider.inquiryCalls();
    assertThat(calls).hasSize(2);
    assertThat(calls.get(1).inquiryId()).isEqualTo(calls.get(0).inquiryId()).isEqualTo(inquiry);
    assertThat(calls.get(1).requestId()).isEqualTo(calls.get(0).requestId()).isEqualTo(request);
    assertThat(calls.get(1).body()).isEqualTo(calls.get(0).body());
    JsonNode done = r.view(batch);
    assertThat(done.get("state").asText()).isEqualTo("CONFIRMED_ACCEPTED_FOR_PROCESSING");
    assertThat(done.get("attemptCount").asInt()).isEqualTo(2);
    untouched();
  }

  @Test
  void aResultFromAWorkerThatLostItsLeaseIsNotAppliedAndNeverOverwritesAConclusion()
      throws Exception {
    provider.thenInquiryDo(
        () -> {
          // While this worker is stuck its lease expires, and another cycle recovers the row,
          // asks again and concludes with an acceptance.
          advance(Duration.ofMinutes(10));
          provider.thenInquiry(PaymentInquiryOutcome.ACCEPTED_FOR_PROCESSING);
          worker.runOnce();
          advance(Duration.ofMinutes(1));
          worker.runOnce();
          // The stuck worker finally answers, late and differently.
          return new InquiryResult(PaymentInquiryOutcome.REJECTED_OTHER);
        });
    worker.runOnce();

    JsonNode view = r.view(batch);
    assertThat(view.get("state").asText()).isEqualTo("CONFIRMED_ACCEPTED_FOR_PROCESSING");
    assertThat(view.get("outcomeCode").asText()).isEqualTo("ACCEPTED_FOR_PROCESSING");
    assertThat(view.get("attemptCount").asInt()).isEqualTo(2);
    assertThat(view.get("version").asInt()).isEqualTo(5);
    JsonNode items = r.inquiries(batch).get("items");
    List<String> states = new ArrayList<>();
    List<String> outcomes = new ArrayList<>();
    items.forEach(
        item -> {
          states.add(item.get("state").asText());
          outcomes.add(item.get("outcomeCode").asText());
        });
    assertThat(states)
        .containsExactly(
            "CONFIRMED_ACCEPTED_FOR_PROCESSING",
            "INQUIRY_IN_PROGRESS",
            "OPEN",
            "INQUIRY_IN_PROGRESS",
            "OPEN");
    assertThat(outcomes).doesNotContain("REJECTED_OTHER");
    assertThat(provider.inquiryCalls()).hasSize(2);

    // An expired-lease recovery never touches a concluded row either.
    advance(Duration.ofDays(30));
    assertThat(worker.runOnce()).isZero();
    assertThat(r.view(batch).get("version").asInt()).isEqualTo(5);
    untouched();
  }

  @Test
  void twoWorkersRacingForOneReconciliationAskTheProviderOnce() throws Exception {
    provider.thenInquiry(PaymentInquiryOutcome.ACCEPTED_FOR_PROCESSING);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<Integer>> futures = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return worker.runOnce();
                }));
      }
      start.countDown();
      int asked = 0;
      for (Future<Integer> future : futures) {
        asked += future.get(60, TimeUnit.SECONDS);
      }
      assertThat(asked).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
    assertThat(provider.inquiryCalls()).hasSize(1);
    assertThat(r.view(batch).get("state").asText()).isEqualTo("CONFIRMED_ACCEPTED_FOR_PROCESSING");
    assertThat(r.inquiries(batch).get("items")).hasSize(3);
    untouched();
  }

  // ------------------------------------------------------------------------------------------
  // Audit, events and metrics
  // ------------------------------------------------------------------------------------------

  @Test
  void everyTransitionWritesAnAuditRecordAnInternalEventAndACounter() throws Exception {
    provider.thenInquiry(PaymentInquiryOutcome.ACCEPTED_FOR_PROCESSING);
    worker.runOnce();

    assertThat(r.evidence()).containsExactly(1, 3, 3, 3);
    assertThat(
            r.c.count(
                "SELECT count(*) FROM platform.outbox_event WHERE tenant_id = ?"
                    + " AND event_type = 'payroll.payment-reconciliation.created.v1'",
                w.tenant))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForList(
                "SELECT envelope::jsonb -> 'data' ->> 'state' FROM platform.outbox_event"
                    + " WHERE tenant_id = ? AND event_type ="
                    + " 'payroll.payment-reconciliation.state-changed.v1'"
                    + " ORDER BY (envelope::jsonb -> 'data' ->> 'version')::int",
                String.class,
                w.tenant))
        .containsExactly("INQUIRY_IN_PROGRESS", "CONFIRMED_ACCEPTED_FOR_PROCESSING");
    assertThat(
            jdbc.queryForList(
                "SELECT DISTINCT actor_subject FROM platform.audit_event WHERE tenant_id = ?"
                    + " AND action = 'payment-reconciliation.inquiry'",
                String.class,
                w.tenant))
        .containsExactly(PaymentReconciliationRecorder.WORKER_ACTOR);
    Set<String> keys =
        new HashSet<>(
            jdbc.queryForList(
                "SELECT DISTINCT k FROM platform.outbox_event e,"
                    + " jsonb_object_keys((e.envelope::jsonb) -> 'data') k WHERE e.tenant_id = ?"
                    + " AND e.event_type LIKE 'payroll.payment-reconciliation.%'",
                String.class, w.tenant));
    assertThat(keys)
        .isSubsetOf(
            "schemaVersion",
            "reconciliationId",
            "submissionId",
            "batchId",
            "state",
            "version",
            "outcomeCode");
    assertThat(registry.find("divalhr.payment.reconciliation.open").gauge()).isNotNull();
    untouched();
  }

  @Test
  void aLaterScriptedAnswerNeverReachesAConcludedReconciliation() throws Exception {
    provider.thenInquiry(PaymentInquiryOutcome.REJECTED_LIMIT);
    worker.runOnce();
    provider.thenInquiry(PaymentInquiryOutcome.ACCEPTED_FOR_PROCESSING);
    advance(Duration.ofDays(2));
    worker.runOnce();
    worker.runOnce();
    JsonNode view = r.view(batch);
    assertThat(view.get("state").asText()).isEqualTo("CONFIRMED_REJECTED");
    assertThat(view.get("outcomeCode").asText()).isEqualTo("REJECTED_LIMIT");
    assertThat(provider.inquiryCalls()).hasSize(1);
    assertThat(r.evidence()).containsExactly(1, 3, 3, 3);
    untouched();
  }
}
