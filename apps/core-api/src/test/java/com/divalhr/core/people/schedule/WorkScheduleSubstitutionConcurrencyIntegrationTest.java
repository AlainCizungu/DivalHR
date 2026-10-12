package com.divalhr.core.people.schedule;

import static com.divalhr.core.people.schedule.ScheduleWorld.JSON;
import static com.divalhr.core.people.schedule.ScheduleWorld.cancel;
import static com.divalhr.core.people.schedule.ScheduleWorld.proposal;
import static com.divalhr.core.people.schedule.ScheduleWorld.replace;
import static com.divalhr.core.people.schedule.ScheduleWorld.shift;
import static com.divalhr.core.people.schedule.SubstitutionCalls.approve;
import static com.divalhr.core.people.schedule.SubstitutionCalls.history;
import static com.divalhr.core.people.schedule.SubstitutionCalls.reject;
import static com.divalhr.core.people.schedule.SubstitutionCalls.request;
import static com.divalhr.core.people.schedule.SubstitutionCalls.slotIds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MVP-051 concurrency and atomicity against real PostgreSQL (D51-7, D51-10 items 15 and 17): racing
 * requests and racing decisions settle to exactly one; an approval serializes with a cancellation,
 * a replacement, a manager change, a placement change and a separation in both orders, each order
 * producing one valid outcome; two approvals choosing one substitute for overlapping shifts settle
 * to one; an outbox, audit or idempotency-completion failure rolls the whole decision back, and the
 * same key then succeeds.
 */
@IntegrationTest
class WorkScheduleSubstitutionConcurrencyIntegrationTest {

  private static final int PARALLEL = 8;

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private JdbcWorkScheduleRepository schedules;
  @Autowired private ManagerGraphLock graph;
  @Autowired private PlatformTransactionManager transactions;
  @MockitoSpyBean private OutboxWriter outbox;
  @MockitoSpyBean private AuditRecorder audit;
  @MockitoSpyBean private IdempotencyService idempotency;

  @AfterEach
  void resetSpies() {
    reset((OutboxWriter) AopTestUtils.getUltimateTargetObject(outbox));
    reset((AuditRecorder) AopTestUtils.getUltimateTargetObject(audit));
    reset((IdempotencyService) AopTestUtils.getUltimateTargetObject(idempotency));
  }

  /** A team: Mia manages Ana and Ben; Ana works 08-16 on the day. */
  private record Team(
      ScheduleWorld w,
      Person mia,
      Person ana,
      Person ben,
      LocalDate day,
      String schedule,
      String slot) {}

