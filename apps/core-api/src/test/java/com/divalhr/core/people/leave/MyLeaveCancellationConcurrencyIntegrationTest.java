package com.divalhr.core.people.leave;

import static com.divalhr.core.people.leave.LeaveWorld.ADMIN_APPROVALS;
import static com.divalhr.core.people.leave.LeaveWorld.JSON;
import static com.divalhr.core.people.leave.LeaveWorld.MY_APPROVALS;
import static com.divalhr.core.people.leave.LeaveWorld.cancel;
import static com.divalhr.core.people.leave.LeaveWorld.cancellation;
import static com.divalhr.core.people.leave.LeaveWorld.decide;
import static com.divalhr.core.people.leave.LeaveWorld.decision;
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
 * MVP-041C concurrency and atomicity against real PostgreSQL (D41C-4; AC2, AC4): a cancellation and
 * a manager or tenant-administrator decision of one request serialize on its row in either order
 * with exactly one terminal state and one matching evidence row, without deadlock; identical
 * retries replay one receipt; racing cancellations under different keys give one cancellation; an
 * outbox or audit failure rolls the cancellation, the transition, the release of the dates and the
 * idempotency key back together.
 */
@IntegrationTest
class MyLeaveCancellationConcurrencyIntegrationTest {

  // Under the per-subject leave-request-write limit (10 per minute) with the setup request.
  private static final int PARALLEL = 8;

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private DataSource dataSource;
  @MockitoSpyBean private OutboxWriter outbox;
  @MockitoSpyBean private AuditRecorder audit;

  @AfterEach
  void resetSpies() {
    reset((OutboxWriter) AopTestUtils.getUltimateTargetObject(outbox));
    reset((AuditRecorder) AopTestUtils.getUltimateTargetObject(audit));
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
    return response.getStatus() == 200
        ? "200"
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

  private static void lockRow(Connection c, String sql, Object... args) throws Exception {
    try (PreparedStatement statement = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        statement.setObject(i + 1, args[i]);
      }
      statement.executeQuery().close();
    }
  }

