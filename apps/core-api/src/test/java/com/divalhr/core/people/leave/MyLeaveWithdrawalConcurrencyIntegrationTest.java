package com.divalhr.core.people.leave;

import static com.divalhr.core.people.leave.LeaveWorld.ADMIN_APPROVALS;
import static com.divalhr.core.people.leave.LeaveWorld.JSON;
import static com.divalhr.core.people.leave.LeaveWorld.amend;
import static com.divalhr.core.people.leave.LeaveWorld.amendment;
import static com.divalhr.core.people.leave.LeaveWorld.cancel;
import static com.divalhr.core.people.leave.LeaveWorld.cancellation;
import static com.divalhr.core.people.leave.LeaveWorld.decide;
import static com.divalhr.core.people.leave.LeaveWorld.decision;
import static com.divalhr.core.people.leave.LeaveWorld.withdraw;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.people.leave.LeaveWorld.Person;
import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.idempotency.IdempotencyScope;
import com.divalhr.core.platform.idempotency.IdempotencyService;
import com.divalhr.core.platform.idempotency.StoredResponse;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
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
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-041F concurrency and atomicity against real PostgreSQL (D41F-4; AC3, AC6): identical retries
 * queued behind the request row or behind its employment replay one receipt over one evidence row;
 * racing withdrawals under different keys give one withdrawal; a withdrawal and a cancellation,
 * amendment or decision of one request serialize on its row in either order with one valid outcome;
 * the released dates cannot be used before the withdrawal commits; an outbox, audit or
 * idempotency-completion failure rolls the state, the evidence and the release back together.
 */
@IntegrationTest
class MyLeaveWithdrawalConcurrencyIntegrationTest {

  // Under the per-subject leave-request-write limit (10 per minute) with the setup request.
  private static final int PARALLEL = 8;

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private DataSource dataSource;
  @MockitoSpyBean private OutboxWriter outbox;
  @MockitoSpyBean private AuditRecorder audit;
  @MockitoSpyBean private IdempotencyService idempotency;

  @AfterEach
  void resetSpies() {
    reset((OutboxWriter) AopTestUtils.getUltimateTargetObject(outbox));
    reset((AuditRecorder) AopTestUtils.getUltimateTargetObject(audit));
    reset((IdempotencyService) AopTestUtils.getUltimateTargetObject(idempotency));
  }

  private static List<MockHttpServletResponse> parallel(
      LeaveWorld w, List<MockHttpServletRequestBuilder> requests) throws Exception {
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

  private static String code(MockHttpServletResponse response) throws Exception {
    return response.getStatus() < 300
        ? Integer.toString(response.getStatus())
        : response.getStatus()
            + " "
            + JSON.readTree(response.getContentAsString()).get("code").asText();
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

  private static void run(Connection c, String sql, Object... args) throws Exception {
    try (PreparedStatement statement = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        statement.setObject(i + 1, args[i]);
      }
      statement.execute();
    }
  }

  /**
   * Holds one row with {@code lock} (SQL with the tenant and the row id), queues {@code first},
   * then {@code second}, releases the row, and returns both responses in queue order.
   */
  private List<MockHttpServletResponse> queued(
      LeaveWorld w,
      String lock,
      Object rowId,
      MockHttpServletRequestBuilder first,
      MockHttpServletRequestBuilder second)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try (Connection blocker = dataSource.getConnection()) {
      blocker.setAutoCommit(false);
      run(blocker, lock, w.tenant(), rowId);
      Future<MockHttpServletResponse> one = pool.submit(() -> w.call(first));
      awaitWaiting(1);
      Future<MockHttpServletResponse> two = pool.submit(() -> w.call(second));
      awaitWaiting(2);
      blocker.rollback();
      return List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
  }

  private static final String REQUEST_ROW =
      "SELECT 1 FROM people.leave_request WHERE tenant_id = ? AND id = ?::uuid FOR UPDATE";
  private static final String EMPLOYMENT_ROW =
      "SELECT 1 FROM people.employment WHERE tenant_id = ? AND id = ? FOR UPDATE";

  private String approved(LeaveWorld w, Person p, String policy, LocalDate day) throws Exception {
    String id = w.request(p, policy, day, day);
    w.approveAsAdmin(id);
    return id;
  }

  private UUID employment(String requestId) {
    return jdbc.queryForObject(
        "SELECT employment_id FROM people.leave_request WHERE id = ?::uuid", UUID.class, requestId);
  }

  // ------------------------------------------------------------------------------------------
  // Retried and racing withdrawals
  // ------------------------------------------------------------------------------------------

  @Test
  void identicalRetriesQueuedBehindTheRequestOrTheEmploymentReplayOneReceipt() throws Exception {
    LeaveWorld w = new LeaveWorld(mvc, jdbc, lookups);
    String central = w.policy("ADM", "TENANT_ADMIN");
    Person p = w.person("Bénédicte", "Mbuyi");
    String behindRequest = approved(w, p, central, w.today.plusDays(5));
    String behindEmployment = approved(w, p, central, w.today.plusDays(9));
    Map<String, Object> body = cancellation("fr", "Plus besoin.");
    for (String[] order :
        List.of(
            new String[] {REQUEST_ROW, behindRequest},
            new String[] {EMPLOYMENT_ROW, behindEmployment})) {
      String id = order[1];
      String key = Organizations.newKey();
      Object row = order[0].equals(REQUEST_ROW) ? id : employment(id);
      List<MockHttpServletResponse> both =
          queued(
              w,
              order[0],
              row,
              withdraw(p.bearer(), id, key, body),
              withdraw(p.bearer(), id, key, body));
      assertThat(both).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(201));
      assertThat(both.get(0).getHeader("Idempotent-Replayed")).isNull();
      assertThat(both.get(1).getHeader("Idempotent-Replayed")).isEqualTo("true");
      assertThat(JSON.readTree(both.get(1).getContentAsString()))
          .isEqualTo(JSON.readTree(both.get(0).getContentAsString()));
      assertThat(
              w.count(
                  "SELECT count(*) FROM people.leave_request_withdrawal WHERE request_id = ?::uuid",
                  id))
          .isEqualTo(1);
    }
    assertThat(w.withdrawals()).containsExactly(2, 2, 2);
  }