  private Team team(int inDays) throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person mia = w.person("Mia", "Mbuyi");
    Person ana = w.person("Ana", "Kalala");
    Person ben = w.person("Ben", "Ilunga");
    w.manageAll(mia, ana, ben);
    LocalDate day = w.today.plusDays(inDays);
    String schedule =
        w.published(proposal(w.site(), day, day, List.of(shift("a", day, 8, 16, ana.employee()))));
    return new Team(w, mia, ana, ben, day, schedule, slotIds(w, schedule).get(0));
  }

  private static String requested(Team t) throws Exception {
    return t.w().expect(request(t.ana(), Organizations.newKey(), t.slot()), 201).get("id").asText();
  }

  private static String code(MockHttpServletResponse response) throws Exception {
    int status = response.getStatus();
    return status == 200 || status == 201
        ? Integer.toString(status)
        : status + " " + JSON.readTree(response.getContentAsString()).get("code").asText();
  }

  private UUID coverage(String slot) {
    List<UUID> found =
        jdbc.queryForList(
            "SELECT employee_id FROM people.work_schedule_slot_coverage WHERE slot_id = ?::uuid",
            UUID.class,
            slot);
    return found.isEmpty() ? null : found.get(0);
  }

  private int decisions(String request) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM people.work_schedule_substitution_decision"
                + " WHERE request_id = ?::uuid",
            Integer.class,
            request);
    return n == null ? 0 : n;
  }

  private String state(Team t) throws Exception {
    return t.w().expect(history(t.ana().bearer()), 200).get("items").get(0).get("state").asText();
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

  private Runnable employmentLock(UUID employee) {
    return () ->
        jdbc.query(
            "SELECT id FROM people.employment WHERE employee_id = ? FOR UPDATE",
            (rs, n) -> rs.getObject("id"),
            employee);
  }

  // ------------------------------------------------------------------------------------------
  // Racing requests and decisions
  // ------------------------------------------------------------------------------------------

  @Test
  void racingRequestsAndRacingDecisionsSettleToExactlyOne() throws Exception {
    Team t = team(5);
    ScheduleWorld w = t.w();
    List<MockHttpServletRequestBuilder> requests = new ArrayList<>();
    for (int i = 0; i < PARALLEL; i++) {
      requests.add(request(t.ana(), Organizations.newKey(), t.slot()));
    }
    List<String> codes = parallel(w, requests);
    assertThat(codes).filteredOn("201"::equals).hasSize(1);
    assertThat(codes)
        .filteredOn(c -> !"201".equals(c))
        .allMatch("409 WORK_SCHEDULE_SUBSTITUTION_ALREADY_PENDING"::equals);
    String id =
        jdbc.queryForObject(
            "SELECT id::text FROM people.work_schedule_substitution_request WHERE slot_id ="
                + " ?::uuid",
            String.class,
            t.slot());

    List<MockHttpServletRequestBuilder> decisions = new ArrayList<>();
    for (int i = 0; i < PARALLEL; i++) {
      decisions.add(
          i % 2 == 0
              ? approve(t.mia(), id, Organizations.newKey(), t.ben().employee())
              : reject(t.mia(), id, Organizations.newKey()));
    }
    codes = parallel(w, decisions);
    assertThat(codes).filteredOn("200"::equals).hasSize(1);
    assertThat(codes)
        .filteredOn(c -> !"200".equals(c))
        .allMatch("409 WORK_SCHEDULE_SUBSTITUTION_ALREADY_DECIDED"::equals);
    assertThat(decisions(id)).isEqualTo(1);
    String state = state(t);
    assertThat(coverage(t.slot()))
        .isEqualTo("APPROVED".equals(state) ? t.ben().employee() : t.ana().employee());
  }

  @Test
  void twoApprovalsChoosingOneSubstituteForOverlappingShiftsSettleToOne() throws Exception {
    Team t = team(5);
    ScheduleWorld w = t.w();
    Person cleo = w.person("Cléo", "Banza");
    w.manage(cleo.employee(), t.mia().employee(), w.today);
    // Two shifts of one later day, 08-12 and 11-15, given away to Ben at the same time.
    LocalDate g = t.day().plusDays(1);
    String overlapping =
        w.published(
            proposal(
                w.site(),
                g,
                g,
                List.of(
                    shift("a", g, 8, 12, t.ana().employee()),
                    shift("c", g, 11, 15, cleo.employee()))));
    List<String> slots = slotIds(w, overlapping);
    String a =
        w.expect(request(t.ana(), Organizations.newKey(), slots.get(0)), 201).get("id").asText();
    String c =
        w.expect(request(cleo, Organizations.newKey(), slots.get(1)), 201).get("id").asText();
    List<String> codes =
        parallel(
            w,
            List.of(
                approve(t.mia(), a, Organizations.newKey(), t.ben().employee()),
                approve(t.mia(), c, Organizations.newKey(), t.ben().employee())));
    assertThat(codes).containsExactlyInAnyOrder("200", "409 WORK_SCHEDULE_SUBSTITUTE_CONFLICT");
    assertThat(
            w.count(
                "SELECT count(*) FROM people.work_schedule_slot_coverage WHERE schedule_id ="
                    + " ?::uuid AND employee_id = ?",
                overlapping,
                t.ben().employee()))
        .isEqualTo(1);
  }

  // ------------------------------------------------------------------------------------------
  // Approval against schedule changes
  // ------------------------------------------------------------------------------------------

  @Test
  void anApprovalAndACancellationSerializeInEitherOrder() throws Exception {
    // Approval first: the cancellation then releases the substituted coverage.
    Team t = team(5);
    ScheduleWorld w = t.w();
    String id = requested(t);
    MockHttpServletRequestBuilder approval =
        approve(t.mia(), id, Organizations.newKey(), t.ben().employee());
    MockHttpServletRequestBuilder cancellation =
        cancel(w.admin, Organizations.newKey(), t.schedule(), "Fermeture du site");
    assertThat(queued(w, schedulingLock(w), List.of(approval, cancellation)))
        .containsExactly("200", "200");
    assertThat(state(t)).isEqualTo("APPROVED");
    assertThat(coverage(t.slot())).isNull();

    // Cancellation first: the approval finds the request voided.
    Team u = team(6);
    String other = requested(u);
    approval = approve(u.mia(), other, Organizations.newKey(), u.ben().employee());
    cancellation = cancel(u.w().admin, Organizations.newKey(), u.schedule(), "Fermeture du site");
    assertThat(queued(u.w(), schedulingLock(u.w()), List.of(cancellation, approval)))
        .containsExactly("200", "409 WORK_SCHEDULE_SUBSTITUTION_NOT_AVAILABLE");
    assertThat(state(u)).isEqualTo("VOIDED");
    assertThat(decisions(other)).isZero();
  }

  @Test
  void anApprovalAndAReplacementSerializeInEitherOrder() throws Exception {
    Team t = team(5);
    ScheduleWorld w = t.w();
    String id = requested(t);
    List<java.util.Map<String, Object>> slots =
        List.of(shift("r", t.day(), 9, 13, t.ana().employee()));
    MockHttpServletRequestBuilder replacement =
        replace(
            w.admin,
            Organizations.newKey(),
            t.schedule(),
            slots,
            "Réorganisation",
            w.replacementDigest(t.schedule(), slots, "Réorganisation"));
    MockHttpServletRequestBuilder approval =
        approve(t.mia(), id, Organizations.newKey(), t.ben().employee());
    assertThat(queued(w, schedulingLock(w), List.of(approval, replacement)))
        .containsExactly("200", "201");
    assertThat(state(t)).isEqualTo("APPROVED");
    assertThat(coverage(t.slot())).isNull();

    Team u = team(6);
    String other = requested(u);
    slots = List.of(shift("r", u.day(), 9, 13, u.ana().employee()));
    replacement =
        replace(
            u.w().admin,
            Organizations.newKey(),
            u.schedule(),
            slots,
            "Réorganisation",
            u.w().replacementDigest(u.schedule(), slots, "Réorganisation"));
    approval = approve(u.mia(), other, Organizations.newKey(), u.ben().employee());
    assertThat(queued(u.w(), schedulingLock(u.w()), List.of(replacement, approval)))
        .containsExactly("201", "409 WORK_SCHEDULE_SUBSTITUTION_NOT_AVAILABLE");
    assertThat(state(u)).isEqualTo("VOIDED");
  }

  // ------------------------------------------------------------------------------------------
  // Approval against employment history
  // ------------------------------------------------------------------------------------------

  @Test
  void anApprovalAndAManagerChangeSerializeInEitherOrder() throws Exception {
    // Approval first: decided by Mia, then the line moves.
    Team t = team(5);
    ScheduleWorld w = t.w();
    Person noa = w.person("Noa", "Tshala");
    String id = requested(t);
    MockHttpServletRequestBuilder approval =
        approve(t.mia(), id, Organizations.newKey(), t.ben().employee());
    MockHttpServletRequestBuilder move =
        w.change(t.ana().employee(), w.managerCommand(noa.employee(), t.day().minusDays(1)));
    assertThat(queued(w, graphLock(w), List.of(approval, move))).containsExactly("200", "201");
    assertThat(state(t)).isEqualTo("APPROVED");

    // Manager change first: Mia is no longer the manager; the same 404.
    Team u = team(6);
    Person zoe = u.w().person("Zoe", "Tshala");
    String other = requested(u);
    approval = approve(u.mia(), other, Organizations.newKey(), u.ben().employee());
    move =
        u.w()
            .change(u.ana().employee(), u.w().managerCommand(zoe.employee(), u.day().minusDays(1)));
    assertThat(queued(u.w(), graphLock(u.w()), List.of(move, approval)))
        .containsExactly("201", "404 WORK_SCHEDULE_SUBSTITUTION_NOT_FOUND");
    assertThat(decisions(other)).isZero();
  }

  @Test
  void anApprovalAndTheSubstitutesPlacementChangeOrSeparationSerializeInEitherOrder()
      throws Exception {
    // Approval first, then Ben moves: both succeed (the decision was valid when taken).
    Team t = team(5);
    ScheduleWorld w = t.w();
    String id = requested(t);
    MockHttpServletRequestBuilder approval =
        approve(t.mia(), id, Organizations.newKey(), t.ben().employee());
    MockHttpServletRequestBuilder move =
        w.change(
            t.ben().employee(),
            w.placement(w.otherSite, w.otherSiteDepartment, t.day().minusDays(1)));
    assertThat(queued(w, employmentLock(t.ben().employee()), List.of(approval, move)))
        .containsExactly("200", "201");
    assertThat(coverage(t.slot())).isEqualTo(t.ben().employee());

    // Move first: the approval re-reads Ben's placement and refuses him.
    Team u = team(6);
    String other = requested(u);
    move =
        u.w()
            .change(
                u.ben().employee(),
                u.w().placement(u.w().otherSite, u.w().otherSiteDepartment, u.day().minusDays(1)));
    approval = approve(u.mia(), other, Organizations.newKey(), u.ben().employee());
    assertThat(queued(u.w(), employmentLock(u.ben().employee()), List.of(move, approval)))
        .containsExactly("201", "422 WORK_SCHEDULE_SUBSTITUTE_NOT_ELIGIBLE");
    assertThat(coverage(u.slot())).isEqualTo(u.ana().employee());

    // Separation first: likewise refused.
    Team v = team(7);
    String third = requested(v);
    MockHttpServletRequestBuilder leave = v.w().separate(v.ben().employee(), v.day().minusDays(1));
    approval = approve(v.mia(), third, Organizations.newKey(), v.ben().employee());
    assertThat(queued(v.w(), employmentLock(v.ben().employee()), List.of(leave, approval)))
        .containsExactly("201", "422 WORK_SCHEDULE_SUBSTITUTE_NOT_ELIGIBLE");
    assertThat(coverage(v.slot())).isEqualTo(v.ana().employee());
  }

  // ------------------------------------------------------------------------------------------
  // Atomicity
  // ------------------------------------------------------------------------------------------

  /**
   * Fails one step of an approval, checks that nothing of it remains (the request still pending,
   * the original coverage in place, no audit record or event), then approves again with the key.
   */
  private void rolledBack(Consumer<String> fail, Runnable heal, String step) throws Exception {
    Team t = team(5);
    ScheduleWorld w = t.w();
    String id = requested(t);
    String key = Organizations.newKey();
    fail.accept(step);
    try {
      assertThat(w.call(approve(t.mia(), id, key, t.ben().employee())).getStatus()).isEqualTo(500);
    } finally {
      heal.run();
    }
    assertThat(decisions(id)).isZero();
    assertThat(coverage(t.slot())).isEqualTo(t.ana().employee());
    assertThat(state(t)).isEqualTo("PENDING");
    assertThat(
            w.count(
                "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?"
                    + " AND action = 'work-schedule-substitution.approve'",
                w.tenant()))
        .isZero();
    assertThat(
            w.count(
                "SELECT count(*) FROM platform.outbox_event WHERE envelope ->> 'tenantId' = ?"
                    + " AND envelope ->> 'eventType' ="
                    + " 'people.work-schedule-substitution.approved.v1'",
                w.tenant().toString()))
        .isZero();
    w.expect(approve(t.mia(), id, key, t.ben().employee()), 200);
    assertThat(coverage(t.slot())).isEqualTo(t.ben().employee());
  }

  @Test
  void anOutboxFailureRollsTheWholeApprovalBack() throws Exception {
    OutboxWriter target = AopTestUtils.getUltimateTargetObject(outbox);
    rolledBack(
        type ->
            doThrow(new IllegalStateException("simulated outbox failure"))
                .when(target)
                .append(
                    argThat(
                        (EventEnvelope event) -> event != null && type.equals(event.eventType()))),
        () -> {
          reset(target);
          doCallRealMethod().when(target).append(any(EventEnvelope.class));
        },
        "people.work-schedule-substitution.approved.v1");
  }

  @Test
  void anAuditFailureRollsTheWholeApprovalBack() throws Exception {
    AuditRecorder target = AopTestUtils.getUltimateTargetObject(audit);
    rolledBack(
        action ->
            doThrow(new IllegalStateException("simulated audit failure"))
                .when(target)
                .record(
                    argThat((AuditEvent event) -> event != null && action.equals(event.action()))),
        () -> {
          reset(target);
          doCallRealMethod().when(target).record(any(AuditEvent.class));
        },
        "work-schedule-substitution.approve");
  }

  @Test
  void anIdempotencyCompletionFailureRollsTheWholeApprovalBack() throws Exception {
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
          reset(target);
          doCallRealMethod()
              .when(target)
              .complete(any(IdempotencyScope.class), any(StoredResponse.class));
        },
        "work-schedule-substitution.decide");
  }

  @Test
  void aFailedRequestLeavesNothingBehind() throws Exception {
    OutboxWriter target = AopTestUtils.getUltimateTargetObject(outbox);
    Team t = team(5);
    ScheduleWorld w = t.w();
    String key = Organizations.newKey();
    doThrow(new IllegalStateException("simulated outbox failure"))
        .when(target)
        .append(
            argThat(
                (EventEnvelope event) ->
                    event != null
                        && "people.work-schedule-substitution.requested.v1"
                            .equals(event.eventType())));
    try {
      assertThat(w.call(request(t.ana(), key, t.slot())).getStatus()).isEqualTo(500);
    } finally {
      reset(target);
      doCallRealMethod().when(target).append(any(EventEnvelope.class));
    }
    assertThat(
            w.count(
                "SELECT count(*) FROM people.work_schedule_substitution_request WHERE tenant_id"
                    + " = ?",
                w.tenant()))
        .isZero();
    w.expect(request(t.ana(), key, t.slot()), 201);
  }
}
