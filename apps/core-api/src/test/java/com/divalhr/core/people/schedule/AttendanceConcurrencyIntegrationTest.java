package com.divalhr.core.people.schedule;

import static com.divalhr.core.people.schedule.AttendanceCalls.checkIn;
import static com.divalhr.core.people.schedule.AttendanceCalls.checkOut;
import static com.divalhr.core.people.schedule.AttendanceCalls.instants;
import static com.divalhr.core.people.schedule.ScheduleWorld.JSON;
import static com.divalhr.core.people.schedule.ScheduleWorld.cancel;
import static com.divalhr.core.people.schedule.ScheduleWorld.proposal;
import static com.divalhr.core.people.schedule.ScheduleWorld.shift;
import static com.divalhr.core.people.schedule.SubstitutionCalls.approve;
import static com.divalhr.core.people.schedule.SubstitutionCalls.request;
import static com.divalhr.core.people.schedule.SubstitutionCalls.slotIds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import com.divalhr.core.identity.application.EmailLookup;
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
 * MVP-060A concurrency and atomicity against real PostgreSQL (A60A-6, A60A-9 items 6 and 10):
 * racing check-ins and racing check-outs settle to one; racing check-ins on two shifts leave one
 * open record; a check-in serializes with a cancellation and with a substitution approval in both
 * orders, each order one valid outcome; an outbox, audit or idempotency-completion failure rolls
 * the whole command back, and the same key then succeeds.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
class AttendanceConcurrencyIntegrationTest {

  // Five and five: within the subject's write limit (10 per minute).
  private static final int PARALLEL = 5;

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;
  @Autowired private JdbcWorkScheduleRepository schedules;
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

  /** Ana works 08-16 on the day; the clock stands one hour before the start. */
  private record Shift(ScheduleWorld w, Person ana, String schedule, String slot, Instant[] at) {}

  private Shift shiftIn(ScheduleWorld w, Person ana, int inDays) throws Exception {
    LocalDate day = w.today.plusDays(inDays);
    String schedule =
        w.published(proposal(w.site(), day, day, List.of(shift("a", day, 8, 16, ana.employee()))));
    String slot = slotIds(w, schedule).get(0);
    return new Shift(w, ana, schedule, slot, instants(jdbc, slot));
  }

  private Shift fresh(int inDays) throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    return shiftIn(w, w.person("Ana", "Kalala"), inDays);
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

  /** Runs the calls queued, in order, behind the tenant's scheduling lock; outcomes in order. */
  private List<String> queued(ScheduleWorld w, List<MockHttpServletRequestBuilder> calls)
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
                          schedules.lock(new TenantId(w.tenant()));
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

  // ------------------------------------------------------------------------------------------
  // Racing commands
  // ------------------------------------------------------------------------------------------

  @Test
  void racingCheckInsAndRacingCheckOutsSettleToExactlyOne() throws Exception {
    Shift s = fresh(3);
    clock.set(s.at()[0].minus(Duration.ofHours(1)));
    List<MockHttpServletRequestBuilder> ins = new ArrayList<>();
    for (int i = 0; i < PARALLEL; i++) {
      ins.add(checkIn(s.ana(), Organizations.newKey(), s.slot()));
    }
    List<String> codes = parallel(s.w(), ins);
    assertThat(codes).filteredOn("201"::equals).hasSize(1);
    assertThat(codes)
        .filteredOn(c -> !"201".equals(c))
        .allMatch("409 ATTENDANCE_ALREADY_CHECKED_IN"::equals);
    assertThat(records(s.slot())).isEqualTo(1);
    String id =
        jdbc.queryForObject(
            "SELECT id::text FROM people.attendance_record WHERE slot_id = ?::uuid",
            String.class,
            s.slot());

    clock.set(s.at()[1]);
    List<MockHttpServletRequestBuilder> outs = new ArrayList<>();
    for (int i = 0; i < PARALLEL; i++) {
      outs.add(checkOut(s.ana(), Organizations.newKey(), id));
    }
    codes = parallel(s.w(), outs);
    assertThat(codes).filteredOn("200"::equals).hasSize(1);
    assertThat(codes)
        .filteredOn(c -> !"200".equals(c))
        .allMatch("409 ATTENDANCE_ALREADY_CHECKED_OUT"::equals);
    assertThat(
            count(
                "SELECT count(*) FROM people.attendance_event WHERE record_id = ?::uuid"
                    + " AND event_type = 'CHECKED_OUT'",
                id))
        .isEqualTo(1);
  }

