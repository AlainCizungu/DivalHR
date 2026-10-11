package com.divalhr.core.people.schedule;

import static com.divalhr.core.people.schedule.ScheduleWorld.JSON;
import static com.divalhr.core.people.schedule.ScheduleWorld.cancel;
import static com.divalhr.core.people.schedule.ScheduleWorld.proposal;
import static com.divalhr.core.people.schedule.ScheduleWorld.publish;
import static com.divalhr.core.people.schedule.ScheduleWorld.replace;
import static com.divalhr.core.people.schedule.ScheduleWorld.shift;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MVP-050B concurrency and atomicity against real PostgreSQL (D50B-8): a cancellation and a
 * replacement of one schedule, and two replacements, settle to exactly one terminal action and one
 * successor; a replacement and an ordinary publication assigning one employee at overlapping times
 * settle to one; a replacement against a placement change and a separation serializes in both
 * orders; an outbox, audit or idempotency-completion failure leaves the original schedule
 * published, operational and visible, and the same key then succeeds.
 */
@IntegrationTest
class WorkScheduleLifecycleConcurrencyIntegrationTest {

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

  /** A previewed replacement of the schedule, as a builder. */
  private static MockHttpServletRequestBuilder replacing(
      ScheduleWorld w, String id, List<Map<String, Object>> slots, String reason) throws Exception {
    return replace(
        w.admin, Organizations.newKey(), id, slots, reason, w.replacementDigest(id, slots, reason));
  }

  private static int terminal(ScheduleWorld w, String id) {
    return w.count(
            "SELECT count(*) FROM people.work_schedule_cancellation WHERE schedule_id = ?::uuid",
            id)
        + w.count(
            "SELECT count(*) FROM people.work_schedule_replacement WHERE schedule_id = ?::uuid",
            id);
  }

  // ------------------------------------------------------------------------------------------
  // Terminal action against terminal action
  // ------------------------------------------------------------------------------------------

  @Test
  void aCancellationAndAReplacementSettleToOneTerminalActionInEitherOrder() throws Exception {
    ScheduleWorld w = world();
    LocalDate d = w.today.plusDays(4);
    String a = w.published(proposal(w.site(), d, d, List.of(shift("a", d, 8, 12, null))));
    MockHttpServletRequestBuilder replacement =
        replacing(w, a, List.of(shift("b", d, 9, 13, null)), "Réorganisation");
    MockHttpServletRequestBuilder cancellation =
        cancel(w.admin, Organizations.newKey(), a, "Fermeture du site");
    assertThat(queued(w, schedulingLock(w), List.of(cancellation, replacement)))
        .containsExactly("200", "409 WORK_SCHEDULE_ALREADY_CANCELLED");
    assertThat(terminal(w, a)).isEqualTo(1);
    assertThat(w.status(a)).isEqualTo("CANCELLED");

    LocalDate e = d.plusDays(1);
    String b = w.published(proposal(w.site(), e, e, List.of(shift("a", e, 8, 12, null))));
    replacement = replacing(w, b, List.of(shift("b", e, 9, 13, null)), "Réorganisation");
    cancellation = cancel(w.admin, Organizations.newKey(), b, "Fermeture du site");
    assertThat(queued(w, schedulingLock(w), List.of(replacement, cancellation)))
        .containsExactly("201", "409 WORK_SCHEDULE_ALREADY_REPLACED");
    assertThat(terminal(w, b)).isEqualTo(1);
    assertThat(w.status(b)).isEqualTo("REPLACED");
  }

  @Test
  void concurrentReplacementsProduceOneSuccessor() throws Exception {
    ScheduleWorld w = world();
    LocalDate d = w.today.plusDays(5);
    String a = w.published(proposal(w.site(), d, d, List.of(shift("a", d, 8, 12, null))));
    List<MockHttpServletRequestBuilder> racing = new ArrayList<>();
    for (int i = 0; i < PARALLEL; i++) {
      racing.add(replacing(w, a, List.of(shift("r" + i, d, 9 + i % 3, 14, null)), "Variante " + i));
    }
    List<String> codes = new ArrayList<>();
    for (MockHttpServletResponse response : parallel(w, racing)) {
      codes.add(code(response));
    }
    assertThat(codes).filteredOn("201"::equals).hasSize(1);
    assertThat(codes)
        .filteredOn(c -> !"201".equals(c))
        .allMatch("409 WORK_SCHEDULE_ALREADY_REPLACED"::equals);
    assertThat(terminal(w, a)).isEqualTo(1);
    assertThat(
            w.count(
                "SELECT count(*) FROM people.work_schedule_site_coverage WHERE tenant_id = ?",
                w.tenant()))
        .isEqualTo(1);
    assertThat(w.rows()).containsExactly(2, 2);
  }