  /**
   * Holds the request row, queues {@code first}, then {@code second}, releases the row, and returns
   * both responses in queue order.
   */
  private List<MockHttpServletResponse> queued(
      LeaveWorld w,
      String requestId,
      MockHttpServletRequestBuilder first,
      MockHttpServletRequestBuilder second)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try (Connection blocker = dataSource.getConnection()) {
      blocker.setAutoCommit(false);
      lockRow(
          blocker,
          "SELECT 1 FROM people.leave_request WHERE tenant_id = ? AND id = ?::uuid FOR UPDATE",
          w.tenant(),
          requestId);
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

  // ------------------------------------------------------------------------------------------
  // A cancellation racing a decision (step 5, the request row)
  // ------------------------------------------------------------------------------------------

  @Test
  void aCancellationAndAManagerDecisionSerializeInEitherOrder() throws Exception {
    LeaveWorld w = new LeaveWorld(mvc, jdbc, lookups);
    LocalDate t = w.today;
    String managed = w.policy("MGR", "MANAGER");
    Person manager = w.person("Josué", "Kabeya");
    Person report = w.person("Bénédicte", "Mbuyi");
    w.manage(report.employee(), manager.employee(), t);
    String cancelledFirst = w.request(report, managed, t.plusDays(5), t.plusDays(5));
    String decidedFirst = w.request(report, managed, t.plusDays(9), t.plusDays(9));
    Map<String, Object> approve = decision("APPROVED", "fr", "Accordé.");
    Map<String, Object> withdraw = cancellation("fr", "Mes dates ont changé.");

    // (1) The cancellation queues first: it commits; the decision observes CANCELLED.
    List<MockHttpServletResponse> first =
        queued(
            w,
            cancelledFirst,
            cancel(report.bearer(), cancelledFirst, Organizations.newKey(), withdraw),
            decide(
                MY_APPROVALS, manager.bearer(), cancelledFirst, Organizations.newKey(), approve));
    assertThat(code(first.get(0))).isEqualTo("200");
    assertThat(code(first.get(1))).isEqualTo("409 LEAVE_REQUEST_ALREADY_DECIDED");
    assertThat(w.state(cancelledFirst)).isEqualTo("CANCELLED");

    // (2) The decision queues first: it commits; the cancellation observes APPROVED.
    List<MockHttpServletResponse> second =
        queued(
            w,
            decidedFirst,
            decide(MY_APPROVALS, manager.bearer(), decidedFirst, Organizations.newKey(), approve),
            cancel(report.bearer(), decidedFirst, Organizations.newKey(), withdraw));
    assertThat(code(second.get(0))).isEqualTo("200");
    assertThat(code(second.get(1))).isEqualTo("409 LEAVE_REQUEST_ALREADY_DECIDED");
    assertThat(w.state(decidedFirst)).isEqualTo("APPROVED");
    // Exactly one terminal state and one matching evidence row each; no orphan record.
    assertThat(w.cancellations()).containsExactly(1, 1, 1);
    assertThat(w.decisions()).containsExactly(1, 1, 1);
    assertEvidence(w, cancelledFirst, 0, 1);
    assertEvidence(w, decidedFirst, 1, 0);
  }

  @Test
  void aCancellationAndATenantAdministratorDecisionSerializeInEitherOrder() throws Exception {
    LeaveWorld w = new LeaveWorld(mvc, jdbc, lookups);
    LocalDate t = w.today;
    String central = w.policy("ADM", "TENANT_ADMIN");
    Person report = w.person("Bénédicte", "Mbuyi");
    String cancelledFirst = w.request(report, central, t.plusDays(5), t.plusDays(5));
    String decidedFirst = w.request(report, central, t.plusDays(9), t.plusDays(9));
    Map<String, Object> reject = decision("REJECTED", "en", "Not this week.");
    Map<String, Object> withdraw = cancellation("en", "Plans changed.");

    List<MockHttpServletResponse> first =
        queued(
            w,
            cancelledFirst,
            cancel(report.bearer(), cancelledFirst, Organizations.newKey(), withdraw),
            decide(ADMIN_APPROVALS, w.admin, cancelledFirst, Organizations.newKey(), reject));
    assertThat(code(first.get(0))).isEqualTo("200");
    assertThat(code(first.get(1))).isEqualTo("409 LEAVE_REQUEST_ALREADY_DECIDED");
    List<MockHttpServletResponse> second =
        queued(
            w,
            decidedFirst,
            decide(ADMIN_APPROVALS, w.admin, decidedFirst, Organizations.newKey(), reject),
            cancel(report.bearer(), decidedFirst, Organizations.newKey(), withdraw));
    assertThat(code(second.get(0))).isEqualTo("200");
    assertThat(code(second.get(1))).isEqualTo("409 LEAVE_REQUEST_ALREADY_DECIDED");
    assertThat(w.state(cancelledFirst)).isEqualTo("CANCELLED");
    assertThat(w.state(decidedFirst)).isEqualTo("REJECTED");
    assertThat(w.cancellations()).containsExactly(1, 1, 1);
    assertThat(w.decisions()).containsExactly(1, 1, 1);
    assertEvidence(w, cancelledFirst, 0, 1);
    assertEvidence(w, decidedFirst, 1, 0);
  }

  /** The request's decision and cancellation rows. */
  private static void assertEvidence(LeaveWorld w, String requestId, int decisions, int cancels) {
    assertThat(
            w.count(
                "SELECT count(*) FROM people.leave_request_decision WHERE request_id = ?::uuid",
                requestId))
        .isEqualTo(decisions);
    assertThat(
            w.count(
                "SELECT count(*) FROM people.leave_request_cancellation WHERE request_id = ?::uuid",
                requestId))
        .isEqualTo(cancels);
  }

  // ------------------------------------------------------------------------------------------
  // Retried and racing cancellations
  // ------------------------------------------------------------------------------------------

  @Test
  void identicalConcurrentRetriesReplayOneReceipt() throws Exception {
    LeaveWorld w = new LeaveWorld(mvc, jdbc, lookups);
    String central = w.policy("ADM", "TENANT_ADMIN");
    Person report = w.person("Bénédicte", "Mbuyi");
    String id = w.request(report, central, w.today.plusDays(5), w.today.plusDays(5));
    String key = Organizations.newKey();
    List<MockHttpServletRequestBuilder> attempts = new ArrayList<>();
    for (int i = 0; i < PARALLEL; i++) {
      attempts.add(cancel(report.bearer(), id, key, cancellation("fr", "Plus besoin.")));
    }
    List<JsonNode> receipts = new ArrayList<>();
    int replayed = 0;
    for (MockHttpServletResponse response : parallel(w, attempts)) {
      assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
      receipts.add(JSON.readTree(response.getContentAsString()));
      if ("true".equals(response.getHeader("Idempotent-Replayed"))) {
        replayed++;
      }
    }
    assertThat(receipts.stream().distinct()).hasSize(1);
    assertThat(replayed).isEqualTo(PARALLEL - 1);
    assertThat(w.cancellations()).containsExactly(1, 1, 1);
  }

  @Test
  void racingCancellationsUnderDifferentKeysLeaveOne() throws Exception {
    LeaveWorld w = new LeaveWorld(mvc, jdbc, lookups);
    String central = w.policy("ADM", "TENANT_ADMIN");
    Person report = w.person("Bénédicte", "Mbuyi");
    String id = w.request(report, central, w.today.plusDays(5), w.today.plusDays(5));
    List<MockHttpServletRequestBuilder> attempts = new ArrayList<>();
    for (int i = 0; i < PARALLEL; i++) {
      attempts.add(
          cancel(report.bearer(), id, Organizations.newKey(), cancellation("en", "Attempt " + i)));
    }
    List<String> outcomes = new ArrayList<>();
    for (MockHttpServletResponse response : parallel(w, attempts)) {
      outcomes.add(code(response));
    }
    assertThat(outcomes).filteredOn("200"::equals).hasSize(1);
    assertThat(outcomes)
        .filteredOn(o -> !o.equals("200"))
        .hasSize(PARALLEL - 1)
        .containsOnly("409 LEAVE_REQUEST_ALREADY_CANCELLED");
    assertThat(w.cancellations()).containsExactly(1, 1, 1);
  }

  // ------------------------------------------------------------------------------------------
  // Atomicity
  // ------------------------------------------------------------------------------------------

  @Test
  void anOutboxFailureRollsTheWholeCancellationBack() throws Exception {
    OutboxWriter target = AopTestUtils.getUltimateTargetObject(outbox);
    rolledBack(
        w ->
            doThrow(new IllegalStateException("simulated outbox failure"))
                .when(target)
                .append(any(EventEnvelope.class)),
        () -> reset(target));
  }

  @Test
  void anAuditFailureRollsTheWholeCancellationBack() throws Exception {
    AuditRecorder target = AopTestUtils.getUltimateTargetObject(audit);
    rolledBack(
        w ->
            doThrow(new IllegalStateException("simulated audit failure"))
                .when(target)
                .record(
                    argThat(
                        (AuditEvent event) ->
                            event != null && "leave-request.cancel".equals(event.action()))),
        () -> {
          reset(target);
          doCallRealMethod().when(target).record(any(AuditEvent.class));
        });
  }

  private void rolledBack(Consumer<LeaveWorld> fail, Runnable restore) throws Exception {
    LeaveWorld w = new LeaveWorld(mvc, jdbc, lookups);
    String central = w.policy("ADM", "TENANT_ADMIN");
    Person report = w.person("Bénédicte", "Mbuyi");
    String id = w.request(report, central, w.today.plusDays(5), w.today.plusDays(5));
    fail.accept(w);
    String key = Organizations.newKey();
    Map<String, Object> body = cancellation("fr", "Mes dates ont changé.");
    assertThat(w.call(cancel(report.bearer(), id, key, body)).getStatus()).isEqualTo(500);
    restore.run();
    assertThat(w.state(id)).isEqualTo("PENDING");
    assertThat(w.cancellations()).containsExactly(0, 0, 0);
    assertThat(
            w.count(
                "SELECT count(*) FROM platform.idempotency_record WHERE idempotency_key = ?", key))
        .isZero();
    // The dates were not released: the employee still cannot request them.
    w.problem(
        LeaveWorld.submit(report.bearer(), central, w.today.plusDays(5), w.today.plusDays(5)),
        409,
        "LEAVE_REQUEST_OVERLAP");
    MockHttpServletResponse retried = w.call(cancel(report.bearer(), id, key, body));
    assertThat(retried.getStatus()).isEqualTo(200);
    assertThat(retried.getHeader("Idempotent-Replayed")).isNull();
    assertThat(w.state(id)).isEqualTo("CANCELLED");
    assertThat(w.cancellations()).containsExactly(1, 1, 1);
  }
}
