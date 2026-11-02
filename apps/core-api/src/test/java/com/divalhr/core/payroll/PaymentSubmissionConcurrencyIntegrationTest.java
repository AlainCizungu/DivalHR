package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.payroll.PayrollPackageWorld.Chain;
import com.divalhr.core.payroll.application.PaymentSubmissionWorker;
import com.divalhr.core.support.FakePaymentProviderGateway;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-095B (Issue #187, ADR 0037) against real PostgreSQL: submissions racing for one batch record
 * exactly one submission with its evidence, audit row and event; the same key racing records one; a
 * submission racing a cancellation leaves exactly one winner; two workers racing for one queued
 * submission send it once. Synthetic data only; the connector is scripted.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@PayrollPackageWorld.HighLimits
@PaymentWorld.HighLimits
@PaymentSubmissionWorld.HighLimits
class PaymentSubmissionConcurrencyIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;
  @Autowired private FakePaymentProviderGateway provider;
  @Autowired private PaymentSubmissionWorker worker;

  private PayrollPackageWorld w;
  private PaymentWorld p;
  private PaymentSubmissionWorld s;
  private String batch;
  private CompensationWorld.Person ana;
  private String pkg;

  @BeforeEach
  void world() throws Exception {
    w = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
    provider.reset();
    for (int i = 0; i < 50 && worker.runOnce() > 0; i++) {
      // drain submissions of other classes
    }
    provider.reset();
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

  private List<String> parallel(List<MockHttpServletRequestBuilder> calls) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(calls.size());
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
      for (MockHttpServletRequestBuilder call : calls) {
        Callable<MockHttpServletResponse> task =
            () -> {
              start.await();
              return w.c.call(call);
            };
        futures.add(pool.submit(task));
      }
      start.countDown();
      List<String> outcomes = new ArrayList<>();
      for (Future<MockHttpServletResponse> future : futures) {
        MockHttpServletResponse response = future.get(60, TimeUnit.SECONDS);
        JsonNode body = CompensationWorld.JSON.readTree(response.getContentAsString());
        outcomes.add(
            response.getStatus() >= 300
                ? response.getStatus() + " " + body.get("code").asText()
                : String.valueOf(response.getStatus()));
      }
      return outcomes;
    } finally {
      pool.shutdownNow();
    }
  }

  private static long created(List<String> outcomes) {
    return outcomes.stream().filter("201"::equals).count();
  }

  @Test
  void threeSubmissionsOfTheSamePreviewRecordOneAndTheOthersAreStableConflicts() throws Exception {
    Map<String, Object> body = PaymentSubmissionWorld.committing(s.preview(batch));
    List<String> outcomes =
        parallel(
            List.of(
                s.commitRequest(w.admin, batch, Organizations.newKey(), body),
                s.commitRequest(w.admin, batch, Organizations.newKey(), body),
                s.commitRequest(w.admin, batch, Organizations.newKey(), body)));

    assertThat(created(outcomes)).as(outcomes.toString()).isEqualTo(1);
    assertThat(outcomes)
        .filteredOn(outcome -> !outcome.equals("201"))
        .hasSize(2)
        .allSatisfy(
            outcome ->
                assertThat(outcome)
                    .isIn(
                        "409 PAYMENT_SUBMISSION_EXISTS",
                        "409 PAYMENT_SUBMISSION_VERSION_CONFLICT",
                        "409 PAYMENT_SUBMISSION_PREVIEW_STALE"));
    assertThat(s.evidence()).containsExactly(1, 1, 1, 1);
  }

  @Test
  void theSameKeyAndPayloadRacingRecordsExactlyOneSubmission() throws Exception {
    Map<String, Object> body = PaymentSubmissionWorld.committing(s.preview(batch));
    String key = Organizations.newKey();
    List<String> outcomes =
        parallel(
            List.of(
                s.commitRequest(w.admin, batch, key, body),
                s.commitRequest(w.admin, batch, key, body)));
    assertThat(outcomes).contains("201").noneMatch(outcome -> outcome.startsWith("5"));
    assertThat(s.evidence()).containsExactly(1, 1, 1, 1);
  }

  @Test
  void aSubmissionRacingACancellationLeavesExactlyOneWinner() throws Exception {
    Map<String, Object> submit = PaymentSubmissionWorld.committing(s.preview(batch));
    JsonNode cancelPreview = w.c.expect(p.cancelPreview(w.admin, batch, "PREPARED_IN_ERROR"), 200);
    Map<String, Object> cancel = PaymentWorld.cancelling(cancelPreview, "PREPARED_IN_ERROR");

    List<String> outcomes =
        parallel(
            List.of(
                s.commitRequest(w.admin, batch, Organizations.newKey(), submit),
                p.cancelRequest(w.admin, batch, Organizations.newKey(), cancel)));

    assertThat(created(outcomes)).as(outcomes.toString()).isEqualTo(1);
    assertThat(outcomes).noneMatch(outcome -> outcome.startsWith("5"));
    int submissions = s.evidence().get(0);
    int cancellations = p.evidence().get(3);
    assertThat(submissions + cancellations).as("exactly one of the two").isEqualTo(1);
  }

  @Test
  void twoWorkersRacingForOneQueuedSubmissionSendItOnce() throws Exception {
    s.submit(batch);
    UUID request = s.requestId(batch);
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
      for (Future<Integer> future : futures) {
        future.get(60, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }
    assertThat(provider.calls().stream().filter(call -> call.requestId().equals(request)))
        .hasSize(1);
    assertThat(s.state(batch)).isEqualTo("ACCEPTED_FOR_PROCESSING");
    assertThat(s.evidence().get(1)).isEqualTo(3);
  }
}
