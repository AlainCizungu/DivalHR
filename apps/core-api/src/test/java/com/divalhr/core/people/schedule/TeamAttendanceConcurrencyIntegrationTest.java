package com.divalhr.core.people.schedule;

import static com.divalhr.core.people.schedule.AttendanceCalls.checkIn;
import static com.divalhr.core.people.schedule.AttendanceCalls.checkOut;
import static com.divalhr.core.people.schedule.AttendanceCalls.instants;
import static com.divalhr.core.people.schedule.ScheduleWorld.JSON;
import static com.divalhr.core.people.schedule.ScheduleWorld.cancel;
import static com.divalhr.core.people.schedule.ScheduleWorld.proposal;
import static com.divalhr.core.people.schedule.ScheduleWorld.replace;
import static com.divalhr.core.people.schedule.ScheduleWorld.shift;
import static com.divalhr.core.people.schedule.SubstitutionCalls.approve;
import static com.divalhr.core.people.schedule.SubstitutionCalls.request;
import static com.divalhr.core.people.schedule.SubstitutionCalls.slotIds;
import static com.divalhr.core.people.schedule.TeamAttendanceCalls.teamCheckIn;
import static com.divalhr.core.people.schedule.TeamAttendanceCalls.teamCheckOut;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.people.application.ManagerGraphLock;
import com.divalhr.core.people.schedule.ScheduleWorld.Person;
import com.divalhr.core.people.schedule.internal.JdbcWorkScheduleRepository;
import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.idempotency.IdempotencyScope;
import com.divalhr.core.platform.idempotency.IdempotencyService;
import com.divalhr.core.platform.idempotency.StoredResponse;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import com.divalhr.core.platform.tenancy.TenantId;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MVP-060B concurrency and atomicity against real PostgreSQL (B60B-6, B60B-9 items 11, 12 and 14):
 * a supervisor's and the employee's own command on one shift settle to one; a supervisor check-in
 * serializes with a manager change and a separation (behind the manager-graph lock) and with a
 * cancellation, a replacement and a substitution approval (behind the scheduling lock) in both
 * orders, each order one valid outcome; an outbox, audit or idempotency-completion failure rolls
 * the event, the supervisor action and the record back, and the same key then succeeds.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
class TeamAttendanceConcurrencyIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;
  @Autowired private JdbcWorkScheduleRepository schedules;
  @Autowired private ManagerGraphLock graph;
  @Autowired private PlatformTransactionManager transactions;
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

  /** Mia manages Ana and Ben from today; Ana works 08-16 on {@code day}. */
  private record Team(
      ScheduleWorld w,
      Person mia,
      Person ana,
      Person ben,
      LocalDate day,
      String schedule,
      String slot,
      Instant[] at) {}

  private Team team(int inDays) throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person mia = w.person("Mia", "Mbuyi");
    Person ana = w.person("Ana", "Kalala");
    Person ben = w.person("Ben", "Ilunga");
    w.manageAll(mia, ana, ben);
    LocalDate day = w.today.plusDays(inDays);
    String schedule =
        w.published(proposal(w.site(), day, day, List.of(shift("a", day, 8, 16, ana.employee()))));
    String slot = slotIds(w, schedule).get(0);
    return new Team(w, mia, ana, ben, day, schedule, slot, instants(jdbc, slot));
  }

  /** The clock one hour before Ana's start (inside the check-in window). */
  private void beforeStart(Team t) {
    clock.set(t.at()[0].minus(Duration.ofHours(1)));
  }

  private static String code(MockHttpServletResponse response) throws Exception {
    int status = response.getStatus();
    return status == 200 || status == 201
        ? Integer.toString(status)
        : status + " " + JSON.readTree(response.getContentAsString()).get("code").asText();
  }

  private int count(String sql, Object... args) {
    Integer n = jdbc.queryForObject(sql, Integer.class, args);
    return n == null ? 0 : n;
  }

  private int records(String slot) {
    return count("SELECT count(*) FROM people.attendance_record WHERE slot_id = ?::uuid", slot);
  }

  private int actions(String slot) {
    return count(
        "SELECT count(*) FROM people.attendance_supervisor_action WHERE slot_id = ?::uuid", slot);
  }

  /** Waits until at least {@code n} backends of this database wait on a lock. */
  private void awaitWaiting(int n) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      Integer waiting =
          jdbc.queryForObject(
              "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                  + " AND wait_event_type = 'Lock'",
              Integer.class);
      if (waiting != null && waiting >= n) {
        return;
      }
      Thread.sleep(25);
    }
    throw new AssertionError("expected " + n + " waiting transactions");
  }

  /** Runs the calls queued, in order, behind a lock the holder takes; their outcomes in order. */
  private List<String> queued(
      ScheduleWorld w, Runnable holderLock, List<MockHttpServletRequestBuilder> calls)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(calls.size() + 1);
    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try {
      Future<?> holder =
          pool.submit(
              () -> {
                new TransactionTemplate(transactions)
                    .executeWithoutResult(
                        status -> {
                          holderLock.run();
                          held.countDown();
                          try {
                            release.await(30, TimeUnit.SECONDS);
                          } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                          }
                        });
                return null;
              });
      assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
      List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
      for (int i = 0; i < calls.size(); i++) {
        MockHttpServletRequestBuilder call = calls.get(i);
        futures.add(pool.submit(() -> w.call(call)));
        awaitWaiting(i + 1);
      }
      release.countDown();
      holder.get(30, TimeUnit.SECONDS);
      List<String> codes = new ArrayList<>();
      for (Future<MockHttpServletResponse> future : futures) {
        codes.add(code(future.get(30, TimeUnit.SECONDS)));
      }
      return codes;
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
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
        codes.add(code(future.get(60, TimeUnit.SECONDS)));
      }
      return codes;
    } finally {
      pool.shutdownNow();
    }
  }

  private Runnable schedulingLock(ScheduleWorld w) {
    return () -> schedules.lock(new TenantId(w.tenant()));
  }

  private Runnable graphLock(ScheduleWorld w) {
    return () -> graph.lock(new TenantId(w.tenant()));
  }

  /**
   * A previewed change of Ana's MANAGER line to {@code manager} from the day before her shift, made
   * while the clock stands on the shift (so it is a late notification).
   */
  private static MockHttpServletRequestBuilder move(Team t, Person manager) throws Exception {
    Map<String, Object> command = t.w().managerCommand(manager.employee(), t.day().minusDays(1));
    command.put("reasonCode", "LATE_NOTIFICATION");
    return t.w().change(t.ana().employee(), command);
  }

  // ------------------------------------------------------------------------------------------
  // Supervisor against the employee herself
  // ------------------------------------------------------------------------------------------

  @Test
  void theEmployeesOwnAndTheSupervisorsCommandsSettleToExactlyOne() throws Exception {
    Team t = team(3);
    beforeStart(t);
    List<String> codes =
        parallel(
            t.w(),
            List.of(
                checkIn(t.ana(), Organizations.newKey(), t.slot()),
                teamCheckIn(t.mia(), Organizations.newKey(), t.slot())));
    assertThat(codes).filteredOn("201"::equals).hasSize(1);
    assertThat(codes)
        .filteredOn(c -> !"201".equals(c))
        .containsExactly("409 ATTENDANCE_ALREADY_CHECKED_IN");
    assertThat(records(t.slot())).isEqualTo(1);
    // A supervisor action exists exactly when the supervisor's command won.
    assertThat(actions(t.slot())).isEqualTo("201".equals(codes.get(1)) ? 1 : 0);
    String id =
        jdbc.queryForObject(
            "SELECT id::text FROM people.attendance_record WHERE slot_id = ?::uuid",
            String.class,
            t.slot());

    clock.set(t.at()[1]);
    codes =
        parallel(
            t.w(),
            List.of(
                checkOut(t.ana(), Organizations.newKey(), id),
                teamCheckOut(t.mia(), Organizations.newKey(), id)));
    assertThat(codes).filteredOn("200"::equals).hasSize(1);
    assertThat(codes)
        .filteredOn(c -> !"200".equals(c))
        .containsExactly("409 ATTENDANCE_ALREADY_CHECKED_OUT");
    assertThat(
            count(
                "SELECT count(*) FROM people.attendance_event WHERE record_id = ?::uuid"
                    + " AND event_type = 'CHECKED_OUT'",
                id))
        .isEqualTo(1);
  }

  // ------------------------------------------------------------------------------------------
  // Supervisor check-in against employment history (the manager-graph lock)
  // ------------------------------------------------------------------------------------------

  @Test
  void aSupervisorCheckInAndAManagerChangeSerializeInEitherOrder() throws Exception {
    // Check-in first: recorded by the manager of the moment; the line then moves.
    Team t = team(3);
    Person noa = t.w().person("Noa", "Banza");
    beforeStart(t);
    MockHttpServletRequestBuilder move = move(t, noa);
    assertThat(
            queued(
                t.w(),
                graphLock(t.w()),
                List.of(teamCheckIn(t.mia(), Organizations.newKey(), t.slot()), move)))
        .containsExactly("201", "201");
    assertThat(actions(t.slot())).isEqualTo(1);

    // Manager change first: Mia no longer manages Ana on the day; the same 404, nothing written.
    clock.reset();
    Team u = team(4);
    Person zoe = u.w().person("Zoé", "Tshala");
    beforeStart(u);
    move = move(u, zoe);
    assertThat(
            queued(
                u.w(),
                graphLock(u.w()),
                List.of(move, teamCheckIn(u.mia(), Organizations.newKey(), u.slot()))))
        .containsExactly("201", "404 TEAM_ATTENDANCE_ACTION_NOT_FOUND");
    assertThat(records(u.slot())).isZero();
    assertThat(actions(u.slot())).isZero();
  }

  @Test
  void aSupervisorCheckInAndASeparationSerializeInEitherOrder() throws Exception {
    // Separation of the target first (from before the shift, so at once): no qualifying
    // employment on the day; the same 404, nothing written.
    Team t = team(3);
    beforeStart(t);
    MockHttpServletRequestBuilder leave =
        t.w().separate(t.ana().employee(), t.day().minusDays(1), "IMMEDIATELY");
    assertThat(
            queued(
                t.w(),
                graphLock(t.w()),
                List.of(leave, teamCheckIn(t.mia(), Organizations.newKey(), t.slot()))))
        .containsExactly("201", "404 TEAM_ATTENDANCE_ACTION_NOT_FOUND");
    assertThat(records(t.slot())).isZero();
    assertThat(actions(t.slot())).isZero();

    // Check-in first: recorded while Ana was employed; the separation then proceeds and the
    // evidence stays.
    clock.reset();
    Team u = team(4);
    beforeStart(u);
    leave = u.w().separate(u.ana().employee(), u.day().minusDays(1), "IMMEDIATELY");
    List<String> codes =
        queued(
            u.w(),
            graphLock(u.w()),
            List.of(teamCheckIn(u.mia(), Organizations.newKey(), u.slot()), leave));
    assertThat(codes).containsExactly("201", "201");
    assertThat(records(u.slot())).isEqualTo(1);
    assertThat(actions(u.slot())).isEqualTo(1);
  }

  // ------------------------------------------------------------------------------------------
  // Supervisor check-in against schedule changes (the scheduling lock)
  // ------------------------------------------------------------------------------------------

  @Test
  void aSupervisorCheckInAndACancellationSerializeInEitherOrder() throws Exception {
    // Cancellation first: no operational shift; the same 404.
    Team t = team(3);
    beforeStart(t);
    assertThat(
            queued(
                t.w(),
                schedulingLock(t.w()),
                List.of(
                    cancel(t.w().admin, Organizations.newKey(), t.schedule(), "Fermeture"),
                    teamCheckIn(t.mia(), Organizations.newKey(), t.slot()))))
        .containsExactly("200", "404 TEAM_ATTENDANCE_ACTION_NOT_FOUND");
    assertThat(records(t.slot())).isZero();

    // Check-in first: the cancellation proceeds and the evidence stays.
    clock.reset();
    Team u = team(4);
    beforeStart(u);
    assertThat(
            queued(
                u.w(),
                schedulingLock(u.w()),
                List.of(
                    teamCheckIn(u.mia(), Organizations.newKey(), u.slot()),
                    cancel(u.w().admin, Organizations.newKey(), u.schedule(), "Fermeture"))))
        .containsExactly("201", "200");
    assertThat(records(u.slot())).isEqualTo(1);
    assertThat(actions(u.slot())).isEqualTo(1);
    assertThat(u.w().status(u.schedule())).isEqualTo("CANCELLED");
  }

  @Test
  void aSupervisorCheckInAndAReplacementSerializeInEitherOrder() throws Exception {
    // Replacement first (Ben takes the shift): the old slot is not operational; the same 404.
    Team t = team(3);
    List<Map<String, Object>> slots = List.of(shift("r", t.day(), 8, 16, t.ben().employee()));
    beforeStart(t);
    String digest = t.w().replacementDigest(t.schedule(), slots, "Renfort");
    assertThat(
            queued(
                t.w(),
                schedulingLock(t.w()),
                List.of(
                    replace(
                        t.w().admin,
                        Organizations.newKey(),
                        t.schedule(),
                        slots,
                        "Renfort",
                        digest),
                    teamCheckIn(t.mia(), Organizations.newKey(), t.slot()))))
        .containsExactly("201", "404 TEAM_ATTENDANCE_ACTION_NOT_FOUND");
    assertThat(records(t.slot())).isZero();

    // Check-in first: the replacement proceeds after it and the evidence stays.
    clock.reset();
    Team u = team(4);
    List<Map<String, Object>> others = List.of(shift("r", u.day(), 8, 16, u.ben().employee()));
    beforeStart(u);
    String other = u.w().replacementDigest(u.schedule(), others, "Renfort");
    List<String> codes =
        queued(
            u.w(),
            schedulingLock(u.w()),
            List.of(
                teamCheckIn(u.mia(), Organizations.newKey(), u.slot()),
                replace(
                    u.w().admin, Organizations.newKey(), u.schedule(), others, "Renfort", other)));
    assertThat(codes).containsExactly("201", "201");
    assertThat(records(u.slot())).isEqualTo(1);
    assertThat(actions(u.slot())).isEqualTo(1);
  }

  @Test
  void aSupervisorCheckInAndASubstitutionApprovalSerializeInEitherOrder() throws Exception {
    // Approval first: Ben covers the shift; Mia (his manager too) now records Ben, not Ana.
    Team t = team(3);
    String first =
        t.w().expect(request(t.ana(), Organizations.newKey(), t.slot()), 201).get("id").asText();
    beforeStart(t);
    List<String> codes =
        queued(
            t.w(),
            schedulingLock(t.w()),
            List.of(
                approve(t.mia(), first, Organizations.newKey(), t.ben().employee()),
                teamCheckIn(t.mia(), Organizations.newKey(), t.slot())));
    assertThat(codes).containsExactly("200", "201");
    assertThat(
            jdbc.queryForObject(
                "SELECT employee_id FROM people.attendance_record WHERE slot_id = ?::uuid",
                UUID.class,
                t.slot()))
        .isEqualTo(t.ben().employee());

    // Check-in first: Ana's evidence is kept and Ben now covers the shift.
    clock.reset();
    Team u = team(4);
    String second =
        u.w().expect(request(u.ana(), Organizations.newKey(), u.slot()), 201).get("id").asText();
    beforeStart(u);
    assertThat(
            queued(
                u.w(),
                schedulingLock(u.w()),
                List.of(
                    teamCheckIn(u.mia(), Organizations.newKey(), u.slot()),
                    approve(u.mia(), second, Organizations.newKey(), u.ben().employee()))))
        .containsExactly("201", "200");
    assertThat(
            jdbc.queryForObject(
                "SELECT employee_id FROM people.attendance_record WHERE slot_id = ?::uuid",
                UUID.class,
                u.slot()))
        .isEqualTo(u.ana().employee());
  }

  // ------------------------------------------------------------------------------------------
  // Atomicity
  // ------------------------------------------------------------------------------------------

  /**
   * Fails one step of a supervisor check-in, checks that nothing of it remains (no event, action,
   * record, audit record or outbox event), then checks in again with the same key.
   */
  private void rolledBack(Consumer<String> fail, Runnable heal, String step) throws Exception {
    Team t = team(3);
    beforeStart(t);
    String key = Organizations.newKey();
    fail.accept(step);
    try {
      assertThat(t.w().call(teamCheckIn(t.mia(), key, t.slot())).getStatus()).isEqualTo(500);
    } finally {
      heal.run();
    }
    assertThat(records(t.slot())).isZero();
    assertThat(actions(t.slot())).isZero();
    assertThat(
            count("SELECT count(*) FROM people.attendance_event WHERE slot_id = ?::uuid", t.slot()))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?"
                    + " AND action = 'attendance.supervisor-check-in'",
                t.w().tenant()))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM platform.outbox_event WHERE envelope ->> 'tenantId' = ? AND"
                    + " envelope ->> 'eventType' = 'people.attendance.supervisor-checked-in.v1'",
                t.w().tenant().toString()))
        .isZero();
    t.w().expect(teamCheckIn(t.mia(), key, t.slot()), 201);
    assertThat(records(t.slot())).isEqualTo(1);
    assertThat(actions(t.slot())).isEqualTo(1);
  }

  @Test
  void anOutboxFailureRollsTheWholeSupervisorCheckInBack() throws Exception {
    OutboxWriter target = AopTestUtils.getUltimateTargetObject(outbox);
    rolledBack(
        type ->
            doThrow(new IllegalStateException("simulated outbox failure"))
                .when(target)
                .append(
                    argThat(
                        (EventEnvelope event) -> event != null && type.equals(event.eventType()))),
        () -> {
          org.mockito.Mockito.reset(target);
          doCallRealMethod().when(target).append(any(EventEnvelope.class));
        },
        "people.attendance.supervisor-checked-in.v1");
  }

  @Test
  void anAuditFailureRollsTheWholeSupervisorCheckInBack() throws Exception {
    AuditRecorder target = AopTestUtils.getUltimateTargetObject(audit);
    rolledBack(
        action ->
            doThrow(new IllegalStateException("simulated audit failure"))
                .when(target)
                .record(
                    argThat((AuditEvent event) -> event != null && action.equals(event.action()))),
        () -> {
          org.mockito.Mockito.reset(target);
          doCallRealMethod().when(target).record(any(AuditEvent.class));
        },
        "attendance.supervisor-check-in");
  }

  @Test
  void anIdempotencyCompletionFailureRollsTheWholeSupervisorCheckInBack() throws Exception {
    IdempotencyService target = AopTestUtils.getUltimateTargetObject(idempotency);
    rolledBack(
        operation ->
            doThrow(new IllegalStateException("simulated completion failure"))
                .when(target)
                .complete(
                    argThat(
                        (IdempotencyScope scope) ->
                            scope != null && operation.equals(scope.operation())),
                    any(StoredResponse.class)),
        () -> {
          org.mockito.Mockito.reset(target);
          doCallRealMethod()
              .when(target)
              .complete(any(IdempotencyScope.class), any(StoredResponse.class));
        },
        "attendance.supervisor-check-in");
  }

  @Test
  void aFailedSupervisorCheckOutLeavesTheRecordOpen() throws Exception {
    OutboxWriter target = AopTestUtils.getUltimateTargetObject(outbox);
    Team t = team(3);
    beforeStart(t);
    String id =
        t.w()
            .expect(teamCheckIn(t.mia(), Organizations.newKey(), t.slot()), 201)
            .get("attendanceRecordId")
            .asText();
    clock.set(t.at()[1]);
    String key = Organizations.newKey();
    doThrow(new IllegalStateException("simulated outbox failure"))
        .when(target)
        .append(
            argThat(
                (EventEnvelope event) ->
                    event != null
                        && "people.attendance.supervisor-checked-out.v1"
                            .equals(event.eventType())));
    try {
      assertThat(t.w().call(teamCheckOut(t.mia(), key, id)).getStatus()).isEqualTo(500);
    } finally {
      org.mockito.Mockito.reset(target);
      doCallRealMethod().when(target).append(any(EventEnvelope.class));
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM people.attendance_record WHERE id = ?::uuid", String.class, id))
        .isEqualTo("OPEN");
    assertThat(count("SELECT count(*) FROM people.attendance_event WHERE record_id = ?::uuid", id))
        .isEqualTo(1);
    assertThat(actions(t.slot())).isEqualTo(1);
    t.w().expect(teamCheckOut(t.mia(), key, id), 200);
    assertThat(actions(t.slot())).isEqualTo(2);
  }
}
