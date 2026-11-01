package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.payroll.PayrollPackageWorld.Chain;
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
 * MVP-095A (Issue #183, ADR 0036) against real PostgreSQL: batches that race for one package each
 * with its own Idempotency-Key and the same preview record exactly one batch with its lines, audit
 * row and event and a stable conflict for every other caller; the same key racing records one batch;
 * destination commits racing one expected version record one version; cancellations race to one.
 * Synthetic data only.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@PayrollPackageWorld.HighLimits
@PaymentWorld.HighLimits
class PaymentConcurrencyIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;

  private PayrollPackageWorld w;
  private PaymentWorld p;
  private CompensationWorld.Person ana;
  private String pkg;

  @BeforeEach
  void world() throws Exception {
    w = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
    p = new PaymentWorld(w);
    UUID pack = StatutoryWorld.verifiedPack(jdbc);
    w.s.activate(pack, w.month);
    ana = w.linkedEmployee("Mukendi", PayrollPackageWorld.GROSS);
    String run = w.s.cdfRun();
    String calculation = w.s.create(run).get("calculationId").asText();
    pkg = w.approve(new Chain(pack, ana.employee(), run, calculation)).get("packageId").asText();
  }

  @AfterEach
  void reset() {
    clock.reset();
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
  void threePreparationsOfTheSamePreviewRecordOneBatchAndTheOthersAreStableConflicts()
      throws Exception {
    p.record(ana.employee(), p.bank(PaymentWorld.ACCOUNT, p.early(), "INITIAL_SETUP"));
    Map<String, Object> body = PaymentWorld.preparing(p.previewBatch(pkg));
    List<Integer> before = p.evidence();

    List<String> outcomes =
        parallel(
            List.of(
                p.prepareRequest(w.admin, pkg, Organizations.newKey(), body),
                p.prepareRequest(w.admin, pkg, Organizations.newKey(), body),
                p.prepareRequest(w.admin, pkg, Organizations.newKey(), body)));

    assertThat(created(outcomes)).as(outcomes.toString()).isEqualTo(1);
    assertThat(outcomes)
        .filteredOn(outcome -> !outcome.equals("201"))
        .hasSize(2)
        .allSatisfy(
            outcome ->
                assertThat(outcome)
                    .isIn(
                        "409 PAYMENT_BATCH_ALREADY_PREPARED",
                        "409 PAYMENT_BATCH_VERSION_CONFLICT",
                        "409 PAYMENT_BATCH_PREVIEW_STALE"));
    List<Integer> after = p.evidence();
    // One batch, its single line, one audit row and one event; nothing else changes.
    assertThat(after.get(1)).isEqualTo(before.get(1) + 1);
    assertThat(after.get(2)).isEqualTo(before.get(2) + 1);
    assertThat(after.get(3)).isEqualTo(before.get(3));
    assertThat(after.get(4)).isEqualTo(before.get(4) + 1);
    assertThat(after.get(5)).isEqualTo(before.get(5) + 1);
  }

  @Test
  void theSameKeyAndPayloadRacingRecordsExactlyOneBatch() throws Exception {
    p.record(ana.employee(), p.bank(PaymentWorld.ACCOUNT, p.early(), "INITIAL_SETUP"));
    Map<String, Object> body = PaymentWorld.preparing(p.previewBatch(pkg));
    String key = Organizations.newKey();
    int batches = p.evidence().get(1);

    List<String> outcomes =
        parallel(
            List.of(
                p.prepareRequest(w.admin, pkg, key, body),
                p.prepareRequest(w.admin, pkg, key, body)));

    assertThat(outcomes).contains("201").noneMatch(outcome -> outcome.startsWith("5"));
    assertThat(p.evidence().get(1)).isEqualTo(batches + 1);
  }

  @Test
  void destinationCommitsRacingOneExpectedVersionRecordOneVersion() throws Exception {
    Map<String, Object> first = p.bank(PaymentWorld.ACCOUNT, p.early(), "INITIAL_SETUP");
    Map<String, Object> second = p.mobile(PaymentWorld.PHONE, p.early(), "INITIAL_SETUP");
    JsonNode previewOne = p.previewDestination(ana.employee(), first);
    JsonNode previewTwo = p.previewDestination(ana.employee(), second);
    int rows = p.evidence().get(0);

    List<String> outcomes =
        parallel(
            List.of(
                p.destinationCommit(
                    w.admin,
                    ana.employee(),
                    Organizations.newKey(),
                    PaymentWorld.committing(first, previewOne)),
                p.destinationCommit(
                    w.admin,
                    ana.employee(),
                    Organizations.newKey(),
                    PaymentWorld.committing(second, previewTwo))));

    assertThat(created(outcomes)).as(outcomes.toString()).isEqualTo(1);
    assertThat(outcomes)
        .filteredOn(outcome -> !outcome.equals("201"))
        .allSatisfy(
            outcome ->
                assertThat(outcome)
                    .isIn(
                        "409 PAYMENT_DESTINATION_VERSION_CONFLICT",
                        "409 PAYMENT_DESTINATION_PREVIEW_STALE"));
    assertThat(p.evidence().get(0)).isEqualTo(rows + 1);
  }

  @Test
  void twoCancellationsOfTheSamePreviewRecordOneCancellation() throws Exception {
    p.record(ana.employee(), p.bank(PaymentWorld.ACCOUNT, p.early(), "INITIAL_SETUP"));
    String batch = p.prepare(pkg).get("batchId").asText();
    JsonNode preview = w.c.expect(p.cancelPreview(w.admin, batch, "PREPARED_IN_ERROR"), 200);
    Map<String, Object> body = PaymentWorld.cancelling(preview, "PREPARED_IN_ERROR");
    int cancels = p.evidence().get(3);

    List<String> outcomes =
        parallel(
            List.of(
                p.cancelRequest(w.admin, batch, Organizations.newKey(), body),
                p.cancelRequest(w.admin, batch, Organizations.newKey(), body)));

    assertThat(created(outcomes)).as(outcomes.toString()).isEqualTo(1);
    assertThat(outcomes)
        .filteredOn(outcome -> !outcome.equals("201"))
        .allSatisfy(
            outcome ->
                assertThat(outcome)
                    .isIn(
                        "409 PAYMENT_BATCH_CANCELLED",
                        "409 PAYMENT_BATCH_VERSION_CONFLICT",
                        "409 PAYMENT_BATCH_PREVIEW_STALE"));
    assertThat(p.evidence().get(3)).isEqualTo(cancels + 1);
  }
}
