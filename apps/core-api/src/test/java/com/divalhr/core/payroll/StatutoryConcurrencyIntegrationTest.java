package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
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
 * MVP-094B (Issue #179, ADR 0034 section 7) against real PostgreSQL: calculations, profiles and
 * activations that race, each with its own Idempotency-Key and the same preview, record exactly one
 * row and one stable conflict for every other caller; a calculation racing a profile change ends in
 * one of the two legal orders and never in a calculation that disagrees with the profile digest it
 * stored. Synthetic data only.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@TestPropertySource(
    properties = {
      "divalhr.request-limits.subject.statutory-read=600",
      "divalhr.request-limits.subject.statutory-calculation-preview=600",
      "divalhr.request-limits.subject.statutory-calculation-create=600",
      "divalhr.request-limits.subject.statutory-profile-preview=600",
      "divalhr.request-limits.subject.statutory-profile-write=600",
      "divalhr.request-limits.subject.statutory-activation-write=600",
      "divalhr.request-limits.subject.payroll-run-preview=600",
      "divalhr.request-limits.subject.payroll-run-create=600",
      "divalhr.request-limits.subject.payroll-run-read=600",
      "divalhr.request-limits.subject.payroll-policy-preview=600",
      "divalhr.request-limits.subject.payroll-policy-write=600",
      "divalhr.request-limits.subject.payroll-policy-read=600"
    })
class StatutoryConcurrencyIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;

  private StatutoryWorld w;
  private UUID pack;
  private UUID employee;
  private String run;

  @BeforeEach
  void world() throws Exception {
    w = new StatutoryWorld(mvc, jdbc, lookups, clock);
    pack = StatutoryWorld.verifiedPack(jdbc);
    w.activate(pack, w.month);
    employee = w.paidInCdf("Mukendi", StatutoryWorld.GROSS);
    w.standard(employee);
    run = w.cdfRun();
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
  void threeCreationsOfTheSamePreviewRecordOneCalculationAndTheOthersAreVersionConflicts()
      throws Exception {
    Map<String, Object> body = StatutoryWorld.calculationCommitting(w.preview(run));
    List<Integer> before = w.evidence();

    List<String> outcomes =
        parallel(
            List.of(
                w.createRequest(run, Organizations.newKey(), body),
                w.createRequest(run, Organizations.newKey(), body),
                w.createRequest(run, Organizations.newKey(), body)));

    assertThat(outcomes)
        .containsExactlyInAnyOrder(
            "201", "409 STATUTORY_VERSION_CONFLICT", "409 STATUTORY_VERSION_CONFLICT");
    List<Integer> after = w.evidence();
    assertThat(after.get(2)).isEqualTo(before.get(2) + 1);
    assertThat(after.get(3)).isEqualTo(before.get(3) + 1);
    assertThat(after.get(4)).isEqualTo(before.get(4) + 1);
    assertThat(after.get(5)).isEqualTo(before.get(5) + 1);
  }

  @Test
  void theSameKeyAndPayloadRacingRecordsExactlyOneCalculation() throws Exception {
    Map<String, Object> body = StatutoryWorld.calculationCommitting(w.preview(run));
    String key = Organizations.newKey();
    int calculations = w.evidence().get(2);

    List<String> outcomes =
        parallel(List.of(w.createRequest(run, key, body), w.createRequest(run, key, body)));

    assertThat(outcomes).contains("201").noneMatch(outcome -> outcome.startsWith("5"));
    assertThat(w.evidence().get(2)).isEqualTo(calculations + 1);
  }

  @Test
  void racingRecalculationsLeaveOneDraftAndAConsistentChain() throws Exception {
    w.create(run);
    w.profile(employee, w.month.atDay(1).minusDays(10), "STANDARD_RESIDENT", true, false);
    Map<String, Object> body = StatutoryWorld.calculationCommitting(w.preview(run));

    List<String> outcomes =
        parallel(
            List.of(
                w.createRequest(run, Organizations.newKey(), body),
                w.createRequest(run, Organizations.newKey(), body)));

    assertThat(outcomes).containsExactlyInAnyOrder("201", "409 STATUTORY_VERSION_CONFLICT");
    assertThat(
            w.c.count(
                "SELECT count(*) FROM payroll.statutory_calculation WHERE tenant_id = ?"
                    + " AND status = 'DRAFT'",
                w.tenant))
        .isEqualTo(1);
    assertThat(
            w.c.count(
                "SELECT count(*) FROM payroll.statutory_calculation WHERE tenant_id = ?", w.tenant))
        .isEqualTo(2);
  }

  @Test
  void racingProfileCommitsOfOneEmployeeRecordOneEntry() throws Exception {
    LocalDate from = w.month.atDay(1).minusDays(10);
    List<MockHttpServletRequestBuilder> calls = new ArrayList<>();
    for (int day = 0; day < 3; day++) {
      Map<String, Object> command =
          StatutoryWorld.profileCommand(
              from.plusDays(day), "STANDARD_RESIDENT", true, false, "EMPLOYER_DECLARATION");
      JsonNode preview = w.c.expect(w.profilePreviewRequest(employee, command), 200);
      calls.add(
          w.profileCommitRequest(
              employee,
              Organizations.newKey(),
              StatutoryWorld.profileCommitting(command, preview)));
    }
    int profiles = w.evidence().get(1);

    List<String> outcomes = parallel(calls);

    assertThat(outcomes)
        .containsExactlyInAnyOrder(
            "201", "409 STATUTORY_VERSION_CONFLICT", "409 STATUTORY_VERSION_CONFLICT");
    assertThat(w.evidence().get(1)).isEqualTo(profiles + 1);
  }

  @Test
  void racingActivationsRecordOneSequence() throws Exception {
    UUID second = StatutoryWorld.verifiedPack(jdbc);
    List<MockHttpServletRequestBuilder> calls = new ArrayList<>();
    for (int i = 1; i <= 3; i++) {
      calls.add(
          w.activateRequest(
              Organizations.newKey(),
              StatutoryWorld.activation(i == 2 ? second : pack, w.month.plusMonths(i), 1)));
    }
    int activations = w.evidence().get(0);

    List<String> outcomes = parallel(calls);

    assertThat(outcomes)
        .containsExactlyInAnyOrder(
            "201", "409 STATUTORY_VERSION_CONFLICT", "409 STATUTORY_VERSION_CONFLICT");
    assertThat(w.evidence().get(0)).isEqualTo(activations + 1);
  }

  @Test
  void aCalculationRacingAProfileChangeEndsInOneOfTheTwoLegalOrders() throws Exception {
    Map<String, Object> calculation = StatutoryWorld.calculationCommitting(w.preview(run));
    Map<String, Object> command =
        StatutoryWorld.profileCommand(
            w.month.atDay(1).minusDays(10),
            "STANDARD_RESIDENT",
            true,
            false,
            "EMPLOYER_DECLARATION");
    JsonNode profilePreview = w.c.expect(w.profilePreviewRequest(employee, command), 200);

    List<String> outcomes =
        parallel(
            List.of(
                w.createRequest(run, Organizations.newKey(), calculation),
                w.profileCommitRequest(
                    employee,
                    Organizations.newKey(),
                    StatutoryWorld.profileCommitting(command, profilePreview))));

    assertThat(outcomes.get(1)).isEqualTo("201");
    assertThat(outcomes.get(0)).isIn("201", "409 STATUTORY_PREVIEW_STALE");
    int calculations = w.evidence().get(2);
    assertThat(calculations).isEqualTo(outcomes.get(0).equals("201") ? 1 : 0);
    if (calculations == 1) {
      // The calculation froze exactly the standard profile that was current when it was created.
      String deductions =
          jdbc.queryForObject(
              "SELECT employee_deductions_total_minor::text FROM payroll.statutory_calculation"
                  + " WHERE tenant_id = ?",
              String.class,
              w.tenant);
      assertThat(deductions).isEqualTo("12960");
    }
  }
}
