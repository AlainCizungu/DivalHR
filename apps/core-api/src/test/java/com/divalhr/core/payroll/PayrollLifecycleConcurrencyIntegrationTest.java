package com.divalhr.core.payroll;

import static com.divalhr.core.payroll.PayrollInputCalls.JSON;
import static com.divalhr.core.payroll.PayrollInputCalls.generate;
import static com.divalhr.core.payroll.PayrollInputCalls.generating;
import static com.divalhr.core.payroll.PayrollInputCalls.preview;
import static com.divalhr.core.payroll.PayrollInputCalls.scope;
import static com.divalhr.core.payroll.PayrollLifecycleCalls.createExport;
import static com.divalhr.core.payroll.PayrollLifecycleCalls.exporting;
import static com.divalhr.core.payroll.PayrollLifecycleCalls.lock;
import static com.divalhr.core.payroll.PayrollLifecycleCalls.lockPreview;
import static com.divalhr.core.payroll.PayrollLifecycleCalls.locking;
import static com.divalhr.core.payroll.PayrollLifecycleCalls.supersede;
import static com.divalhr.core.payroll.PayrollLifecycleCalls.supersedePreview;
import static com.divalhr.core.payroll.PayrollLifecycleCalls.superseding;
import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.people.schedule.LockedWeeks;
import com.divalhr.core.people.schedule.LockedWeeks.Week;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
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

/**
 * MVP-092 (Issue #149, acceptance criterion 4) against real PostgreSQL: concurrent commands on the
 * same run produce exactly one valid transition with no partial evidence. Two locks yield one lock;
 * two supersessions yield one next version and one stable conflict; concurrent exports of a locked
 * run yield one export that every caller then receives.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
class PayrollLifecycleConcurrencyIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;

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
      List<String> outcomes = new ArrayList<>();
      for (Future<MockHttpServletResponse> future : futures) {
        MockHttpServletResponse response = future.get(60, TimeUnit.SECONDS);
        JsonNode body = JSON.readTree(response.getContentAsString());
        outcomes.add(
            response.getStatus() >= 300
                ? response.getStatus() + " " + body.get("code").asText()
                : body.get("outcome").asText());
      }
      return outcomes;
    } finally {
      pool.shutdownNow();
    }
  }

  private String generated(LockedWeeks f, Week week) throws Exception {
    Map<String, Object> period = scope(f.legalEntity(), week.monday(), week.sunday());
    JsonNode preview = f.expect(preview(f.administrator(), period), 200);
    return f.expect(generate(f.administrator(), key(), generating(period, preview)), 200)
        .get("run")
        .get("runId")
        .asText();
  }

  @Test
  void twoConcurrentLocksYieldOneLockAndOneExportIsSharedByEveryCaller() throws Exception {
    LockedWeeks f = new LockedWeeks(mvc, jdbc, lookups, clock);
    Week week = f.locked(f.nextMonday());
    String run = generated(f, week);
    JsonNode review = f.expect(lockPreview(f.administrator(), run), 200);
    String digest = review.get("previewDigest").asText();
    List<String> locks =
        parallel(
            f,
            List.of(
                lock(f.administrator(), key(), run, locking(digest)),
                lock(f.administrator(), key(), run, locking(digest)),
                lock(f.administrator(), key(), run, locking(digest))));
    assertThat(locks)
        .containsExactlyInAnyOrder(
            "LOCKED", "409 PAYROLL_INPUT_RUN_LOCKED", "409 PAYROLL_INPUT_RUN_LOCKED");
    assertThat(count("SELECT count(*) FROM payroll.input_run_lock WHERE tenant_id = ?", f.tenant()))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ? AND action ="
                    + " 'payroll-input.lock'",
                f.tenant()))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM platform.outbox_event WHERE envelope ->> 'tenantId' = ? AND"
                    + " envelope ->> 'eventType' = 'payroll.payroll-input.locked.v1'",
                f.tenant().toString()))
        .isEqualTo(1);

    List<String> exports =
        parallel(
            f,
            List.of(
                createExport(f.administrator(), key(), exporting(run)),
                createExport(f.administrator(), key(), exporting(run)),
                createExport(f.administrator(), key(), exporting(run))));
    assertThat(exports).containsExactlyInAnyOrder("CREATED", "EXISTING", "EXISTING");
    assertThat(count("SELECT count(*) FROM payroll.input_export WHERE tenant_id = ?", f.tenant()))
        .isEqualTo(1);
    assertThat(
            count("SELECT count(*) FROM payroll.input_export_row WHERE tenant_id = ?", f.tenant()))
        .isEqualTo(
            count("SELECT count(*) FROM payroll.input_source_row WHERE tenant_id = ?", f.tenant()));
    assertThat(
            count(
                "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ? AND action ="
                    + " 'payroll-input-export.create'",
                f.tenant()))
        .isEqualTo(1);
  }

  @Test
  void twoConcurrentSupersessionsYieldOneNextVersionAndAStableConflict() throws Exception {
    LockedWeeks f = new LockedWeeks(mvc, jdbc, lookups, clock);
    Week week = f.locked(f.nextMonday());
    String run = generated(f, week);
    f.unlock(week);
    assertThat(f.correctAna(week, 20).getStatus()).isEqualTo(201);
    f.refresh(week);
    f.approveCycle(week, f.ana());
    f.lock(week);
    JsonNode review = f.expect(supersedePreview(f.administrator(), run), 200);
    String digest = review.get("previewDigest").asText();
    List<String> outcomes =
        parallel(
            f,
            List.of(
                supersede(
                    f.administrator(),
                    key(),
                    run,
                    superseding("UPSTREAM_CORRECTION", null, digest)),
                supersede(
                    f.administrator(),
                    key(),
                    run,
                    superseding("UPSTREAM_REAPPROVAL", null, digest))));
    assertThat(outcomes).containsExactlyInAnyOrder("SUPERSEDED", "409 PAYROLL_INPUT_NOT_LATEST");
    assertThat(count("SELECT count(*) FROM payroll.input_run WHERE tenant_id = ?", f.tenant()))
        .isEqualTo(2);
    assertThat(
            count(
                "SELECT count(*) FROM payroll.input_run_supersession WHERE tenant_id = ?",
                f.tenant()))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM platform.outbox_event WHERE envelope ->> 'tenantId' = ? AND"
                    + " envelope ->> 'eventType' = 'payroll.payroll-input.superseded.v1'",
                f.tenant().toString()))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM payroll.input_source_row WHERE tenant_id = ?"
                    + " AND run_id IN (SELECT id FROM payroll.input_run WHERE tenant_id = ? AND"
                    + " version = 2)",
                f.tenant(),
                f.tenant()))
        .isEqualTo(4);
  }
}