  // ------------------------------------------------------------------------------------------
  // Replacement against publication and employment facts
  // ------------------------------------------------------------------------------------------

  /** An overnight replacement slot of Ana's, from 22:00 on the date to 06:00 the next day. */
  private static List<Map<String, Object>> overnight(LocalDate day, Person ana) {
    return List.of(
        ScheduleWorld.slot(
            "n", day + "T22:00", day.plusDays(1) + "T06:00", "Guard", "Gardien", ana.employee()));
  }

  /** A publication assigning Ana from 04:00 to 08:00 the next day, previewed now. */
  private static MockHttpServletRequestBuilder earlyNextDay(
      ScheduleWorld w, LocalDate day, Person ana) throws Exception {
    LocalDate next = day.plusDays(1);
    return previewed(
        w, proposal(w.site(), next, next, List.of(shift("o", next, 4, 8, ana.employee()))));
  }

  @Test
  void aReplacementAndAPublicationAssigningOneEmployeeSettleToOneInEitherOrder() throws Exception {
    ScheduleWorld w = world();
    Person ana = w.person("Ana", "Tshala");
    // Replacement first: its overnight shift holds Ana; the publication is refused.
    LocalDate d = w.today.plusDays(6);
    String a = w.published(proposal(w.site(), d, d, List.of(shift("a", d, 8, 12, null))));
    MockHttpServletRequestBuilder replacement =
        replacing(w, a, overnight(d, ana), "Veille ajoutée");
    MockHttpServletRequestBuilder publication = earlyNextDay(w, d, ana);
    assertThat(queued(w, schedulingLock(w), List.of(replacement, publication)))
        .containsExactly("201", "409 WORK_SCHEDULE_EMPLOYEE_CONFLICT");
    assertThat(w.status(a)).isEqualTo("REPLACED");

    // Publication first: the replacement is refused and the original stays operational.
    LocalDate e = d.plusDays(4);
    String b = w.published(proposal(w.site(), e, e, List.of(shift("b", e, 8, 12, null))));
    replacement = replacing(w, b, overnight(e, ana), "Veille ajoutée");
    publication = earlyNextDay(w, e, ana);
    assertThat(queued(w, schedulingLock(w), List.of(publication, replacement)))
        .containsExactly("201", "409 WORK_SCHEDULE_EMPLOYEE_CONFLICT");
    assertThat(w.status(b)).isEqualTo("PUBLISHED");
    assertThat(
            w.count(
                "SELECT count(*) FROM people.work_schedule_site_coverage WHERE schedule_id ="
                    + " ?::uuid",
                b))
        .isEqualTo(1);
  }

  @Test
  void aReplacementAndAPlacementChangeSerializeInEitherOrder() throws Exception {
    ScheduleWorld w = world();
    LocalDate d = w.today.plusDays(8);
    Person ana = w.person("Ana", "Mujinga");
    String a = w.published(proposal(w.site(), d, d, List.of(shift("a", d, 8, 12, null))));
    // Replacement first: published with the facts it reviewed; the change then applies.
    MockHttpServletRequestBuilder replacement =
        replacing(w, a, List.of(shift("r", d, 9, 13, ana.employee())), "Renfort");
    MockHttpServletRequestBuilder move =
        w.change(ana.employee(), w.placement(w.otherSite, w.otherSiteDepartment, d.minusDays(1)));
    assertThat(queued(w, employmentLock(ana.employee()), List.of(replacement, move)))
        .containsExactly("201", "201");
    assertThat(w.status(a)).isEqualTo("REPLACED");

    // Change first: the replacement waits, reads the new placement and is refused; the original
    // stays published.
    Person ben = w.person("Ben", "Kasongo");
    LocalDate e = d.plusDays(1);
    String b = w.published(proposal(w.site(), e, e, List.of(shift("b", e, 8, 12, null))));
    replacement = replacing(w, b, List.of(shift("r", e, 9, 13, ben.employee())), "Renfort");
    move =
        w.change(ben.employee(), w.placement(w.otherSite, w.otherSiteDepartment, e.minusDays(1)));
    assertThat(queued(w, employmentLock(ben.employee()), List.of(move, replacement)))
        .containsExactly("201", "422 WORK_SCHEDULE_EMPLOYEE_NOT_ELIGIBLE");
    assertThat(w.status(b)).isEqualTo("PUBLISHED");

    // A separation first, likewise.
    Person cleo = w.person("Cléo", "Banza");
    LocalDate f = e.plusDays(1);
    String c = w.published(proposal(w.site(), f, f, List.of(shift("c", f, 8, 12, null))));
    replacement = replacing(w, c, List.of(shift("r", f, 9, 13, cleo.employee())), "Renfort");
    MockHttpServletRequestBuilder leave = w.separate(cleo.employee(), f.minusDays(1));
    assertThat(queued(w, employmentLock(cleo.employee()), List.of(leave, replacement)))
        .containsExactly("201", "422 WORK_SCHEDULE_EMPLOYEE_NOT_ELIGIBLE");
    assertThat(w.status(c)).isEqualTo("PUBLISHED");
  }

