package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.payroll.PayrollPackageWorld.Chain;
import com.divalhr.core.payroll.application.PaymentReconciliationWorker;
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
 * MVP-095C (Issue #190, ADR 0038) against real PostgreSQL: investigations racing for one submission
 * record exactly one reconciliation with its opening evidence, audit row and event, whether they
 * share an idempotency key or not, and none of them ever contacts the provider. Synthetic data
 * only; the connector is scripted.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@PayrollPackageWorld.HighLimits
@PaymentWorld.HighLimits
@PaymentSubmissionWorld.HighLimits
@PaymentReconciliationWorld.HighLimits
class PaymentReconciliationConcurrencyIntegrationTest {

  private static final int RACERS = 4;

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;
  @Autowired private FakePaymentProviderGateway provider;
  @Autowired private PaymentSubmissionWorker submissions;
  @Autowired private PaymentReconciliationWorker worker;

  private PayrollPackageWorld w;
  private PaymentWorld p;
  private PaymentSubmissionWorld s;
  private PaymentReconciliationWorld r;
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
    CompensationWorld.Person ana = w.linkedEmployee("Mukendi", PayrollPackageWorld.GROSS);
    String run = w.s.cdfRun();
    String calculation = w.s.create(run).get("calculationId").asText();
    String pkg =
        w.approve(new Chain(pack, ana.employee(), run, calculation)).get("packageId").asText();
    p.record(ana.employee(), p.bank(PaymentWorld.ACCOUNT, p.early(), "INITIAL_SETUP"));
    batch = p.prepare(pkg).get("batchId").asText();
    r.deliveryUnknown(batch, provider, submissions);
    provider.reset();
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
  void identicalCommandsWithDistinctKeysRecordOneAndTheOthersAreStableConflicts() throws Exception {
    Map<String, Object> body = PaymentReconciliationWorld.committing(r.preview(batch));
    List<MockHttpServletRequestBuilder> calls = new ArrayList<>();
    for (int i = 0; i < RACERS; i++) {
      calls.add(r.commitRequest(w.admin, batch, Organizations.newKey(), body));
    }
    List<String> outcomes = parallel(calls);

    assertThat(created(outcomes)).as(outcomes.toString()).isEqualTo(1);
    assertThat(outcomes)
        .filteredOn(outcome -> !outcome.equals("201"))
        .hasSize(RACERS - 1)
        .allSatisfy(outcome -> assertThat(outcome).isEqualTo("409 PAYMENT_RECONCILIATION_EXISTS"));
    assertThat(r.evidence()).containsExactly(1, 1, 1, 1);
    assertThat(provider.inquiryCalls()).isEmpty();
    assertThat(provider.calls()).isEmpty();
  }

  @Test
  void theSameKeyAndPayloadRacingRecordsExactlyOneReconciliation() throws Exception {
    Map<String, Object> body = PaymentReconciliationWorld.committing(r.preview(batch));
    String key = Organizations.newKey();
    List<MockHttpServletRequestBuilder> calls = new ArrayList<>();
    for (int i = 0; i < RACERS; i++) {
      calls.add(r.commitRequest(w.admin, batch, key, body));
    }
    List<String> outcomes = parallel(calls);
    assertThat(outcomes).contains("201").noneMatch(outcome -> outcome.startsWith("5"));
    assertThat(r.evidence()).containsExactly(1, 1, 1, 1);
    assertThat(provider.inquiryCalls()).isEmpty();
    assertThat(provider.calls()).isEmpty();
  }

  @Test
  void aMixOfSharedAndDistinctKeysStillRecordsExactlyOneReconciliation() throws Exception {
    Map<String, Object> body = PaymentReconciliationWorld.committing(r.preview(batch));
    String shared = Organizations.newKey();
    List<String> outcomes =
        parallel(
            List.of(
                r.commitRequest(w.admin, batch, shared, body),
                r.commitRequest(w.admin, batch, shared, body),
                r.commitRequest(w.admin, batch, Organizations.newKey(), body),
                r.commitRequest(w.admin, batch, Organizations.newKey(), body)));
    // The two sharing a key may both answer 201 (the second is a replay of the first).
    assertThat(created(outcomes)).as(outcomes.toString()).isBetween(1L, 2L);
    assertThat(outcomes).noneMatch(outcome -> outcome.startsWith("5"));
    assertThat(r.evidence()).containsExactly(1, 1, 1, 1);
    assertThat(s.state(batch)).isEqualTo("DELIVERY_UNKNOWN");
  }
}
