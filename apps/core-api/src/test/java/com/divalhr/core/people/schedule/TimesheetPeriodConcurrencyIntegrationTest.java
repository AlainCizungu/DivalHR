package com.divalhr.core.people.schedule;

import static com.divalhr.core.people.schedule.AttendanceCalls.checkIn;
import static com.divalhr.core.people.schedule.AttendanceCalls.checkOut;
import static com.divalhr.core.people.schedule.AttendanceCalls.instants;
import static com.divalhr.core.people.schedule.CorrectionCalls.commit;
import static com.divalhr.core.people.schedule.CorrectionCalls.commitRecord;
import static com.divalhr.core.people.schedule.CorrectionCalls.previewRecord;
import static com.divalhr.core.people.schedule.CorrectionCalls.values;
import static com.divalhr.core.people.schedule.ScheduleWorld.JSON;
import static com.divalhr.core.people.schedule.ScheduleWorld.proposal;
import static com.divalhr.core.people.schedule.ScheduleWorld.shift;
import static com.divalhr.core.people.schedule.SubstitutionCalls.slotIds;
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.approval;
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.approve;
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.decision;
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.submission;
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.submit;
import static com.divalhr.core.people.schedule.TimesheetCalls.confirmed;
import static com.divalhr.core.people.schedule.TimesheetCalls.generate;
import static com.divalhr.core.people.schedule.TimesheetCalls.mine;
import static com.divalhr.core.people.schedule.TimesheetCalls.previewRefresh;
import static com.divalhr.core.people.schedule.TimesheetCalls.refresh;
import static com.divalhr.core.people.schedule.TimesheetCalls.refreshBody;
import static com.divalhr.core.people.schedule.TimesheetPeriodCalls.EXPLANATION;
import static com.divalhr.core.people.schedule.TimesheetPeriodCalls.lock;
import static com.divalhr.core.people.schedule.TimesheetPeriodCalls.locking;
import static com.divalhr.core.people.schedule.TimesheetPeriodCalls.previewLock;
import static com.divalhr.core.people.schedule.TimesheetPeriodCalls.previewUnlock;
import static com.divalhr.core.people.schedule.TimesheetPeriodCalls.reasoned;
import static com.divalhr.core.people.schedule.TimesheetPeriodCalls.unlock;
import static com.divalhr.core.people.schedule.TimesheetPeriodCalls.unlocking;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.people.schedule.ScheduleWorld.Person;
import com.divalhr.core.people.schedule.domain.Timesheet;
import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
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
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-071 (Issue #139, L71-11 items 11 and 14) against real PostgreSQL: concurrent locks, unlocks,
 * corrections and refreshes of one week end in exactly one legal sequence of transitions without a
 * deadlock, and a lock or unlock whose audit record or event fails writes nothing.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
class TimesheetPeriodConcurrencyIntegrationTest {

  private static final ZoneId KINSHASA = ZoneId.of("Africa/Kinshasa");

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;
  @MockitoSpyBean private OutboxWriter outbox;
  @MockitoSpyBean private AuditRecorder audit;

  @AfterEach
  void reset() {
    clock.reset();
    org.mockito.Mockito.reset((OutboxWriter) AopTestUtils.getUltimateTargetObject(outbox));
    org.mockito.Mockito.reset((AuditRecorder) AopTestUtils.getUltimateTargetObject(audit));
  }

  /** Ana's Monday 08-16 week, generated, submitted and approved by Mia; the clock its Wednesday. */
  private record Week(
      ScheduleWorld w,
      String admin,
      Person mia,
      Person ana,
      LocalDate monday,
      String generationId,
      String timesheetId,
      String record,
      Instant[] shift) {

    Map<String, Object> scope() {
      return TimesheetPeriodCalls.scope(w.org.legalEntityId(), w.site(), monday);
    }

    Map<String, Object> period() {
      return TimesheetCalls.period(w.site(), monday);
    }
  }

  private Week approved() throws Exception {
    clock.reset();
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person mia = w.person("Mia", "Mbuyi");
    Person ana = w.person("Ana", "Kalala");
    w.manageAll(mia, ana);
    LocalDate monday = Timesheet.Period.mondayOf(w.today).plusDays(7);
    String slot =
        slotIds(
                w,
                w.published(
                    proposal(
                        w.site(),
                        monday,
                        monday,
                        List.of(shift("a", monday, 8, 16, ana.employee())))))
            .get(0);
    Instant[] at = instants(jdbc, slot);
    clock.set(at[0].plus(Duration.ofMinutes(3)));
    String record =
        w.expect(checkIn(ana, Organizations.newKey(), slot), 201)
            .get("attendanceRecordId")
            .asText();
    clock.set(at[1].minus(Duration.ofMinutes(2)));
    w.expect(checkOut(ana, Organizations.newKey(), record), 200);
    clock.set(monday.plusDays(2).atTime(LocalTime.NOON).atZone(KINSHASA).toInstant());
    String admin = admin(w);
    String generationId =
        w.expect(
                generate(
                    admin,
                    Organizations.newKey(),
                    confirmed(TimesheetCalls.period(w.site(), monday))),
                200)
            .get("generation")
            .get("generationId")
            .asText();
    String timesheetId =
        jdbc.queryForObject(
            "SELECT id::text FROM people.timesheet WHERE generation_id = ?::uuid",
            String.class,
            generationId);
    JsonNode own = w.expect(mine(ana.bearer(), timesheetId), 200);
    w.expect(submit(ana.bearer(), Organizations.newKey(), timesheetId, submission(own)), 200);
    JsonNode reviewed = w.expect(approval(mia.bearer(), timesheetId), 200);
    w.expect(approve(mia.bearer(), Organizations.newKey(), timesheetId, decision(reviewed)), 200);
    return new Week(w, admin, mia, ana, monday, generationId, timesheetId, record, at);
  }

  private static String admin(ScheduleWorld w) {
    return Hierarchy.bearer(w.tenant(), "sub-tsp-race-" + UUID.randomUUID(), "tenant-admin");
  }

  private int count(String sql, Object... args) {
    Integer n = jdbc.queryForObject(sql, Integer.class, args);
    return n == null ? 0 : n;
  }

  /** Period actions, projections, lock/unlock audits and events of the tenant. */
  private List<Integer> written(Week t) {
    UUID tenant = t.w().tenant();
    return List.of(
        count("SELECT count(*) FROM people.timesheet_period_action WHERE tenant_id = ?", tenant),
        count("SELECT count(*) FROM people.timesheet_period_lock WHERE tenant_id = ?", tenant),
        count(
            "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ? AND action IN"
                + " ('timesheet-period.lock', 'timesheet-period.unlock')",
            tenant),
        count(
            "SELECT count(*) FROM platform.outbox_event WHERE envelope ->> 'tenantId' = ? AND"
                + " envelope ->> 'eventType' LIKE 'people.timesheet-period.%'",
            tenant.toString()));
  }

  private String state(Week t) {
    return jdbc.queryForObject(
        "SELECT coalesce((SELECT state || ':' || version FROM people.timesheet_period_lock WHERE"
            + " generation_id = ?::uuid), 'NEVER')",
        String.class,
        t.generationId());
  }

  private List<String> sequence(Week t) {
    return jdbc.queryForList(
        "SELECT sequence || ':' || action FROM people.timesheet_period_action WHERE"
            + " generation_id = ?::uuid ORDER BY sequence",
        String.class,
        t.generationId());
  }

  private static List<String> parallel(ScheduleWorld w, List<MockHttpServletRequestBuilder> calls)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(calls.size());
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
      for (MockHttpServletRequestBuilder call : calls) {
        Callable<MockHttpServletResponse> task =
            () -> {
              start.await();
              return w.call(call);
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

  // ------------------------------------------------------------------------------------------
  // 11: races
  // ------------------------------------------------------------------------------------------

  @Test
  void concurrentLocksOfOneWeekWriteOneLock() throws Exception {
    Week t = approved();
    JsonNode preview = t.w().expect(previewLock(t.admin(), t.scope()), 200);
    List<MockHttpServletRequestBuilder> calls = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      calls.add(lock(admin(t.w()), Organizations.newKey(), locking(t.scope(), preview)));
    }
    List<String> outcomes = parallel(t.w(), calls);
    // One locks; the others find the lock version moved: the preview is stale.
    assertThat(outcomes).filteredOn("LOCKED"::equals).hasSize(1);
    assertThat(outcomes).filteredOn("409 TIMESHEET_PERIOD_PREVIEW_STALE"::equals).hasSize(2);
    assertThat(written(t)).containsExactly(1, 1, 1, 1);
    assertThat(state(t)).isEqualTo("LOCKED:1");
  }

  @Test
  void aLockRacingACorrectionAndARefreshYieldsOneLegalResult() throws Exception {
    Week t = approved();
    ScheduleWorld w = t.w();
    JsonNode preview = w.expect(previewLock(t.admin(), t.scope()), 200);
    Map<String, Object> fix = values(t.shift()[0].plus(Duration.ofMinutes(20)), t.shift()[1]);
    JsonNode reviewed = w.expect(previewRecord(t.admin(), t.record(), fix), 200);
    String refresher = admin(w);
    JsonNode refreshPreview =
        w.expect(previewRefresh(refresher, t.generationId(), t.period()), 200);
    List<String> outcomes =
        parallel(
            w,
            List.of(
                lock(admin(w), Organizations.newKey(), locking(t.scope(), preview)),
                commitRecord(t.admin(), Organizations.newKey(), t.record(), commit(fix, reviewed)),
                refresh(
                    refresher,
                    Organizations.newKey(),
                    t.generationId(),
                    refreshBody(t.period(), refreshPreview))));
    String status =
        jdbc.queryForObject(
            "SELECT status FROM people.timesheet WHERE id = ?::uuid",
            String.class,
            t.timesheetId());
    if ("LOCKED".equals(outcomes.get(0))) {
      // The lock first: the correction and the refresh are refused by the locked week; the
      // approved timesheet is untouched.
      assertThat(outcomes.get(1)).isEqualTo("409 TIMESHEET_PERIOD_LOCKED");
      assertThat(outcomes.get(2)).isEqualTo("409 TIMESHEET_PERIOD_LOCKED");
      assertThat(status).isEqualTo("APPROVED");
      assertThat(state(t)).isEqualTo("LOCKED:1");
      assertThat(
              count(
                  "SELECT count(*) FROM people.attendance_correction WHERE tenant_id = ?",
                  w.tenant()))
          .isZero();
    } else {
      // The correction first reopens the week (the lock preview is stale or the week not ready),
      // or the refresh first changes the version (stale): nothing was locked.
      assertThat(outcomes.get(0))
          .isIn("409 TIMESHEET_PERIOD_PREVIEW_STALE", "409 TIMESHEET_PERIOD_NOT_READY");
      assertThat(written(t)).containsExactly(0, 0, 0, 0);
      assertThat(state(t)).isEqualTo("NEVER");
    }
    // Whatever the order, one legal history: no deadlock, no half transition.
    assertThat(sequence(t)).isIn(List.of(), List.of("1:LOCKED"));
  }

  @Test
  void concurrentUnlocksAndALockOfALockedWeekYieldOneLegalSequence() throws Exception {
    Week t = approved();
    ScheduleWorld w = t.w();
    JsonNode preview = w.expect(previewLock(t.admin(), t.scope()), 200);
    w.expect(lock(t.admin(), Organizations.newKey(), locking(t.scope(), preview)), 200);
    Map<String, Object> reasoned = reasoned(t.scope(), "ORGANIZATION_REQUEST", EXPLANATION);
    JsonNode unlockPreview = w.expect(previewUnlock(t.admin(), reasoned), 200);
    JsonNode lockedPreview = w.expect(previewLock(t.admin(), t.scope()), 200);
    List<String> outcomes =
        parallel(
            w,
            List.of(
                unlock(admin(w), Organizations.newKey(), unlocking(reasoned, unlockPreview)),
                unlock(admin(w), Organizations.newKey(), unlocking(reasoned, unlockPreview)),
                lock(admin(w), Organizations.newKey(), locking(t.scope(), lockedPreview))));
    assertThat(outcomes.subList(0, 2))
        .containsExactlyInAnyOrder("UNLOCKED", "409 TIMESHEET_PERIOD_NOT_LOCKED");
    // The lock with the locked week's preview: UNCHANGED while still locked, or stale after the
    // unlock moved the version; never a second LOCKED action.
    assertThat(outcomes.get(2)).isIn("UNCHANGED", "409 TIMESHEET_PERIOD_PREVIEW_STALE");
    assertThat(sequence(t)).containsExactly("1:LOCKED", "2:UNLOCKED");
    assertThat(state(t)).isEqualTo("OPEN:2");
    assertThat(written(t)).containsExactly(2, 1, 2, 2);
  }

  // ------------------------------------------------------------------------------------------
  // 14: atomicity
  // ------------------------------------------------------------------------------------------

  private void rolledBack(
      Week t,
      MockHttpServletRequestBuilder command,
      Consumer<String> fail,
      Runnable heal,
      String step,
      List<Integer> before,
      String stateBefore)
      throws Exception {
    fail.accept(step);
    try {
      assertThat(t.w().call(command).getStatus()).isEqualTo(500);
    } finally {
      heal.run();
    }
    // Neither the action, nor the projection, nor the audit record or event was kept.
    assertThat(written(t)).isEqualTo(before);
    assertThat(state(t)).isEqualTo(stateBefore);
  }

  @Test
  void anOutboxOrAuditFailureRollsTheTransitionBack() throws Exception {
    OutboxWriter events = AopTestUtils.getUltimateTargetObject(outbox);
    AuditRecorder records = AopTestUtils.getUltimateTargetObject(audit);
    Consumer<String> failEvent =
        step ->
            doThrow(new IllegalStateException("simulated outbox failure"))
                .when(events)
                .append(
                    argThat(
                        (EventEnvelope event) -> event != null && step.equals(event.eventType())));
    Runnable healEvent =
        () -> {
          org.mockito.Mockito.reset(events);
          doCallRealMethod().when(events).append(any(EventEnvelope.class));
        };
    Consumer<String> failAudit =
        step ->
            doThrow(new IllegalStateException("simulated audit failure"))
                .when(records)
                .record(
                    argThat((AuditEvent event) -> event != null && step.equals(event.action())));
    Runnable healAudit =
        () -> {
          org.mockito.Mockito.reset(records);
          doCallRealMethod().when(records).record(any(AuditEvent.class));
        };

    Week t = approved();
    ScheduleWorld w = t.w();
    JsonNode preview = w.expect(previewLock(t.admin(), t.scope()), 200);
    String key = Organizations.newKey();
    rolledBack(
        t,
        lock(t.admin(), key, locking(t.scope(), preview)),
        failEvent,
        healEvent,
        "people.timesheet-period.locked.v1",
        List.of(0, 0, 0, 0),
        "NEVER");
    rolledBack(
        t,
        lock(t.admin(), key, locking(t.scope(), preview)),
        failAudit,
        healAudit,
        "timesheet-period.lock",
        List.of(0, 0, 0, 0),
        "NEVER");
    // The same key then applies the lock (the failed attempts kept no idempotency record).
    MockHttpServletResponse applied = w.call(lock(t.admin(), key, locking(t.scope(), preview)));
    assertThat(applied.getStatus()).isEqualTo(200);
    assertThat(applied.getHeader("Idempotent-Replayed")).isNull();
    assertThat(JSON.readTree(applied.getContentAsString()).get("outcome").asText())
        .isEqualTo("LOCKED");
    assertThat(written(t)).containsExactly(1, 1, 1, 1);

    Map<String, Object> reasoned = reasoned(t.scope(), "OTHER", EXPLANATION);
    JsonNode unlockPreview = w.expect(previewUnlock(t.admin(), reasoned), 200);
    String unlockKey = Organizations.newKey();
    rolledBack(
        t,
        unlock(t.admin(), unlockKey, unlocking(reasoned, unlockPreview)),
        failEvent,
        healEvent,
        "people.timesheet-period.unlocked.v1",
        List.of(1, 1, 1, 1),
        "LOCKED:1");
    rolledBack(
        t,
        unlock(t.admin(), unlockKey, unlocking(reasoned, unlockPreview)),
        failAudit,
        healAudit,
        "timesheet-period.unlock",
        List.of(1, 1, 1, 1),
        "LOCKED:1");
    assertThat(
            w.expect(unlock(t.admin(), unlockKey, unlocking(reasoned, unlockPreview)), 200)
                .get("outcome")
                .asText())
        .isEqualTo("UNLOCKED");
    assertThat(written(t)).containsExactly(2, 1, 2, 2);
    assertThat(state(t)).isEqualTo("OPEN:2");
  }
}