  // ------------------------------------------------------------------------------------------
  // Atomicity
  // ------------------------------------------------------------------------------------------

  /**
   * Fails the replacement's step, checks that the original is still published, operational and
   * visible and that nothing of the replacement was written, then replaces again with the same key.
   */
  private void rolledBack(Consumer<String> fail, Runnable heal, String step) throws Exception {
    ScheduleWorld w = world();
    Person ana = w.person("Ana", "Kabedi");
    LocalDate d = w.today.plusDays(5);
    String a = w.published(proposal(w.site(), d, d, List.of(shift("a", d, 8, 12, ana.employee()))));
    List<Map<String, Object>> slots = List.of(shift("b", d, 13, 17, ana.employee()));
    String digest = w.replacementDigest(a, slots, "Réorganisation");
    String key = Organizations.newKey();
    fail.accept(step);
    try {
      assertThat(w.call(replace(w.admin, key, a, slots, "Réorganisation", digest)).getStatus())
          .isEqualTo(500);
    } finally {
      heal.run();
    }
    assertThat(w.status(a)).isEqualTo("PUBLISHED");
    assertThat(w.rows()).containsExactly(1, 1);
    assertThat(terminal(w, a)).isZero();
    assertThat(
            w.count(
                "SELECT count(*) FROM people.work_schedule_site_coverage WHERE schedule_id ="
                    + " ?::uuid",
                a))
        .isEqualTo(1);
    assertThat(
            w.expect(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                            ScheduleWorld.MINE)
                        .header("Authorization", ana.bearer()),
                    200)
                .get("items"))
        .hasSize(1);
    assertThat(
            w.count(
                "SELECT count(*) FROM platform.audit_event WHERE action = 'work-schedule.replace'"
                    + " AND tenant_id = ?",
                w.tenant()))
        .isZero();
    assertThat(
            w.count(
                "SELECT count(*) FROM platform.outbox_event"
                    + " WHERE envelope ->> 'eventType' = 'people.work-schedule.replaced.v1'"
                    + " AND envelope ->> 'tenantId' = ?",
                w.tenant().toString()))
        .isZero();
    w.expect(replace(w.admin, key, a, slots, "Réorganisation", digest), 201);
    assertThat(w.status(a)).isEqualTo("REPLACED");
    assertThat(w.rows()).containsExactly(2, 2);
  }

  @Test
  void anOutboxFailureLeavesTheOriginalOperational() throws Exception {
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
        "people.work-schedule.replaced.v1");
  }

  @Test
  void anAuditFailureLeavesTheOriginalOperational() throws Exception {
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
        "work-schedule.replace");
  }

  @Test
  void anIdempotencyCompletionFailureLeavesTheOriginalOperational() throws Exception {
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
        "work-schedule.replace");
  }

  @Test
  void aFailedCancellationReleasesNothing() throws Exception {
    OutboxWriter target = AopTestUtils.getUltimateTargetObject(outbox);
    ScheduleWorld w = world();
    LocalDate d = w.today.plusDays(4);
    String a = w.published(proposal(w.site(), d, d, List.of(shift("a", d, 8, 12, null))));
    String key = Organizations.newKey();
    doThrow(new IllegalStateException("simulated outbox failure"))
        .when(target)
        .append(
            argThat(
                (EventEnvelope event) ->
                    event != null
                        && "people.work-schedule.cancelled.v1".equals(event.eventType())));
    try {
      assertThat(w.call(cancel(w.admin, key, a, "Fermeture du site")).getStatus()).isEqualTo(500);
    } finally {
      reset(target);
      doCallRealMethod().when(target).append(any(EventEnvelope.class));
    }
    assertThat(w.status(a)).isEqualTo("PUBLISHED");
    assertThat(terminal(w, a)).isZero();
    w.problem(
        ScheduleWorld.preview(
            w.admin, proposal(w.site(), d, d, List.of(shift("z", d, 8, 9, null)))),
        409,
        "WORK_SCHEDULE_OVERLAP");
    w.expect(cancel(w.admin, key, a, "Fermeture du site"), 200);
    assertThat(w.status(a)).isEqualTo("CANCELLED");
  }
}
