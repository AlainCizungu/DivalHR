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
import com.divalhr.core.platform.idempotency.IdempotencyService;
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
 * MVP-070D concurrency and atomicity against real PostgreSQL (T70D-11 items 10 and 13): a
 * correction racing a refresh, a decision or another correction serializes on the scheduling lock
 * to one valid outcome, never two reopenings and never an approval of changed time; an audit or
 * outbox failure of the reopening rolls the whole correction back, and the same command then
 * applies. The spies match {@link TimesheetApprovalConcurrencyIntegrationTest}, so they share one
 * context.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
class TimesheetReopeningConcurrencyIntegrationTest {

  private static final ZoneId KINSHASA = ZoneId.of("Africa/Kinshasa");

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;
  @MockitoSpyBean private OutboxWriter outbox;
  @MockitoSpyBean private AuditRecorder audit;
  @MockitoSpyBean private IdempotencyService idempotency;

  @AfterEach
  void reset() {
    clock.reset();
    org.mockito.Mockito.reset((OutboxWriter) AopTestUtils.getUltimateTargetObject(outbox));
    org.mockito.Mockito.reset((AuditRecorder) AopTestUtils.getUltimateTargetObject(audit));
    org.mockito.Mockito.reset(
        (IdempotencyService) AopTestUtils.getUltimateTargetObject(idempotency));
  }

  /** Ana (managed by Mia) worked Monday 08-16 of a generated week; the clock is its Wednesday. */
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

