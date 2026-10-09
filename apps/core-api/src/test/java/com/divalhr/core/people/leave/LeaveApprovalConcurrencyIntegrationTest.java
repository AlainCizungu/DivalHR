package com.divalhr.core.people.leave;

import static com.divalhr.core.people.leave.LeaveWorld.ADMIN_APPROVALS;
import static com.divalhr.core.people.leave.LeaveWorld.JSON;
import static com.divalhr.core.people.leave.LeaveWorld.MY_APPROVALS;
import static com.divalhr.core.people.leave.LeaveWorld.decide;
import static com.divalhr.core.people.leave.LeaveWorld.decision;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.people.application.ManagerGraphLock;
import com.divalhr.core.people.leave.LeaveWorld.Person;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import com.divalhr.core.platform.tenancy.TenantId;
import com.divalhr.core.support.Employees;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MVP-041B concurrency and atomicity against real PostgreSQL (D41B-5, AC6): racing decisions give
 * exactly one terminal decision, audit and event; identical retries replay one receipt; a manager
 * change and a separation racing a decision are serialized in either order without deadlock and
 * with an all-or-nothing result; an outbox failure rolls the decision, the transition, the audit
 * and the idempotency key back together.
 */
@IntegrationTest
class LeaveApprovalConcurrencyIntegrationTest {

  private static final int PARALLEL = 8;

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private ManagerGraphLock graph;
  @Autowired private PlatformTransactionManager transactions;
  @Autowired private DataSource dataSource;
  @MockitoSpyBean private OutboxWriter outbox;