  @Test
  void identicalConcurrentRetriesReplayOneReceipt() throws Exception {
    LeaveWorld w = new LeaveWorld(mvc, jdbc, lookups);
    String central = w.policy("ADM", "TENANT_ADMIN");
    Person p = w.person("Bénédicte", "Mbuyi");
    String id = approved(w, p, central, w.today.plusDays(5));
    String key = Organizations.newKey();
    List<MockHttpServletRequestBuilder> attempts = new ArrayList<>();
    for (int i = 0; i < PARALLEL; i++) {
      attempts.add(withdraw(p.bearer(), id, key, cancellation("fr", "Plus besoin.")));
    }
    List<JsonNode> receipts = new ArrayList<>();
    int replayed = 0;
    for (MockHttpServletResponse response : parallel(w, attempts)) {
      assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
      receipts.add(JSON.readTree(response.getContentAsString()));
      if ("true".equals(response.getHeader("Idempotent-Replayed"))) {
        replayed++;
      }
    }
    assertThat(receipts.stream().distinct()).hasSize(1);
    assertThat(replayed).isEqualTo(PARALLEL - 1);
    assertThat(w.withdrawals()).containsExactly(1, 1, 1);
  }

  @Test
  void racingWithdrawalsUnderDifferentKeysLeaveOne() throws Exception {
    LeaveWorld w = new LeaveWorld(mvc, jdbc, lookups);
    String central = w.policy("ADM", "TENANT_ADMIN");
    Person p = w.person("Bénédicte", "Mbuyi");
    String id = approved(w, p, central, w.today.plusDays(5));
    List<MockHttpServletRequestBuilder> attempts = new ArrayList<>();
    for (int i = 0; i < PARALLEL; i++) {
      attempts.add(
          withdraw(p.bearer(), id, Organizations.newKey(), cancellation("en", "Attempt " + i)));
    }
    List<String> outcomes = new ArrayList<>();
    for (MockHttpServletResponse response : parallel(w, attempts)) {
      outcomes.add(code(response));
    }
    assertThat(outcomes).filteredOn("201"::equals).hasSize(1);
    assertThat(outcomes)
        .filteredOn(o -> !o.equals("201"))
        .hasSize(PARALLEL - 1)
        .containsOnly("409 LEAVE_REQUEST_ALREADY_WITHDRAWN");
    assertThat(w.withdrawals()).containsExactly(1, 1, 1);
  }

  // ------------------------------------------------------------------------------------------
  // A withdrawal racing a cancellation, an amendment or a decision (step 5, the request row)
  // ------------------------------------------------------------------------------------------