    Map<String, Object> period() {
      return TimesheetCalls.period(w.site(), monday);
    }
  }

  private Week fresh() throws Exception {
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
    return new Week(w, admin, mia, ana, monday, generationId, timesheetId, record, at);
  }

  private static String admin(ScheduleWorld w) {
    return Hierarchy.bearer(w.tenant(), "sub-tsr-race-" + UUID.randomUUID(), "tenant-admin");
  }

  /** Ana submits; returns Mia's reviewed submission. */
  private static JsonNode submitted(Week t) throws Exception {
    JsonNode own = t.w().expect(mine(t.ana().bearer(), t.timesheetId()), 200);
    t.w()
        .expect(
            submit(t.ana().bearer(), Organizations.newKey(), t.timesheetId(), submission(own)),
            200);
    return t.w().expect(approval(t.mia().bearer(), t.timesheetId()), 200);
  }

  private static void approved(Week t) throws Exception {
    JsonNode reviewed = submitted(t);
    t.w()
        .expect(
            approve(t.mia().bearer(), Organizations.newKey(), t.timesheetId(), decision(reviewed)),
            200);
  }

  /** A reviewed correction of Ana's record moving her check-in by the given minutes. */
  private static MockHttpServletRequestBuilder correction(Week t, int minutes) throws Exception {
    Map<String, Object> proposal =
        values(t.shift()[0].plus(Duration.ofMinutes(minutes)), t.shift()[1]);
    JsonNode preview = t.w().expect(previewRecord(t.admin(), t.record(), proposal), 200);
    return commitRecord(t.admin(), Organizations.newKey(), t.record(), commit(proposal, preview));
  }

  private int count(String sql, Object... args) {
    Integer n = jdbc.queryForObject(sql, Integer.class, args);
    return n == null ? 0 : n;
  }

  /** Reopenings, corrections, reopening audits and events, and the timesheet's state. */
  private List<Object> written(Week t) {
    UUID tenant = t.w().tenant();
    return List.of(
        count(
            "SELECT count(*) FROM people.timesheet_action WHERE tenant_id = ? AND action ="
                + " 'REOPENED'",
            tenant),
        count("SELECT count(*) FROM people.attendance_correction WHERE tenant_id = ?", tenant),
        count(
            "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ? AND action ="
                + " 'timesheet.reopen'",
            tenant),
        count(
            "SELECT count(*) FROM platform.outbox_event WHERE envelope ->> 'tenantId' = ? AND"
                + " envelope ->> 'eventType' = 'people.timesheet.reopened.v1'",
            tenant.toString()),
        jdbc.queryForObject(
            "SELECT status || ':' || submission_cycle || ':r' || current_revision FROM"
                + " people.timesheet WHERE id = ?::uuid",
            String.class,
            t.timesheetId()));
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
            response.getStatus() < 300
                ? String.valueOf(response.getStatus())
                : response.getStatus() + " " + body.get("code").asText());
      }
      return codes;
    } finally {
      pool.shutdownNow();
    }
  }

  // ------------------------------------------------------------------------------------------
  // 10: races
  // ------------------------------------------------------------------------------------------

  @Test
  void aCorrectionRacingARefreshReopensOnceAndNeverRefreshesAStalePreview() throws Exception {
    Week t = fresh();
    approved(t);
    MockHttpServletRequestBuilder correction = correction(t, 20);
    JsonNode preview = t.w().expect(previewRefresh(t.admin(), t.generationId(), t.period()), 200);
    assertThat(preview.get("lockedTimesheets").asInt()).isEqualTo(1);
    List<String> codes =
        parallel(
            t.w(),
            List.of(
                correction,
                refresh(
                    t.admin(),
                    Organizations.newKey(),
                    t.generationId(),
                    refreshBody(t.period(), preview))));
    assertThat(codes.get(0)).isEqualTo("201");
    // Either the refresh came first (Ana stayed locked; version 2), or the correction did (the
    // refresh's preview no longer matches: Ana is REOPENED now).
    assertThat(codes.get(1)).isIn("200", "409 TIMESHEET_REFRESH_PREVIEW_STALE");
    assertThat(written(t)).containsExactly(1, 1, 1, 1, "REOPENED:1:r1");
  }

  @Test
  void aCorrectionRacingADecisionNeverApprovesChangedTimeNorReopensIt() throws Exception {
    Week t = fresh();
    JsonNode reviewed = submitted(t);
    MockHttpServletRequestBuilder correction = correction(t, 20);
    List<String> codes =
        parallel(
            t.w(),
            List.of(
                correction,
                approve(
                    t.mia().bearer(),
                    Organizations.newKey(),
                    t.timesheetId(),
                    decision(reviewed))));
    // The correction first: the approval sees changed sources (stale). The approval first: the
    // correction's preview did not know of approved time (stale). Never both, never a reopening.
    if ("201".equals(codes.get(0))) {
      assertThat(codes.get(1)).isEqualTo("409 TIMESHEET_DECISION_STALE");
      assertThat(written(t)).containsExactly(0, 1, 0, 0, "SUBMITTED:1:r1");
    } else {
      assertThat(codes).containsExactly("409 ATTENDANCE_CORRECTION_PREVIEW_STALE", "200");
      assertThat(written(t)).containsExactly(0, 0, 0, 0, "APPROVED:1:r1");
    }
  }

  @Test
  void concurrentCorrectionsOfApprovedTimeReopenItExactlyOnce() throws Exception {
    Week t = fresh();
    approved(t);
    List<MockHttpServletRequestBuilder> calls = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      calls.add(correction(t, 10 + i * 5));
    }
    List<String> codes = parallel(t.w(), calls);
    assertThat(codes).filteredOn("201"::equals).hasSize(1);
    assertThat(codes)
        .filteredOn(c -> !"201".equals(c))
        .allMatch("409 ATTENDANCE_CORRECTION_PREVIEW_STALE"::equals);
    assertThat(written(t)).containsExactly(1, 1, 1, 1, "REOPENED:1:r1");
  }

  @Test
  void aSubmissionRacingARefreshOfAReopenedWeekSubmitsOnlyTheRefreshedRevision() throws Exception {
    Week t = fresh();
    approved(t);
    t.w().expect(correction(t, 20), 201);
    JsonNode reopened = t.w().expect(mine(t.ana().bearer(), t.timesheetId()), 200);
    JsonNode preview = t.w().expect(previewRefresh(t.admin(), t.generationId(), t.period()), 200);
    List<String> codes =
        parallel(
            t.w(),
            List.of(
                submit(
                    t.ana().bearer(),
                    Organizations.newKey(),
                    t.timesheetId(),
                    submission(reopened)),
                refresh(
                    t.admin(),
                    Organizations.newKey(),
                    t.generationId(),
                    refreshBody(t.period(), preview))));
    // A reopened timesheet is never submitted; after the refresh, its reviewed revision is stale.
    assertThat(codes.get(0))
        .isIn("409 TIMESHEET_SUBMISSION_NOT_ALLOWED", "409 TIMESHEET_DECISION_STALE");
    assertThat(codes.get(1)).isEqualTo("200");
    assertThat(written(t)).containsExactly(1, 1, 1, 1, "DRAFT:1:r2");
  }

  // ------------------------------------------------------------------------------------------
  // 13: atomic correction, reopening, audit and outbox
  // ------------------------------------------------------------------------------------------

  private void rolledBack(Consumer<String> fail, Runnable heal, String step) throws Exception {
    Week t = fresh();
    approved(t);
    List<Object> before = written(t);
    Map<String, Object> proposal = values(t.shift()[0].plus(Duration.ofMinutes(20)), t.shift()[1]);
    JsonNode preview = t.w().expect(previewRecord(t.admin(), t.record(), proposal), 200);
    String key = Organizations.newKey();
    fail.accept(step);
    try {
      assertThat(
              t.w()
                  .call(commitRecord(t.admin(), key, t.record(), commit(proposal, preview)))
                  .getStatus())
          .isEqualTo(500);
    } finally {
      heal.run();
    }
    // Neither the correction nor the reopening was kept.
    assertThat(written(t)).isEqualTo(before);
    MockHttpServletResponse applied =
        t.w().call(commitRecord(t.admin(), key, t.record(), commit(proposal, preview)));
    assertThat(applied.getStatus()).isEqualTo(201);
    assertThat(applied.getHeader("Idempotent-Replayed")).isNull();
    assertThat(written(t)).containsExactly(1, 1, 1, 1, "REOPENED:1:r1");
  }

  @Test
  void anOutboxOrAuditFailureOfTheReopeningRollsTheWholeCorrectionBack() throws Exception {
    OutboxWriter events = AopTestUtils.getUltimateTargetObject(outbox);
    rolledBack(
        step ->
            doThrow(new IllegalStateException("simulated outbox failure"))
                .when(events)
                .append(
                    argThat(
                        (EventEnvelope event) -> event != null && step.equals(event.eventType()))),
        () -> {
          org.mockito.Mockito.reset(events);
          doCallRealMethod().when(events).append(any(EventEnvelope.class));
        },
        "people.timesheet.reopened.v1");
    AuditRecorder records = AopTestUtils.getUltimateTargetObject(audit);
    rolledBack(
        step ->
            doThrow(new IllegalStateException("simulated audit failure"))
                .when(records)
                .record(
                    argThat((AuditEvent event) -> event != null && step.equals(event.action()))),
        () -> {
          org.mockito.Mockito.reset(records);
          doCallRealMethod().when(records).record(any(AuditEvent.class));
        },
        "timesheet.reopen");
  }
}