  @AfterEach
  void resetSpy() {
    reset((OutboxWriter) AopTestUtils.getUltimateTargetObject(outbox));
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

  // ------------------------------------------------------------------------------------------
  // Racing and retried decisions
  // ------------------------------------------------------------------------------------------

  @Test
  void racingDecisionsLeaveExactlyOneTerminalDecision() throws Exception {
    LeaveWorld w = new LeaveWorld(mvc, jdbc, lookups);
    LocalDate t = w.today;
    String central = w.policy("ADM", "TENANT_ADMIN");
    String managed = w.policy("MGR", "MANAGER");
    Person manager = w.person("Josué", "Kabeya");
    Person report = w.person("Bénédicte", "Mbuyi");
    w.manage(report.employee(), manager.employee(), t.plusDays(1));
    String adminId = w.request(report, central, t.plusDays(5), t.plusDays(5));
    String managerId = w.request(report, managed, t.plusDays(9), t.plusDays(9));

    for (String[] target :
        List.of(
            new String[] {ADMIN_APPROVALS, w.admin, adminId},
            new String[] {MY_APPROVALS, manager.bearer(), managerId})) {
      List<MockHttpServletRequestBuilder> attempts = new ArrayList<>();
      for (int i = 0; i < PARALLEL; i++) {
        attempts.add(
            decide(
                target[0],
                target[1],
                target[2],
                Organizations.newKey(),
                decision(i % 2 == 0 ? "APPROVED" : "REJECTED", "en", "Attempt number " + i)));
      }
      List<String> outcomes = new ArrayList<>();
      for (MockHttpServletResponse response : parallel(w, attempts)) {
        outcomes.add(code(response));
      }
      assertThat(outcomes).filteredOn("200"::equals).hasSize(1);
      assertThat(outcomes)
          .filteredOn(o -> !o.equals("200"))
          .hasSize(PARALLEL - 1)
          .containsOnly("409 LEAVE_REQUEST_ALREADY_DECIDED");
    }
    assertThat(w.decisions()).containsExactly(2, 2, 2);
  }

  @Test
  void identicalConcurrentRetriesReplayOneReceipt() throws Exception {
    LeaveWorld w = new LeaveWorld(mvc, jdbc, lookups);
    String central = w.policy("ADM", "TENANT_ADMIN");
    Person report = w.person("Bénédicte", "Mbuyi");
    String id = w.request(report, central, w.today.plusDays(5), w.today.plusDays(5));
    String key = Organizations.newKey();
    List<MockHttpServletRequestBuilder> attempts = new ArrayList<>();
    for (int i = 0; i < PARALLEL; i++) {
      attempts.add(
          decide(ADMIN_APPROVALS, w.admin, id, key, decision("APPROVED", "fr", "Accordé.")));
    }
    List<MockHttpServletResponse> responses = parallel(w, attempts);
    List<JsonNode> receipts = new ArrayList<>();
    int replayed = 0;
    for (MockHttpServletResponse response : responses) {
      assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
      receipts.add(JSON.readTree(response.getContentAsString()));
      if ("true".equals(response.getHeader("Idempotent-Replayed"))) {
        replayed++;
      }
    }
    assertThat(receipts.stream().distinct()).hasSize(1);
    assertThat(replayed).isEqualTo(PARALLEL - 1);
    assertThat(w.decisions()).containsExactly(1, 1, 1);
  }

  // ------------------------------------------------------------------------------------------
  // A manager change racing a manager decision (step 1, the manager-graph lock)
  // ------------------------------------------------------------------------------------------

  /** Holds the tenant's manager-graph lock (through the People abstraction) until released. */
  private Future<?> holdManagerGraph(TenantId tenant, CountDownLatch held, CountDownLatch release) {
    ExecutorService holder = Executors.newSingleThreadExecutor();
    Future<?> future =
        holder.submit(
            () -> {
              new TransactionTemplate(transactions)
                  .executeWithoutResult(
                      status -> {
                        graph.lock(tenant);
                        held.countDown();
                        try {
                          release.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        }
                      });
              return null;
            });
    holder.shutdown();
    return future;
  }

  @Test
  void aManagerChangeAndAManagerDecisionAreSerializedInEitherOrder() throws Exception {
    LeaveWorld w = new LeaveWorld(mvc, jdbc, lookups);
    LocalDate t = w.today;
    String managed = w.policy("MGR", "MANAGER");
    Person first = w.person("Josué", "Kabeya");
    Person second = w.person("Grâce", "Lukusa");
    Person report = w.person("Bénédicte", "Mbuyi");
    w.manage(report.employee(), first.employee(), t.plusDays(1));
    String changedFirst = w.request(report, managed, t.plusDays(20), t.plusDays(20));
    String decidedFirst = w.request(report, managed, t.plusDays(40), t.plusDays(40));
    TenantId tenant = new TenantId(w.tenant());
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      // (1) The change queues first: the decision then sees the new manager and is refused.
      Map<String, Object> toSecond = change(t.plusDays(10), second);
      JsonNode preview = preview(w, report, toSecond);
      CountDownLatch held = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      Future<?> holder = holdManagerGraph(tenant, held, release);
      assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
      Future<MockHttpServletResponse> changing =
          pool.submit(() -> w.call(w.managerChange(report.employee(), toSecond, preview)));
      awaitWaiting(1);
      Future<MockHttpServletResponse> deciding =
          pool.submit(
              () ->
                  w.call(
                      decide(
                          MY_APPROVALS,
                          first.bearer(),
                          changedFirst,
                          Organizations.newKey(),
                          decision("APPROVED", "en", "Approved before the handover."))));
      awaitWaiting(2);
      release.countDown();
      holder.get(30, TimeUnit.SECONDS);
      assertThat(changing.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(201);
      assertThat(code(deciding.get(30, TimeUnit.SECONDS))).isEqualTo("404 LEAVE_REQUEST_NOT_FOUND");
      assertThat(w.state(changedFirst)).isEqualTo("PENDING");
      assertThat(w.decisions()).containsExactly(0, 0, 0);

      // (2) The decision queues first: it commits under the valid relationship, then the change.
      Map<String, Object> toFirst = change(t.plusDays(30), first);
      JsonNode preview2 = preview(w, report, toFirst);
      CountDownLatch held2 = new CountDownLatch(1);
      CountDownLatch release2 = new CountDownLatch(1);
      // decidedFirst (t + 40) reports to the second manager now; give it back from t + 30 later.
      Future<?> holder2 = holdManagerGraph(tenant, held2, release2);
      assertThat(held2.await(10, TimeUnit.SECONDS)).isTrue();
      Future<MockHttpServletResponse> deciding2 =
          pool.submit(
              () ->
                  w.call(
                      decide(
                          MY_APPROVALS,
                          second.bearer(),
                          decidedFirst,
                          Organizations.newKey(),
                          decision("REJECTED", "fr", "Refusé avant la passation."))));
      awaitWaiting(1);
      Future<MockHttpServletResponse> changing2 =
          pool.submit(() -> w.call(w.managerChange(report.employee(), toFirst, preview2)));
      awaitWaiting(2);
      release2.countDown();
      holder2.get(30, TimeUnit.SECONDS);
      assertThat(code(deciding2.get(30, TimeUnit.SECONDS))).isEqualTo("200");
      assertThat(changing2.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(201);
      assertThat(w.state(decidedFirst)).isEqualTo("REJECTED");
      assertThat(w.decisions()).containsExactly(1, 1, 1);
    } finally {
      pool.shutdownNow();
    }
  }

  private static Map<String, Object> change(LocalDate from, Person manager) {
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("type", "CHANGE");
    command.put("effectiveFrom", from.toString());
    command.put("manager", Map.of("employeeId", manager.employee().toString()));
    return command;
  }

  private static JsonNode preview(LeaveWorld w, Person report, Map<String, Object> command)
      throws Exception {
    return w.expect(
        Employees.postJson(
            w.admin,
            LeaveWorld.EMPLOYEES + "/" + report.employee() + "/employment-changes/preview",
            JSON.writeValueAsString(command)),
        200);
  }

  // ------------------------------------------------------------------------------------------
  // A separation racing an approval (step 2, the employment row)
  // ------------------------------------------------------------------------------------------

  @Test
  void aSeparationAndAnApprovalAreSerializedInEitherOrder() throws Exception {
    LeaveWorld w = new LeaveWorld(mvc, jdbc, lookups);
    LocalDate t = w.today;
    String central = w.policy("ADM", "TENANT_ADMIN");
    Person leaving = w.person("Aline", "Tshibanda");
    Person staying = w.person("Bénédicte", "Mbuyi");
    String cut = w.request(leaving, central, t.plusDays(30), t.plusDays(35));
    String kept = w.request(staying, central, t.plusDays(30), t.plusDays(35));
    Map<String, Object> approve = decision("APPROVED", "en", "Approved for the planned trip.");
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try (Connection blocker = dataSource.getConnection()) {
      blocker.setAutoCommit(false);
      // (1) The separation holds the employment FOR UPDATE (it waits on the link row we hold):
      // the approval waits for it, then reads the separated period and is refused.
      MockHttpServletRequestBuilder separation =
          w.separate(leaving.employee(), w.separation(t.plusDays(32)));
      lockRow(
          blocker,
          "SELECT 1 FROM identity.employee_access_link WHERE tenant_id = ? AND employee_id = ?"
              + " AND unlinked_at IS NULL FOR UPDATE",
          w.tenant(),
          leaving.employee());
      Future<MockHttpServletResponse> separating = pool.submit(() -> w.call(separation));
      awaitWaiting(1);
      Future<MockHttpServletResponse> approving =
          pool.submit(
              () -> w.call(decide(ADMIN_APPROVALS, w.admin, cut, Organizations.newKey(), approve)));
      awaitWaiting(2);
      blocker.rollback();
      assertThat(separating.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(201);
      MockHttpServletResponse refused = approving.get(30, TimeUnit.SECONDS);
      assertThat(code(refused)).isEqualTo("409 LEAVE_REQUEST_NOT_ELIGIBLE");
      assertThat(w.state(cut)).isEqualTo("PENDING");
      assertThat(w.decisions()).containsExactly(0, 0, 0);

      // (2) The approval holds the employment FOR SHARE (it waits on the request row we hold):
      // the separation waits for it; the approval commits on the covering period, then the
      // separation commits.
      MockHttpServletRequestBuilder separation2 =
          w.separate(staying.employee(), w.separation(t.plusDays(60)));
      lockRow(
          blocker,
          "SELECT 1 FROM people.leave_request WHERE tenant_id = ? AND id = ?::uuid FOR UPDATE",
          w.tenant(),
          kept);
      Future<MockHttpServletResponse> approving2 =
          pool.submit(
              () ->
                  w.call(decide(ADMIN_APPROVALS, w.admin, kept, Organizations.newKey(), approve)));
      awaitWaiting(1);
      Future<MockHttpServletResponse> separating2 = pool.submit(() -> w.call(separation2));
      awaitWaiting(2);
      blocker.rollback();
      assertThat(code(approving2.get(30, TimeUnit.SECONDS))).isEqualTo("200");
      assertThat(separating2.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(201);
      assertThat(w.state(kept)).isEqualTo("APPROVED");
      assertThat(w.decisions()).containsExactly(1, 1, 1);
    } finally {
      pool.shutdownNow();
    }
  }

  private static void lockRow(Connection c, String sql, Object... args) throws Exception {
    try (PreparedStatement statement = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        statement.setObject(i + 1, args[i]);
      }
      statement.executeQuery().close();
    }
  }

  // ------------------------------------------------------------------------------------------
  // Atomicity
  // ------------------------------------------------------------------------------------------

  @Test
  void anOutboxFailureRollsTheWholeDecisionBack() throws Exception {
    LeaveWorld w = new LeaveWorld(mvc, jdbc, lookups);
    String central = w.policy("ADM", "TENANT_ADMIN");
    Person report = w.person("Bénédicte", "Mbuyi");
    String id = w.request(report, central, w.today.plusDays(5), w.today.plusDays(5));
    OutboxWriter target = AopTestUtils.getUltimateTargetObject(outbox);
    doThrow(new IllegalStateException("simulated outbox failure"))
        .when(target)
        .append(any(EventEnvelope.class));
    String key = Organizations.newKey();
    Map<String, Object> reject = decision("REJECTED", "en", "Not this week, sorry.");
    assertThat(w.call(decide(ADMIN_APPROVALS, w.admin, id, key, reject)).getStatus())
        .isEqualTo(500);
    assertThat(w.state(id)).isEqualTo("PENDING");
    assertThat(w.decisions()).containsExactly(0, 0, 0);
    assertThat(
            w.count(
                "SELECT count(*) FROM platform.idempotency_record WHERE idempotency_key = ?", key))
        .isZero();
    // The overlap range was not released: the employee still cannot request those dates.
    w.problem(
        LeaveWorld.submit(report.bearer(), central, w.today.plusDays(5), w.today.plusDays(5)),
        409,
        "LEAVE_REQUEST_OVERLAP");
    reset(target);
    MockHttpServletResponse retried = w.call(decide(ADMIN_APPROVALS, w.admin, id, key, reject));
    assertThat(retried.getStatus()).isEqualTo(200);
    assertThat(retried.getHeader("Idempotent-Replayed")).isNull();
    assertThat(w.state(id)).isEqualTo("REJECTED");
    assertThat(w.decisions()).containsExactly(1, 1, 1);
  }
}