  @Test
  void aWithdrawalNeverResurrectsOrRacesPastACancellationAmendmentOrDecision() throws Exception {
    LeaveWorld w = new LeaveWorld(mvc, jdbc, lookups);
    LocalDate t = w.today;
    String central = w.policy("ADM", "TENANT_ADMIN");
    Person p = w.person("Bénédicte", "Mbuyi");
    Person q = w.person("Aline", "Tshibanda");
    Map<String, Object> body = cancellation("fr", "Plus besoin.");

    // Approved: the withdrawal queued first wins; the cancellation and amendment then see it.
    String first = approved(w, p, central, t.plusDays(5));
    List<MockHttpServletResponse> one =
        queued(
            w,
            REQUEST_ROW,
            first,
            withdraw(p.bearer(), first, Organizations.newKey(), body),
            cancel(p.bearer(), first, Organizations.newKey(), body));
    assertThat(code(one.get(0))).isEqualTo("201");
    assertThat(code(one.get(1))).isEqualTo("409 LEAVE_REQUEST_ALREADY_WITHDRAWN");
    // Approved: a cancellation or amendment queued first is refused (approved), then it withdraws.
    String second = approved(w, p, central, t.plusDays(9));
    List<MockHttpServletResponse> two =
        queued(
            w,
            REQUEST_ROW,
            second,
            amend(
                p.bearer(),
                second,
                Organizations.newKey(),
                amendment(central, t.plusDays(12), t.plusDays(12), 1, "fr", "Autres dates.")),
            withdraw(p.bearer(), second, Organizations.newKey(), body));
    assertThat(code(two.get(0))).isEqualTo("409 LEAVE_REQUEST_ALREADY_DECIDED");
    assertThat(code(two.get(1))).isEqualTo("201");

    // Pending: a withdrawal queued before the approval is refused; the approval then commits.
    String pending = w.request(q, central, t.plusDays(15), t.plusDays(15));
    List<MockHttpServletResponse> three =
        queued(
            w,
            REQUEST_ROW,
            pending,
            withdraw(q.bearer(), pending, Organizations.newKey(), body),
            decide(
                ADMIN_APPROVALS,
                w.admin,
                pending,
                Organizations.newKey(),
                decision("APPROVED", "fr", "Accordé.")));
    assertThat(code(three.get(0))).isEqualTo("409 LEAVE_REQUEST_NOT_APPROVED");
    assertThat(code(three.get(1))).isEqualTo("200");
    // A rejection queued before the withdrawal: the withdrawal observes REJECTED.
    String rejected = w.request(q, central, t.plusDays(20), t.plusDays(20));
    List<MockHttpServletResponse> four =
        queued(
            w,
            REQUEST_ROW,
            rejected,
            decide(
                ADMIN_APPROVALS,
                w.admin,
                rejected,
                Organizations.newKey(),
                decision("REJECTED", "en", "Not this week.")),
            withdraw(q.bearer(), rejected, Organizations.newKey(), body));
    assertThat(code(four.get(0))).isEqualTo("200");
    assertThat(code(four.get(1))).isEqualTo("409 LEAVE_REQUEST_ALREADY_DECIDED");

    assertThat(w.state(first)).isEqualTo("WITHDRAWN");
    assertThat(w.state(second)).isEqualTo("WITHDRAWN");
    assertThat(w.state(pending)).isEqualTo("APPROVED");
    assertThat(w.state(rejected)).isEqualTo("REJECTED");
    assertThat(w.withdrawals()).containsExactly(2, 2, 2);
    assertThat(w.cancellations()).containsExactly(0, 0, 0);
    assertThat(w.amendments()).containsExactly(0, 0, 0);
    assertThat(w.decisions()).containsExactly(4, 4, 4);
  }

  // ------------------------------------------------------------------------------------------
  // The released dates: unavailable before the commit, available after it
  // ------------------------------------------------------------------------------------------

