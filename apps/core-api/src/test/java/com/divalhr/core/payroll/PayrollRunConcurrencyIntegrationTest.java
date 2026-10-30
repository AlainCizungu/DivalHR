package com.divalhr.core.payroll;

import static com.divalhr.core.payroll.PayrollRunWorld.policyCommand;
import static com.divalhr.core.payroll.PayrollRunWorld.policyCommitting;
import static com.divalhr.core.payroll.PayrollRunWorld.runCommand;
import static com.divalhr.core.payroll.PayrollRunWorld.runCommitting;
import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-094A (Issue #177, D94A-11) against real PostgreSQL: runs and policies that race on one legal
 * entity, each with its own Idempotency-Key and the same preview, record exactly one version and
 * one stable conflict for every other caller; a run racing a compensation correction ends in one of
 * the two legal orders and never in a half-written run. Synthetic data only.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@TestPropertySource(
    properties = {
      "divalhr.request-limits.subject.payroll-run-preview=600",
      "divalhr.request-limits.subject.payroll-run-create=600",
      "divalhr.request-limits.subject.payroll-run-read=600",
      "divalhr.request-limits.subject.payroll-policy-preview=600",
      "divalhr.request-limits.subject.payroll-policy-write=600",
      "divalhr.request-limits.subject.compensation-read=600",
      "divalhr.request-limits.subject.compensation-preview=600",
      "divalhr.request-limits.subject.compensation-write=600"
    })
class PayrollRunConcurrencyIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;

  private PayrollRunWorld w;

  @BeforeEach
  void world() throws Exception {
    w = new PayrollRunWorld(mvc, jdbc, lookups, clock);
    w.policy("CALENDAR_DAYS");
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

  @Test
  void threeCreationsOfTheSamePreviewRecordOneRunAndTheOthersAreVersionConflicts()
      throws Exception {
    w.paid("Mukendi", w.month.atDay(1).minusDays(5), "USD", "1250.00");
    Map<String, Object> command = runCommand(w.month, "USD");
    Map<String, Object> body = runCommitting(command, w.c.expect(w.runPreviewRequest(command), 200));
    List<Integer> before = w.evidence();

    List<String> outcomes =
        parallel(
            List.of(
                w.runCommitRequest(Organizations.newKey(), body),
                w.runCommitRequest(Organizations.newKey(), body),
                w.runCommitRequest(Organizations.newKey(), body)));

    assertThat(outcomes)
        .containsExactlyInAnyOrder(
            "201", "409 PAYROLL_RUN_VERSION_CONFLICT", "409 PAYROLL_RUN_VERSION_CONFLICT");
    List<Integer> after = w.evidence();
    assertThat(after.get(0)).isEqualTo(before.get(0) + 1);
    assertThat(after.get(1)).isEqualTo(before.get(1) + 1);
    assertThat(after.get(3)).isEqualTo(before.get(3) + 1);
    assertThat(after.get(4)).isEqualTo(before.get(4) + 1);
  }

  @Test
  void theSameKeyAndPayloadRacingRecordsExactlyOneRun() throws Exception {
    w.paid("Mukendi", w.month.atDay(1).minusDays(5), "USD", "1250.00");
    Map<String, Object> command = runCommand(w.month, "USD");
    Map<String, Object> body = runCommitting(command, w.c.expect(w.runPreviewRequest(command), 200));
    String key = Organizations.newKey();
    int runs = w.evidence().get(0);

    List<String> outcomes =
        parallel(List.of(w.runCommitRequest(key, body), w.runCommitRequest(key, body)));

    assertThat(outcomes).contains("201").noneMatch(outcome -> outcome.startsWith("5"));
    assertThat(w.evidence().get(0)).isEqualTo(runs + 1);
  }

  @Test
  void racingRecalculationsLeaveOneDraftAndAConsistentChain() throws Exception {
    w.paid("Mukendi", w.month.atDay(1).minusDays(5), "USD", "1250.00");
    w.createRun("USD");
    w.paid("Kalala", w.month.atDay(1).minusDays(5), "USD", "900.00");
    Map<String, Object> command = runCommand(w.month, "USD");
    Map<String, Object> body = runCommitting(command, w.c.expect(w.runPreviewRequest(command), 200));

    List<String> outcomes =
        parallel(
            List.of(
                w.runCommitRequest(Organizations.newKey(), body),
                w.runCommitRequest(Organizations.newKey(), body)));

    assertThat(outcomes)
        .containsExactlyInAnyOrder("201", "409 PAYROLL_RUN_VERSION_CONFLICT");
    assertThat(
            w.c.count(
                "SELECT count(*) FROM payroll.pay_run WHERE tenant_id = ? AND status = 'DRAFT'",
                w.tenant))
        .isEqualTo(1);
    assertThat(w.c.count("SELECT count(*) FROM payroll.pay_run WHERE tenant_id = ?", w.tenant))
        .isEqualTo(2);
  }

  @Test
  void racingPolicyCommandsRecordOneVersion() throws Exception {
    Map<String, Object> a = policyCommand(w.today.minusMonths(1), "DAY", 5, "NONE");
    Map<String, Object> b = policyCommand(w.today.minusDays(10), "DAY", 6, "NONE");
    Map<String, Object> c = policyCommand(w.today.minusDays(5), "DAY", 7, "NONE");
    List<MockHttpServletRequestBuilder> calls = new ArrayList<>();
    for (Map<String, Object> command : List.of(a, b, c)) {
      JsonNode preview = w.c.expect(w.policyPreviewRequest(command), 200);
      calls.add(w.policyCommitRequest(Organizations.newKey(), policyCommitting(command, preview)));
    }
    int policies = w.evidence().get(2);

    List<String> outcomes = parallel(calls);

    assertThat(outcomes)
        .containsExactlyInAnyOrder("201", "409 PAYROLL_POLICY_CONFLICT", "409 PAYROLL_POLICY_CONFLICT");
    assertThat(w.evidence().get(2)).isEqualTo(policies + 1);
  }

  @Test
  void aRunRacingACompensationCorrectionEndsInOneOfTheTwoLegalOrders() throws Exception {
    UUID ana = w.hire("Mukendi", w.month.atDay(1).minusDays(5));
    JsonNode established = w.compensate(ana, w.month.atDay(1).minusDays(5), "USD", "1250.00");
    Map<String, Object> command = runCommand(w.month, "USD");
    Map<String, Object> run = runCommitting(command, w.c.expect(w.runPreviewRequest(command), 200));

    Map<String, Object> correction =
        CompensationWorld.correction(
            w.month.atDay(1).minusDays(5),
            "USD",
            "1300.00",
            UUID.fromString(established.get("changeId").asText()));
    JsonNode correctionPreview = w.c.preview(ana, correction);

    List<String> outcomes =
        parallel(
            List.of(
                w.runCommitRequest(Organizations.newKey(), run),
                CompensationWorld.commitRequest(
                    w.admin,
                    ana,
                    Organizations.newKey(),
                    CompensationWorld.committing(correction, correctionPreview, true))));

    assertThat(outcomes.get(1)).isEqualTo("201");
    assertThat(outcomes.get(0)).isIn("201", "409 PAYROLL_RUN_PREVIEW_STALE");
    int runs = w.c.count("SELECT count(*) FROM payroll.pay_run WHERE tenant_id = ?", w.tenant);
    assertThat(runs).isEqualTo(outcomes.get(0).equals("201") ? 1 : 0);
    if (runs == 1) {
      // The run froze exactly the compensation that was current when it was created.
      String gross =
          jdbc.queryForObject(
              "SELECT total_gross_minor::text FROM payroll.pay_run WHERE tenant_id = ?",
              String.class,
              w.tenant);
      assertThat(gross).isIn("125000", "130000");
    }
  }
}
