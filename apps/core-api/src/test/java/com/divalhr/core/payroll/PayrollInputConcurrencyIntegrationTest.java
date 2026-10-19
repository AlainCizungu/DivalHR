package com.divalhr.core.payroll;

import static com.divalhr.core.payroll.PayrollInputCalls.JSON;
import static com.divalhr.core.payroll.PayrollInputCalls.generate;
import static com.divalhr.core.payroll.PayrollInputCalls.generating;
import static com.divalhr.core.payroll.PayrollInputCalls.preview;
import static com.divalhr.core.payroll.PayrollInputCalls.scope;
import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.people.schedule.LockedWeeks;
import com.divalhr.core.people.schedule.LockedWeeks.Week;
import com.divalhr.core.platform.access.LockedApprovedTime;
import com.divalhr.core.platform.tenancy.TenantId;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MVP-090A (Issue #141, acceptance criterion 7) against real PostgreSQL: two concurrent generation
 * attempts of the same period by two administrators (different keys) produce one logical run, one
 * audit record and one event; a generation racing an unlock of one of its weeks either generates
 * from the locked evidence or is refused as stale, never a partial run; a preview waits for the
 * tenant's scheduling lock (R142-1), so it never assembles its evidence from mixed states.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
class PayrollInputConcurrencyIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;
  @Autowired private LockedApprovedTime approvedTime;
  @Autowired private PlatformTransactionManager transactions;

  @AfterEach
  void reset() {
    clock.reset();
  }

  private static String key() {
    return Organizations.newKey();
  }

  private int count(String sql, Object... args) {
    Integer n = jdbc.queryForObject(sql, Integer.class, args);
    return n == null ? 0 : n;
  }

  private List<Integer> written(LockedWeeks f) {
    return List.of(
        count("SELECT count(*) FROM payroll.input_run WHERE tenant_id = ?", f.tenant()),
        count("SELECT count(*) FROM payroll.input_source_row WHERE tenant_id = ?", f.tenant()),
        count(
            "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ? AND action ="
                + " 'payroll-input.generate'",
            f.tenant()),
        count(
            "SELECT count(*) FROM platform.outbox_event WHERE envelope ->> 'tenantId' = ? AND"
                + " envelope ->> 'eventType' = 'payroll.payroll-input.generated.v1'",
            f.tenant().toString()));
  }

  private static List<String> parallel(LockedWeeks f, List<MockHttpServletRequestBuilder> calls)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(calls.size());
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
      for (MockHttpServletRequestBuilder call : calls) {
        Callable<MockHttpServletResponse> task =
            () -> {
              start.await();
              return f.call(call);
            };
        futures.add(pool.submit(task));
      }
      start.countDown();
      List<String> codes = new ArrayList<>();
      for (Future<MockHttpServletResponse> future : futures) {
        MockHttpServletResponse response = future.get(60, TimeUnit.SECONDS);
        JsonNode body = JSON.readTree(response.getContentAsString());
        codes.add(
            response.getStatus() >= 300
                ? response.getStatus() + " " + body.get("code").asText()
                : body.has("outcome")
                    ? body.get("outcome").asText()
                    : String.valueOf(response.getStatus()));
      }
      return codes;
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void twoConcurrentGenerationsOfOnePeriodProduceOneLogicalRun() throws Exception {
    LockedWeeks f = new LockedWeeks(mvc, jdbc, lookups, clock);
    LocalDate monday = f.nextMonday();
    f.locked(monday);
    Map<String, Object> period = scope(f.legalEntity(), monday, monday.plusDays(6));
    String first = f.admin();
    String second = f.administrator();
    JsonNode preview = f.expect(preview(first, period), 200);
    List<String> outcomes =
        parallel(
            f,
            List.of(
                generate(first, key(), generating(period, preview)),
                generate(second, key(), generating(period, preview)),
                generate(f.administrator(), key(), generating(period, preview))));
    assertThat(outcomes).containsExactlyInAnyOrder("GENERATED", "EXISTING", "EXISTING");
    assertThat(written(f)).containsExactly(1, 4, 1, 1);
  }

  @Test
  void aGenerationRacingAnUnlockGeneratesFromLockedEvidenceOrIsRefusedWithoutAPartialRun()
      throws Exception {
    LockedWeeks f = new LockedWeeks(mvc, jdbc, lookups, clock);
    LocalDate monday = f.nextMonday();
    Week week = f.locked(monday);
    Map<String, Object> period = scope(f.legalEntity(), monday, monday.plusDays(6));
    JsonNode preview = f.expect(preview(f.admin(), period), 200);
    List<String> outcomes =
        parallel(
            f,
            List.of(
                generate(f.administrator(), key(), generating(period, preview)),
                f.unlocking(week)));
    // Both serialize on the tenant's scheduling lock: either the run was generated from the still
    // locked evidence and the unlock followed, or the unlock came first and the generation is
    // stale. Never a run with an unlocked source.
    assertThat(outcomes.get(1)).isEqualTo("UNLOCKED");
    assertThat(outcomes.get(0)).isIn("GENERATED", "409 PAYROLL_INPUT_PREVIEW_STALE");
    int runs = count("SELECT count(*) FROM payroll.input_run WHERE tenant_id = ?", f.tenant());
    assertThat(runs).isEqualTo(outcomes.get(0).equals("GENERATED") ? 1 : 0);
    assertThat(written(f).get(2)).isEqualTo(runs);
    assertThat(written(f).get(3)).isEqualTo(runs);
    if (runs == 1) {
      // The run binds the LOCKED action that preceded the unlock.
      assertThat(
              count(
                  "SELECT count(*) FROM payroll.input_source_week k JOIN"
                      + " people.timesheet_period_action a ON a.id = k.lock_action_id WHERE"
                      + " k.tenant_id = ? AND a.action = 'LOCKED' AND a.sequence = 1",
                  f.tenant()))
          .isEqualTo(1);
    }
  }

  @Test
  void aPreviewWaitsForTheTenantsSchedulingLockAndThenReadsOneCommittedState() throws Exception {
    LockedWeeks f = new LockedWeeks(mvc, jdbc, lookups, clock);
    LocalDate monday = f.nextMonday();
    Week week = f.locked(monday);
    Map<String, Object> period = scope(f.legalEntity(), monday, monday.plusDays(6));
    String admin = f.admin();
    ExecutorService pool = Executors.newSingleThreadExecutor();
    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    TransactionTemplate holder = new TransactionTemplate(transactions);
    try {
      // Another command of the tenant (a lock, unlock, correction, refresh or approval) holds the
      // scheduling lock in its own transaction.
      Future<?> holding =
          pool.submit(
              () ->
                  holder.executeWithoutResult(
                      status -> {
                        approvedTime.lockForPayrollInputs(new TenantId(f.tenant()));
                        held.countDown();
                        try {
                          release.await(60, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        }
                      }));
      assertThat(held.await(30, TimeUnit.SECONDS)).isTrue();
      ExecutorService previewing = Executors.newSingleThreadExecutor();
      try {
        Future<MockHttpServletResponse> preview =
            previewing.submit(() -> f.call(preview(admin, period)));
        // The preview is blocked on the advisory lock: a backend is waiting for it, and the
        // response does not arrive while the lock is held.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (count("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted")
            == 0) {
          assertThat(System.nanoTime())
              .as("the preview never waited for the lock")
              .isLessThan(deadline);
          assertThat(preview.isDone()).as("the preview did not wait for the lock").isFalse();
          Thread.sleep(50);
        }
        assertThat(preview.isDone()).isFalse();
        release.countDown();
        holding.get(30, TimeUnit.SECONDS);
        MockHttpServletResponse response = preview.get(60, TimeUnit.SECONDS);
        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.getContentAsString());
        assertThat(body.get("ready").asBoolean()).isTrue();
        assertThat(body.get("weeks").get(0).get("generationId").asText())
            .isEqualTo(week.generationId());
      } finally {
        release.countDown();
        previewing.shutdownNow();
      }
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }
}