  @Test
  void theDatesAreReleasedOnlyWhenTheWithdrawalCommits() throws Exception {
    LeaveWorld w = new LeaveWorld(mvc, jdbc, lookups);
    LocalDate day = w.today.plusDays(5);
    String central = w.policy("ADM", "TENANT_ADMIN");
    Person p = w.person("Bénédicte", "Mbuyi");
    String id = approved(w, p, central, day);
    for (boolean commit : List.of(false, true)) {
      ExecutorService pool = Executors.newSingleThreadExecutor();
      try (Connection withdrawal = dataSource.getConnection()) {
        withdrawal.setAutoCommit(false);
        // The withdrawal's own writes, uncommitted (the application's statements, in order).
        run(
            withdrawal,
            "INSERT INTO people.leave_request_withdrawal (id, tenant_id, request_id,"
                + " reason_locale, reason_text, withdrawn_at, withdrawn_by)"
                + " VALUES (gen_random_uuid(), ?, ?::uuid, 'fr', 'Plus besoin.', now(), 'sub-x')",
            w.tenant(),
            id);
        run(
            withdrawal,
            "UPDATE people.leave_request SET state = 'WITHDRAWN' WHERE tenant_id = ?"
                + " AND id = ?::uuid",
            w.tenant(),
            id);
        // A new request for the same dates waits for the withdrawal's outcome.
        Future<MockHttpServletResponse> create =
            pool.submit(() -> w.call(LeaveWorld.submit(p.bearer(), central, day, day)));
        awaitWaiting(1);
        assertThat(create.isDone()).isFalse();
        if (commit) {
          withdrawal.commit();
          assertThat(code(create.get(30, TimeUnit.SECONDS))).isEqualTo("201");
        } else {
          withdrawal.rollback();
          assertThat(code(create.get(30, TimeUnit.SECONDS))).isEqualTo("409 LEAVE_REQUEST_OVERLAP");
          assertThat(w.state(id)).isEqualTo("APPROVED");
        }
      } finally {
        pool.shutdownNow();
      }
    }
    assertThat(w.state(id)).isEqualTo("WITHDRAWN");
  }

  // ------------------------------------------------------------------------------------------
  // Atomicity
  // ------------------------------------------------------------------------------------------

  @Test
  void anOutboxFailureRollsTheWholeWithdrawalBack() throws Exception {
    OutboxWriter target = AopTestUtils.getUltimateTargetObject(outbox);
    rolledBack(
        w ->
            doThrow(new IllegalStateException("simulated outbox failure"))
                .when(target)
                .append(
                    argThat(
                        (EventEnvelope event) ->
                            event != null
                                && "people.leave-request.withdrawn.v1".equals(event.eventType()))),
        () -> {
          reset(target);
          doCallRealMethod().when(target).append(any(EventEnvelope.class));
        });
  }

  @Test
  void anAuditFailureRollsTheWholeWithdrawalBack() throws Exception {
    AuditRecorder target = AopTestUtils.getUltimateTargetObject(audit);
    rolledBack(
        w ->
            doThrow(new IllegalStateException("simulated audit failure"))
                .when(target)
                .record(
                    argThat(
                        (AuditEvent event) ->
                            event != null && "leave-request.withdraw".equals(event.action()))),
        () -> {
          reset(target);
          doCallRealMethod().when(target).record(any(AuditEvent.class));
        });
  }

  @Test
  void anIdempotencyCompletionFailureRollsTheWholeWithdrawalBack() throws Exception {
    IdempotencyService target = AopTestUtils.getUltimateTargetObject(idempotency);
    rolledBack(
        w ->
            doThrow(new IllegalStateException("simulated completion failure"))
                .when(target)
                .complete(
                    argThat(
                        (IdempotencyScope scope) ->
                            scope != null
                                && "leave-request.self-withdraw".equals(scope.operation())),
                    any(StoredResponse.class)),
        () -> {
          reset(target);
          doCallRealMethod()
              .when(target)
              .complete(any(IdempotencyScope.class), any(StoredResponse.class));
        });
  }

  private void rolledBack(Consumer<LeaveWorld> fail, Runnable restore) throws Exception {
    LeaveWorld w = new LeaveWorld(mvc, jdbc, lookups);
    String central = w.policy("ADM", "TENANT_ADMIN");
    Person p = w.person("Bénédicte", "Mbuyi");
    String id = approved(w, p, central, w.today.plusDays(5));
    fail.accept(w);
    String key = Organizations.newKey();
    Map<String, Object> body = cancellation("fr", "Mes projets ont changé.");
    assertThat(w.call(withdraw(p.bearer(), id, key, body)).getStatus()).isEqualTo(500);
    restore.run();
    assertThat(w.state(id)).isEqualTo("APPROVED");
    assertThat(w.withdrawals()).containsExactly(0, 0, 0);
    assertThat(
            w.count(
                "SELECT count(*) FROM platform.idempotency_record WHERE idempotency_key = ?", key))
        .isZero();
    // The dates were not released: the employee still cannot request them.
    w.problem(
        LeaveWorld.submit(p.bearer(), central, w.today.plusDays(5), w.today.plusDays(5)),
        409,
        "LEAVE_REQUEST_OVERLAP");
    MockHttpServletResponse retried = w.call(withdraw(p.bearer(), id, key, body));
    assertThat(retried.getStatus()).isEqualTo(201);
    assertThat(retried.getHeader("Idempotent-Replayed")).isNull();
    assertThat(w.state(id)).isEqualTo("WITHDRAWN");
    assertThat(w.withdrawals()).containsExactly(1, 1, 1);
  }
}