  @Test
  void racingCheckInsOnTwoShiftsLeaveOneOpenRecord() throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person ana = w.person("Ana", "Kalala");
    LocalDate day = w.today.plusDays(3);
    String schedule =
        w.published(
            proposal(
                w.site(),
                day,
                day,
                List.of(
                    shift("a", day, 8, 10, ana.employee()),
                    shift("b", day, 10, 12, ana.employee()))));
    List<String> slots = slotIds(w, schedule);
    clock.set(instants(jdbc, slots.get(0))[0].plus(Duration.ofMinutes(30)));
    List<String> codes =
        parallel(
            w,
            List.of(
                checkIn(ana, Organizations.newKey(), slots.get(0)),
                checkIn(ana, Organizations.newKey(), slots.get(1))));
    assertThat(codes).containsExactlyInAnyOrder("201", "409 ATTENDANCE_OPEN_RECORD_EXISTS");
    assertThat(
            count(
                "SELECT count(*) FROM people.attendance_record WHERE employee_id = ?",
                ana.employee()))
        .isEqualTo(1);
  }

  // ------------------------------------------------------------------------------------------
  // Check-in against schedule changes
  // ------------------------------------------------------------------------------------------

  @Test
  void aCheckInAndACancellationSerializeInEitherOrder() throws Exception {
    // Cancellation first: the check-in finds no operational shift.
    Shift s = fresh(3);
    clock.set(s.at()[0].minus(Duration.ofHours(1)));
    assertThat(
            queued(
                s.w(),
                List.of(
                    cancel(s.w().admin, Organizations.newKey(), s.schedule(), "Fermeture"),
                    checkIn(s.ana(), Organizations.newKey(), s.slot()))))
        .containsExactly("200", "404 ATTENDANCE_SHIFT_NOT_FOUND");
    assertThat(records(s.slot())).isZero();

    // Check-in first: the cancellation proceeds and the evidence stays.
    Shift t = shiftIn(s.w(), s.ana(), 4);
    clock.set(t.at()[0].minus(Duration.ofHours(1)));
    assertThat(
            queued(
                t.w(),
                List.of(
                    checkIn(t.ana(), Organizations.newKey(), t.slot()),
                    cancel(t.w().admin, Organizations.newKey(), t.schedule(), "Fermeture"))))
        .containsExactly("201", "200");
    assertThat(records(t.slot())).isEqualTo(1);
    assertThat(t.w().status(t.schedule())).isEqualTo("CANCELLED");
  }

  @Test
  void aCheckInAndASubstitutionApprovalSerializeInEitherOrder() throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person mia = w.person("Mia", "Mbuyi");
    Person ana = w.person("Ana", "Kalala");
    Person ben = w.person("Ben", "Ilunga");
    w.manageAll(mia, ana, ben);

    // Approval first: Ana no longer covers the shift.
    Shift s = shiftIn(w, ana, 3);
    String first = w.expect(request(ana, Organizations.newKey(), s.slot()), 201).get("id").asText();
    clock.set(s.at()[0].minus(Duration.ofHours(1)));
    assertThat(
            queued(
                w,
                List.of(
                    approve(mia, first, Organizations.newKey(), ben.employee()),
                    checkIn(ana, Organizations.newKey(), s.slot()))))
        .containsExactly("200", "404 ATTENDANCE_SHIFT_NOT_FOUND");
    assertThat(records(s.slot())).isZero();
    w.expect(checkIn(ben, Organizations.newKey(), s.slot()), 201);

    // Check-in first: the approval proceeds; Ana's evidence is kept, Ben now covers the shift.
    clock.reset();
    Shift t = shiftIn(w, ana, 5);
    String second =
        w.expect(request(ana, Organizations.newKey(), t.slot()), 201).get("id").asText();
    clock.set(t.at()[0].minus(Duration.ofHours(1)));
    assertThat(
            queued(
                w,
                List.of(
                    checkIn(ana, Organizations.newKey(), t.slot()),
                    approve(mia, second, Organizations.newKey(), ben.employee()))))
        .containsExactly("201", "200");
    assertThat(
            jdbc.queryForObject(
                "SELECT employee_id FROM people.attendance_record WHERE slot_id = ?::uuid",
                UUID.class,
                t.slot()))
        .isEqualTo(ana.employee());
    assertThat(
            jdbc.queryForObject(
                "SELECT employee_id FROM people.work_schedule_slot_coverage WHERE slot_id ="
                    + " ?::uuid",
                UUID.class,
                t.slot()))
        .isEqualTo(ben.employee());
  }

  // ------------------------------------------------------------------------------------------
  // Atomicity
  // ------------------------------------------------------------------------------------------

  /**
   * Fails one step of a check-in, checks that nothing of it remains (no event, record, audit record
   * or outbox event), then checks in again with the same key.
   */
  private void rolledBack(Consumer<String> fail, Runnable heal, String step) throws Exception {
    Shift s = fresh(3);
    clock.set(s.at()[0].minus(Duration.ofHours(1)));
    String key = Organizations.newKey();
    fail.accept(step);
    try {
      assertThat(s.w().call(checkIn(s.ana(), key, s.slot())).getStatus()).isEqualTo(500);
    } finally {
      heal.run();
    }
    assertThat(records(s.slot())).isZero();
    assertThat(
            count("SELECT count(*) FROM people.attendance_event WHERE slot_id = ?::uuid", s.slot()))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?"
                    + " AND action = 'attendance.check-in'",
                s.w().tenant()))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM platform.outbox_event WHERE envelope ->> 'tenantId' = ?"
                    + " AND envelope ->> 'eventType' = 'people.attendance.checked-in.v1'",
                s.w().tenant().toString()))
        .isZero();
    s.w().expect(checkIn(s.ana(), key, s.slot()), 201);
    assertThat(records(s.slot())).isEqualTo(1);
  }

  @Test
  void anOutboxFailureRollsTheWholeCheckInBack() throws Exception {
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
        "people.attendance.checked-in.v1");
  }

  @Test
  void anAuditFailureRollsTheWholeCheckInBack() throws Exception {
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
        "attendance.check-in");
  }

  @Test
  void anIdempotencyCompletionFailureRollsTheWholeCheckInBack() throws Exception {
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
        "attendance.check-in");
  }

  @Test
  void aFailedCheckOutLeavesTheRecordOpen() throws Exception {
    OutboxWriter target = AopTestUtils.getUltimateTargetObject(outbox);
    Shift s = fresh(3);
    clock.set(s.at()[0].minus(Duration.ofHours(1)));
    String id =
        s.w()
            .expect(checkIn(s.ana(), Organizations.newKey(), s.slot()), 201)
            .get("attendanceRecordId")
            .asText();
    clock.set(s.at()[1]);
    String key = Organizations.newKey();
    doThrow(new IllegalStateException("simulated outbox failure"))
        .when(target)
        .append(
            argThat(
                (EventEnvelope event) ->
                    event != null && "people.attendance.checked-out.v1".equals(event.eventType())));
    try {
      assertThat(s.w().call(checkOut(s.ana(), key, id)).getStatus()).isEqualTo(500);
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
    s.w().expect(checkOut(s.ana(), key, id), 200);
  }
}
