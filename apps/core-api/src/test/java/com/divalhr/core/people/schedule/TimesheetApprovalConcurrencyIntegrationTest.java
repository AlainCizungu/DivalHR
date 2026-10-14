package com.divalhr.core.people.schedule;

import static com.divalhr.core.people.schedule.AttendanceCalls.checkIn;
import static com.divalhr.core.people.schedule.AttendanceCalls.checkOut;
import static com.divalhr.core.people.schedule.AttendanceCalls.instants;
import static com.divalhr.core.people.schedule.ScheduleWorld.JSON;
import static com.divalhr.core.people.schedule.ScheduleWorld.proposal;
import static com.divalhr.core.people.schedule.ScheduleWorld.shift;
import static com.divalhr.core.people.schedule.SubstitutionCalls.slotIds;
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.approval;
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.approve;
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.decision;
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.giveBack;
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
 * MVP-070B/C concurrency and atomicity against real PostgreSQL (T70BC-13 items 11, 12 and 15):
 * concurrent approvals and returns of one submission give exactly one terminal action; concurrent
 * submissions of one draft give one cycle; a submission racing a refresh leaves either a submitted
 * timesheet and a refused refresh, or a refreshed draft and a stale submission; an audit or outbox
 * failure rolls the whole submission or decision back, and the same command then applies, not
 * replays. The spies match {@link TimesheetConcurrencyIntegrationTest}, so they share one context.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
class TimesheetApprovalConcurrencyIntegrationTest {

