package com.divalhr.core.people.schedule;

import static com.divalhr.core.people.schedule.ScheduleWorld.JSON;
import static com.divalhr.core.people.schedule.ScheduleWorld.proposal;
import static com.divalhr.core.people.schedule.ScheduleWorld.publish;
import static com.divalhr.core.people.schedule.ScheduleWorld.shift;
import static com.divalhr.core.people.schedule.ScheduleWorld.slot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * MVP-050A concurrency and atomicity against real PostgreSQL (D50A-6): two publications of one
 * site, and two publications assigning one employee at different sites, each settle to exactly one
 * publication; a publication against a placement change and against a separation, queued in both
 * orders on the employment row, settles to exactly one valid outcome; identical concurrent retries
 * publish one schedule and return one receipt; an outbox, audit or idempotency-completion failure
 * rolls the whole publication back with its key.
 */
@IntegrationTest
class WorkScheduleConcurrencyIntegrationTest {

  private static final int PARALLEL = 8;

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private JdbcWorkScheduleRepository schedules;
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

  private ScheduleWorld world() throws Exception {
    return new ScheduleWorld(mvc, jdbc, lookups);
  }

  private static String code(MockHttpServletResponse response) throws Exception {
    int status = response.getStatus();
    return status == 200 || status == 201
        ? Integer.toString(status)
        : status + " " + JSON.readTree(response.getContentAsString()).get("code").asText();
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

  /**
   * Runs the calls queued, in this order, behind a lock the holder takes, then releases it; returns
   * each call's outcome code, in the same order.
   */
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

  /** Behind the tenant's scheduling lock. */
  private Runnable schedulingLock(ScheduleWorld w) {
    return () -> schedules.lock(new TenantId(w.tenant()));
  }

  /** Behind the employee's employment row, locked as an employment change or separation does. */
  private Runnable employmentLock(UUID employee) {
    return () ->
        jdbc.query(
            "SELECT id FROM people.employment WHERE employee_id = ? FOR UPDATE",
            (rs, n) -> rs.getObject("id"),
            employee);
  }

  private static List<MockHttpServletResponse> parallel(
      ScheduleWorld w, List<MockHttpServletRequestBuilder> requests) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(requests.size());
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
      for (MockHttpServletRequestBuilder request : requests) {
        Callable<MockHttpServletResponse> task =
            () -> {
              start.await();
              return w.call(request);
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

  /** A previewed publication of the proposal, as a builder. */
  private static MockHttpServletRequestBuilder previewed(
      ScheduleWorld w, Map<String, Object> proposal) throws Exception {
    return publish(w.admin, Organizations.newKey(), proposal, w.digest(proposal));
  }

  // ------------------------------------------------------------------------------------------
  // Publication against publication
  // ------------------------------------------------------------------------------------------

  @Test
  void twoPublicationsOfOneSiteSettleToOne() throws Exception {
    ScheduleWorld w = world();
    LocalDate d = w.today.plusDays(4);
    MockHttpServletRequestBuilder first =
        previewed(w, proposal(w.site(), d, d.plusDays(2), List.of(shift("a", d, 8, 12, null))));
    MockHttpServletRequestBuilder second =
        previewed(
            w,
            proposal(
                w.site(),
                d.plusDays(2),
                d.plusDays(4),
                List.of(shift("b", d.plusDays(3), 8, 12, null))));
    assertThat(queued(w, schedulingLock(w), List.of(first, second)))
        .containsExactly("201", "409 WORK_SCHEDULE_OVERLAP");
    assertThat(w.rows()).containsExactly(1, 1);

    // Unordered, many at once: exactly one.
    LocalDate e = w.today.plusDays(20);
    List<MockHttpServletRequestBuilder> racing = new ArrayList<>();
    for (int i = 0; i < PARALLEL; i++) {
      racing.add(
          previewed(
              w,
              proposal(w.site(), e, e.plusDays(i % 3), List.of(shift("r" + i, e, 8, 12, null)))));
    }
    List<String> codes = new ArrayList<>();
    for (MockHttpServletResponse response : parallel(w, racing)) {
      codes.add(code(response));
    }
    assertThat(codes).filteredOn("201"::equals).hasSize(1);
    assertThat(codes)
        .filteredOn(c -> !"201".equals(c))
        .allMatch("409 WORK_SCHEDULE_OVERLAP"::equals);
    assertThat(w.rows()).containsExactly(2, 2);
  }

  @Test
  void twoSitesAssigningOneEmployeeAtOverlappingTimesSettleToOne() throws Exception {
    ScheduleWorld w = world();
    Person ana = w.person("Ana", "Kanku");
    LocalDate d = w.today.plusDays(6);
    // Ana works at the first site until d, then at the other site.
    w.expect(
        w.change(ana.employee(), w.placement(w.otherSite, w.otherSiteDepartment, d.plusDays(1))),
        201);
    MockHttpServletRequestBuilder night =
        previewed(
            w,
            proposal(
                w.site(),
                d,
                d,
                List.of(
                    slot(
                        "n",
                        d + "T22:00",
                        d.plusDays(1) + "T06:00",
                        "Guard",
                        "Gardien",
                        ana.employee()))));
    MockHttpServletRequestBuilder morning =
        previewed(
            w,
            proposal(
                w.otherSite,
                d.plusDays(1),
                d.plusDays(1),
                List.of(shift("m", d.plusDays(1), 5, 9, ana.employee()))));
    assertThat(queued(w, schedulingLock(w), List.of(morning, night)))
        .containsExactly("201", "409 WORK_SCHEDULE_EMPLOYEE_CONFLICT");
    assertThat(w.rows()).containsExactly(1, 1);
    assertThat(
            w.count(
                "SELECT count(*) FROM people.work_schedule_slot WHERE assigned_employee_id = ?",
                ana.employee()))
        .isEqualTo(1);
  }

  // ------------------------------------------------------------------------------------------
  // Publication against employment facts, both orders
  // ------------------------------------------------------------------------------------------

  @Test
  void aPublicationAndAPlacementChangeSerializeInEitherOrder() throws Exception {
    ScheduleWorld w = world();
    LocalDate d = w.today.plusDays(8);
    // Publication first: published with the facts it reviewed; the change then applies.
    Person ana = w.person("Ana", "Mukendi");
    MockHttpServletRequestBuilder publication =
        previewed(w, proposal(w.site(), d, d, List.of(shift("a", d, 8, 12, ana.employee()))));
    MockHttpServletRequestBuilder move =
        w.change(ana.employee(), w.placement(w.otherSite, w.otherSiteDepartment, d.minusDays(1)));
    assertThat(queued(w, employmentLock(ana.employee()), List.of(publication, move)))
        .containsExactly("201", "201");
    assertThat(w.rows()).containsExactly(1, 1);

    // Change first: the publication waits, then reads the new placement and is refused.
    Person ben = w.person("Ben", "Ilunga");
    LocalDate e = d.plusDays(1);
    publication =
        previewed(w, proposal(w.site(), e, e, List.of(shift("b", e, 8, 12, ben.employee()))));
    move =
        w.change(ben.employee(), w.placement(w.otherSite, w.otherSiteDepartment, e.minusDays(1)));
    assertThat(queued(w, employmentLock(ben.employee()), List.of(move, publication)))
        .containsExactly("201", "422 WORK_SCHEDULE_EMPLOYEE_NOT_ELIGIBLE");
    assertThat(w.rows()).containsExactly(1, 1);

    // Change first, still eligible (another department of the site): the preview is stale.
    Person cleo = w.person("Cléo", "Mbuyi");
    publication =
        previewed(w, proposal(w.site(), e, e, List.of(shift("c", e, 8, 12, cleo.employee()))));
    move = w.change(cleo.employee(), w.placement(w.site(), w.secondDepartment, e.minusDays(1)));
    assertThat(queued(w, employmentLock(cleo.employee()), List.of(move, publication)))
        .containsExactly("201", "409 WORK_SCHEDULE_PREVIEW_STALE");
    assertThat(w.rows()).containsExactly(1, 1);
  }

  @Test
  void aPublicationAndASeparationSerializeInEitherOrder() throws Exception {
    ScheduleWorld w = world();
    LocalDate d = w.today.plusDays(9);
    Person ana = w.person("Ana", "Lunda");
    MockHttpServletRequestBuilder publication =
        previewed(w, proposal(w.site(), d, d, List.of(shift("a", d, 8, 12, ana.employee()))));
    MockHttpServletRequestBuilder leave = w.separate(ana.employee(), d.minusDays(1));
    assertThat(queued(w, employmentLock(ana.employee()), List.of(publication, leave)))
        .containsExactly("201", "201");
    assertThat(w.rows()).containsExactly(1, 1);

    Person ben = w.person("Ben", "Kapinga");
    LocalDate e = d.plusDays(1);
    publication =
        previewed(w, proposal(w.site(), e, e, List.of(shift("b", e, 8, 12, ben.employee()))));
    leave = w.separate(ben.employee(), e.minusDays(1));
    assertThat(queued(w, employmentLock(ben.employee()), List.of(leave, publication)))
        .containsExactly("201", "422 WORK_SCHEDULE_EMPLOYEE_NOT_ELIGIBLE");
    assertThat(w.rows()).containsExactly(1, 1);
  }

  // ------------------------------------------------------------------------------------------
  // Retries and atomicity
  // ------------------------------------------------------------------------------------------

  @Test
  void identicalConcurrentRetriesPublishOneScheduleWithOneReceipt() throws Exception {
    ScheduleWorld w = world();
    Person ana = w.person("Ana", "Tshibangu");
    LocalDate d = w.today.plusDays(5);
    Map<String, Object> proposal =
        proposal(
            w.site(),
            d,
            d,
            List.of(shift("a", d, 8, 12, ana.employee()), shift("b", d, 8, 12, null)));
    String digest = w.digest(proposal);
    String key = Organizations.newKey();
    List<MockHttpServletRequestBuilder> retries = new ArrayList<>();
    for (int i = 0; i < PARALLEL; i++) {
      retries.add(publish(w.admin, key, proposal, digest));
    }
    Set<String> bodies = new HashSet<>();
    for (MockHttpServletResponse response : parallel(w, retries)) {
      assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
      bodies.add(response.getContentAsString());
    }
    assertThat(bodies).hasSize(1);
    assertThat(w.rows()).containsExactly(1, 2);
  }

  /**
   * Fails the publication's step, checks that nothing was written (schedule, slots, audit record,
   * event, completed key), then publishes again with the same key.
   */
  private void rolledBack(Consumer<String> fail, Runnable heal, String step) throws Exception {
    ScheduleWorld w = world();
    Person ana = w.person("Ana", "Kabongo");
    LocalDate d = w.today.plusDays(5);
    Map<String, Object> proposal =
        proposal(w.site(), d, d, List.of(shift("a", d, 8, 12, ana.employee())));
    String digest = w.digest(proposal);
    String key = Organizations.newKey();
    fail.accept(step);
    try {
      assertThat(w.call(publish(w.admin, key, proposal, digest)).getStatus()).isEqualTo(500);
    } finally {
      heal.run();
    }
    assertThat(w.rows()).containsExactly(0, 0);
    assertThat(
            w.count(
                "SELECT count(*) FROM platform.audit_event WHERE action = 'work-schedule.publish'"
                    + " AND tenant_id = ?",
                w.tenant()))
        .isZero();
    assertThat(
            w.count(
                "SELECT count(*) FROM platform.outbox_event"
                    + " WHERE envelope ->> 'eventType' = 'people.work-schedule.published.v1'"
                    + " AND envelope ->> 'tenantId' = ?",
                w.tenant().toString()))
        .isZero();
    w.expect(publish(w.admin, key, proposal, digest), 201);
    assertThat(w.rows()).containsExactly(1, 1);
  }

  @Test
  void anOutboxFailureRollsThePublicationBack() throws Exception {
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
        "people.work-schedule.published.v1");
  }

  @Test
  void anAuditFailureRollsThePublicationBack() throws Exception {
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
        "work-schedule.publish");
  }

  @Test
  void anIdempotencyCompletionFailureRollsThePublicationBack() throws Exception {
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
        "work-schedule.publish");
  }
}