  private static final int PARALLEL = 4;
  private static final ZoneId KINSHASA = ZoneId.of("Africa/Kinshasa");
  private static final String REASON = "Le lundi doit être revu avant approbation.";

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
      Person mia,
      Person ana,
      LocalDate monday,
      String generationId,
      String timesheetId) {

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
    JsonNode generated =
        w.expect(
            generate(
                admin(w),
                Organizations.newKey(),
                confirmed(TimesheetCalls.period(w.site(), monday))),
            200);
    String generationId = generated.get("generation").get("generationId").asText();
    String timesheetId =
        jdbc.queryForObject(
            "SELECT id::text FROM people.timesheet WHERE generation_id = ?::uuid",
            String.class,
            generationId);
    return new Week(w, mia, ana, monday, generationId, timesheetId);
  }

  private static String admin(ScheduleWorld w) {
    return Hierarchy.bearer(w.tenant(), "sub-tsa-race-" + UUID.randomUUID(), "tenant-admin");
  }

  private int count(String sql, Object... args) {
    Integer n = jdbc.queryForObject(sql, Integer.class, args);
    return n == null ? 0 : n;
  }

  /** Actions, lifecycle audit records, lifecycle events, and the timesheet's status and cycle. */
  private List<Object> written(Week t) {
    UUID tenant = t.w().tenant();
    return List.of(
        count("SELECT count(*) FROM people.timesheet_action WHERE tenant_id = ?", tenant),
        count(
            "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ? AND action IN"
                + " ('timesheet.submit', 'timesheet.approve', 'timesheet.return')",
            tenant),
        count(
            "SELECT count(*) FROM platform.outbox_event WHERE envelope ->> 'tenantId' = ? AND"
                + " envelope ->> 'eventType' IN ('people.timesheet.submitted.v1',"
                + " 'people.timesheet.approved.v1', 'people.timesheet.returned.v1')",
            tenant.toString()),
        jdbc.queryForObject(
            "SELECT status || ':' || submission_cycle FROM people.timesheet WHERE id = ?::uuid",
            String.class,
            t.timesheetId()));
  }

  private static List<MockHttpServletResponse> parallel(
      ScheduleWorld w, List<MockHttpServletRequestBuilder> calls) throws Exception {
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
      List<MockHttpServletResponse> responses = new ArrayList<>();
      for (Future<MockHttpServletResponse> future : futures) {
        responses.add(future.get(60, TimeUnit.SECONDS));
      }
      return responses;
    } finally {
      pool.shutdownNow();
    }
  }

  private static String code(MockHttpServletResponse response) throws Exception {
    JsonNode body = JSON.readTree(response.getContentAsString());
    int status = response.getStatus();
    if (status == 200) {
      return body.has("action") ? "200 " + body.get("action").asText() : "200";
    }
    return status + " " + body.get("code").asText();
  }

  private static JsonNode reviewedOwn(Week t) throws Exception {
    return t.w().expect(mine(t.ana().bearer(), t.timesheetId()), 200);
  }

  private static JsonNode submitted(Week t) throws Exception {
    t.w()
        .expect(
            submit(
                t.ana().bearer(),
                Organizations.newKey(),
                t.timesheetId(),
                submission(reviewedOwn(t))),
            200);
    return t.w().expect(approval(t.mia().bearer(), t.timesheetId()), 200);
  }

  // ------------------------------------------------------------------------------------------
  // 12: one terminal decision; one cycle per submission
  // ------------------------------------------------------------------------------------------

  @Test
  void concurrentApprovalsAndReturnsGiveExactlyOneTerminalAction() throws Exception {
    Week t = fresh();
    JsonNode reviewed = submitted(t);
    List<MockHttpServletRequestBuilder> calls = new ArrayList<>();
    for (int i = 0; i < PARALLEL; i++) {
      calls.add(
          i % 2 == 0
              ? approve(
                  t.mia().bearer(), Organizations.newKey(), t.timesheetId(), decision(reviewed))
              : giveBack(
                  t.mia().bearer(),
                  Organizations.newKey(),
                  t.timesheetId(),
                  TimesheetApprovalCalls.giveBack(reviewed, REASON)));
    }
    List<String> codes = new ArrayList<>();
    for (MockHttpServletResponse response : parallel(t.w(), calls)) {
      codes.add(code(response));
    }
    assertThat(codes).filteredOn(c -> c.startsWith("200")).hasSize(1);
    assertThat(codes)
        .filteredOn(c -> !c.startsWith("200"))
        .allMatch("409 TIMESHEET_DECISION_NOT_ALLOWED"::equals);
    String winner = codes.stream().filter(c -> c.startsWith("200")).findFirst().orElseThrow();
    assertThat(written(t))
        .containsExactly(2, 2, 2, "200 APPROVED".equals(winner) ? "APPROVED:1" : "DRAFT:1");
  }

  @Test
  void concurrentSubmissionsOfOneDraftGiveOneCycle() throws Exception {
    Week t = fresh();
    JsonNode own = reviewedOwn(t);
    List<MockHttpServletRequestBuilder> calls = new ArrayList<>();
    for (int i = 0; i < PARALLEL; i++) {
      calls.add(submit(t.ana().bearer(), Organizations.newKey(), t.timesheetId(), submission(own)));
    }
    List<String> codes = new ArrayList<>();
    for (MockHttpServletResponse response : parallel(t.w(), calls)) {
      codes.add(code(response));
    }
    assertThat(codes).filteredOn("200 SUBMITTED"::equals).hasSize(1);
    assertThat(codes)
        .filteredOn(c -> !"200 SUBMITTED".equals(c))
        .allMatch("409 TIMESHEET_SUBMISSION_NOT_ALLOWED"::equals);
    assertThat(written(t)).containsExactly(1, 1, 1, "SUBMITTED:1");
  }

  @Test
  void aSubmissionRacingARefreshNeverSubmitsAStaleRevision() throws Exception {
    Week t = fresh();
    String hr = admin(t.w());
    JsonNode own = reviewedOwn(t);
    JsonNode preview = t.w().expect(previewRefresh(hr, t.generationId(), t.period()), 200);
    List<String> codes = new ArrayList<>();
    for (MockHttpServletResponse response :
        parallel(
            t.w(),
            List.of(
                submit(t.ana().bearer(), Organizations.newKey(), t.timesheetId(), submission(own)),
                refresh(
                    hr,
                    Organizations.newKey(),
                    t.generationId(),
                    refreshBody(t.period(), preview))))) {
      codes.add(code(response));
    }
    // Either the submission won (the refresh is refused), or the empty refresh moved the
    // generation version first (the submission's reviewed version is stale).
    if ("200 SUBMITTED".equals(codes.get(0))) {
      assertThat(codes.get(1)).isEqualTo("409 TIMESHEET_GENERATION_NOT_ALLOWED");
      assertThat(written(t)).containsExactly(1, 1, 1, "SUBMITTED:1");
    } else {
      assertThat(codes).containsExactly("409 TIMESHEET_DECISION_STALE", "200");
      assertThat(written(t)).containsExactly(0, 0, 0, "DRAFT:0");
    }
  }

  // ------------------------------------------------------------------------------------------
  // 15: atomic evidence, audit and outbox
  // ------------------------------------------------------------------------------------------

  private void rolledBack(Consumer<String> fail, Runnable heal, String step) throws Exception {
    Week t = fresh();
    boolean decision = !step.endsWith("submit") && !step.endsWith("submitted.v1");
    JsonNode reviewed = decision ? submitted(t) : null;
    List<Object> before = written(t);
    String key = Organizations.newKey();
    MockHttpServletRequestBuilder call =
        decision
            ? approve(t.mia().bearer(), key, t.timesheetId(), decision(reviewed))
            : submit(t.ana().bearer(), key, t.timesheetId(), submission(reviewedOwn(t)));
    fail.accept(step);
    try {
      assertThat(t.w().call(call).getStatus()).isEqualTo(500);
    } finally {
      heal.run();
    }
    assertThat(written(t)).isEqualTo(before);
    // Nothing was kept, so the same command (same key) applies now, not replayed.
    MockHttpServletRequestBuilder again =
        decision
            ? approve(t.mia().bearer(), key, t.timesheetId(), decision(reviewed))
            : submit(t.ana().bearer(), key, t.timesheetId(), submission(reviewedOwn(t)));
    MockHttpServletResponse applied = t.w().call(again);
    assertThat(applied.getStatus()).isEqualTo(200);
    assertThat(applied.getHeader("Idempotent-Replayed")).isNull();
    assertThat(written(t))
        .containsExactly(
            decision ? 2 : 1,
            decision ? 2 : 1,
            decision ? 2 : 1,
            decision ? "APPROVED:1" : "SUBMITTED:1");
  }

  private void outboxFails(String type) throws Exception {
    OutboxWriter target = AopTestUtils.getUltimateTargetObject(outbox);
    rolledBack(
        step ->
            doThrow(new IllegalStateException("simulated outbox failure"))
                .when(target)
                .append(
                    argThat(
                        (EventEnvelope event) -> event != null && step.equals(event.eventType()))),
        () -> {
          org.mockito.Mockito.reset(target);
          doCallRealMethod().when(target).append(any(EventEnvelope.class));
        },
        type);
  }

  private void auditFails(String action) throws Exception {
    AuditRecorder target = AopTestUtils.getUltimateTargetObject(audit);
    rolledBack(
        step ->
            doThrow(new IllegalStateException("simulated audit failure"))
                .when(target)
                .record(
                    argThat((AuditEvent event) -> event != null && step.equals(event.action()))),
        () -> {
          org.mockito.Mockito.reset(target);
          doCallRealMethod().when(target).record(any(AuditEvent.class));
        },
        action);
  }

  @Test
  void anOutboxOrAuditFailureRollsTheWholeSubmissionBack() throws Exception {
    outboxFails("people.timesheet.submitted.v1");
    auditFails("timesheet.submit");
  }

  @Test
  void anOutboxOrAuditFailureRollsTheWholeApprovalBack() throws Exception {
    outboxFails("people.timesheet.approved.v1");
    auditFails("timesheet.approve");
  }
}
