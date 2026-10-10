package com.divalhr.core.people.leave;

import static com.divalhr.core.people.leave.LeaveWorld.ADMIN_APPROVALS;
import static com.divalhr.core.people.leave.LeaveWorld.EXCEPTIONS;
import static com.divalhr.core.people.leave.LeaveWorld.JSON;
import static com.divalhr.core.people.leave.LeaveWorld.MY_APPROVALS;
import static com.divalhr.core.people.leave.LeaveWorld.amend;
import static com.divalhr.core.people.leave.LeaveWorld.amendment;
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
import com.divalhr.core.people.application.ManagerGraphLock;
import com.divalhr.core.people.leave.LeaveWorld.Person;
import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MVP-041D/E concurrency and atomicity against real PostgreSQL (D41DE-5; AC6): an amendment racing
 * a manager decision, a tenant-administrator decision or a cancellation of the same request
 * serializes on its row; a manager change or a manager decision racing a routing-exception override
 * serializes on the tenant's manager-graph lock; each in both orders, with exactly one valid
 * outcome, no deadlock and no orphaned evidence, audit, event or idempotency response; identical
 * concurrent retries replay one receipt; an outbox or audit failure rolls the whole amendment or
 * override back.
 */
@IntegrationTest
class LeaveAmendmentConcurrencyIntegrationTest {

  // Under the per-subject leave-request-write limit (10 per minute) with the setup request.
  private static final int PARALLEL = 8;

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private DataSource dataSource;
  @Autowired private ManagerGraphLock graph;
  @Autowired private PlatformTransactionManager transactions;
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
    return response.getStatus() < 300
        ? String.valueOf(response.getStatus())
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

  /**
   * Holds the request row, queues {@code first}, then {@code second}, releases the row, and returns
   * both responses in queue order.
   */
  private List<MockHttpServletResponse> queuedOnRow(
      LeaveWorld w,
      String requestId,
      MockHttpServletRequestBuilder first,
      MockHttpServletRequestBuilder second)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try (Connection blocker = dataSource.getConnection()) {
      blocker.setAutoCommit(false);
      try (PreparedStatement lock =
          blocker.prepareStatement(
              "SELECT 1 FROM people.leave_request WHERE tenant_id = ? AND id = ?::uuid"
                  + " FOR UPDATE")) {
        lock.setObject(1, w.tenant());
        lock.setObject(2, requestId);
        lock.executeQuery().close();
      }
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

  /**
   * Holds the tenant's manager-graph lock, queues {@code first}, then {@code second}, releases it,
   * and returns both responses in queue order.
   */
  private List<MockHttpServletResponse> queuedOnGraph(
      LeaveWorld w, MockHttpServletRequestBuilder first, MockHttpServletRequestBuilder second)
      throws Exception {
    TenantId tenant = new TenantId(w.tenant());
    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService holder = Executors.newSingleThreadExecutor();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> holding =
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
      assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
      Future<MockHttpServletResponse> one = pool.submit(() -> w.call(first));
      awaitWaiting(1);
      Future<MockHttpServletResponse> two = pool.submit(() -> w.call(second));
      awaitWaiting(2);
      release.countDown();
      holding.get(30, TimeUnit.SECONDS);
      return List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));
    } finally {
      holder.shutdownNow();
      pool.shutdownNow();
    }
  }

  /** The request's decision, cancellation and amendment (as original) rows. */
  private static void assertEvidence(
      LeaveWorld w, String requestId, int decisions, int cancels, int amendments) {
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
    assertThat(
            w.count(
                "SELECT count(*) FROM people.leave_request_amendment"
                    + " WHERE original_request_id = ?::uuid",
                requestId))
        .isEqualTo(amendments);
  }

  private static MockHttpServletRequestBuilder reasonOnly(
      Person p, String id, String policy, LocalDate day) throws Exception {
    return amend(
        p.bearer(),
        id,
        Organizations.newKey(),
        amendment(policy, day, day, 1, "fr", "Raison mise à jour."));
  }

  private static MockHttpServletRequestBuilder managerChange(
      LeaveWorld w, Person report, Person manager, LocalDate from) throws Exception {
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("type", "CHANGE");
    command.put("effectiveFrom", from.toString());
    command.put("manager", Map.of("employeeId", manager.employee().toString()));
    JsonNode preview =
        w.expect(
            Employees.postJson(
                w.admin,
                LeaveWorld.EMPLOYEES + "/" + report.employee() + "/employment-changes/preview",
                JSON.writeValueAsString(command)),
            200);
    return w.managerChange(report.employee(), command, preview);
  }

  // ------------------------------------------------------------------------------------------
  // An amendment racing a decision or a cancellation (step 5, the request row)
  // ------------------------------------------------------------------------------------------

  @Test
  void anAmendmentAndAManagerDecisionSerializeInEitherOrder() throws Exception {
    LeaveWorld w = new LeaveWorld(mvc, jdbc, lookups);
    LocalDate t = w.today;
    String managed = w.policy("MGR", "MANAGER");
    Person manager = w.person("Josué", "Kabeya");
    Person report = w.person("Bénédicte", "Mbuyi");
    w.manage(report.employee(), manager.employee(), t);
    String amendedFirst = w.request(report, managed, t.plusDays(5), t.plusDays(5));
    String decidedFirst = w.request(report, managed, t.plusDays(9), t.plusDays(9));
    Map<String, Object> approve = decision("APPROVED", "fr", "Accordé.");

    List<MockHttpServletResponse> first =
        queuedOnRow(
            w,
            amendedFirst,
            reasonOnly(report, amendedFirst, managed, t.plusDays(5)),
            decide(MY_APPROVALS, manager.bearer(), amendedFirst, Organizations.newKey(), approve));
    assertThat(code(first.get(0))).isEqualTo("201");
    assertThat(code(first.get(1))).isEqualTo("409 LEAVE_REQUEST_ALREADY_DECIDED");
    List<MockHttpServletResponse> second =
        queuedOnRow(
            w,
            decidedFirst,
            decide(MY_APPROVALS, manager.bearer(), decidedFirst, Organizations.newKey(), approve),
            reasonOnly(report, decidedFirst, managed, t.plusDays(9)));
    assertThat(code(second.get(0))).isEqualTo("200");
    assertThat(code(second.get(1))).isEqualTo("409 LEAVE_REQUEST_ALREADY_DECIDED");
    assertThat(w.state(amendedFirst)).isEqualTo("AMENDED");
    assertThat(w.state(decidedFirst)).isEqualTo("APPROVED");
    assertThat(w.amendments()).containsExactly(1, 1, 1);
    assertThat(w.decisions()).containsExactly(1, 1, 1);
    assertEvidence(w, amendedFirst, 0, 0, 1);
    assertEvidence(w, decidedFirst, 1, 0, 0);
  }

  @Test
  void anAmendmentAndATenantAdministratorDecisionSerializeInEitherOrder() throws Exception {
    LeaveWorld w = new LeaveWorld(mvc, jdbc, lookups);
    LocalDate t = w.today;
    String central = w.policy("ADM", "TENANT_ADMIN");
    Person report = w.person("Bénédicte", "Mbuyi");
    String amendedFirst = w.request(report, central, t.plusDays(5), t.plusDays(5));
    String decidedFirst = w.request(report, central, t.plusDays(9), t.plusDays(9));
    Map<String, Object> reject = decision("REJECTED", "en", "Not this week.");

    List<MockHttpServletResponse> first =
        queuedOnRow(
            w,
            amendedFirst,
            reasonOnly(report, amendedFirst, central, t.plusDays(5)),
            decide(ADMIN_APPROVALS, w.admin, amendedFirst, Organizations.newKey(), reject));
    assertThat(code(first.get(0))).isEqualTo("201");
    assertThat(code(first.get(1))).isEqualTo("409 LEAVE_REQUEST_ALREADY_DECIDED");
    List<MockHttpServletResponse> second =
        queuedOnRow(
            w,
            decidedFirst,
            decide(ADMIN_APPROVALS, w.admin, decidedFirst, Organizations.newKey(), reject),
            reasonOnly(report, decidedFirst, central, t.plusDays(9)));
    assertThat(code(second.get(0))).isEqualTo("200");
    assertThat(code(second.get(1))).isEqualTo("409 LEAVE_REQUEST_ALREADY_DECIDED");
    assertThat(w.amendments()).containsExactly(1, 1, 1);
    assertThat(w.decisions()).containsExactly(1, 1, 1);
    assertEvidence(w, amendedFirst, 0, 0, 1);
    assertEvidence(w, decidedFirst, 1, 0, 0);
  }

  @Test
  void anAmendmentAndACancellationSerializeInEitherOrder() throws Exception {
    LeaveWorld w = new LeaveWorld(mvc, jdbc, lookups);
    LocalDate t = w.today;
    String central = w.policy("ADM", "TENANT_ADMIN");
    Person report = w.person("Bénédicte", "Mbuyi");
    String amendedFirst = w.request(report, central, t.plusDays(5), t.plusDays(5));
    String cancelledFirst = w.request(report, central, t.plusDays(9), t.plusDays(9));
    Map<String, Object> withdraw = cancellation("fr", "Plus besoin.");

    List<MockHttpServletResponse> first =
        queuedOnRow(
            w,
            amendedFirst,
            reasonOnly(report, amendedFirst, central, t.plusDays(5)),
            cancel(report.bearer(), amendedFirst, Organizations.newKey(), withdraw));
    assertThat(code(first.get(0))).isEqualTo("201");
    assertThat(code(first.get(1))).isEqualTo("409 LEAVE_REQUEST_ALREADY_AMENDED");
    List<MockHttpServletResponse> second =
        queuedOnRow(
            w,
            cancelledFirst,
            cancel(report.bearer(), cancelledFirst, Organizations.newKey(), withdraw),
            reasonOnly(report, cancelledFirst, central, t.plusDays(9)));
    assertThat(code(second.get(0))).isEqualTo("200");
    assertThat(code(second.get(1))).isEqualTo("409 LEAVE_REQUEST_ALREADY_CANCELLED");
    assertThat(w.amendments()).containsExactly(1, 1, 1);
    assertThat(w.cancellations()).containsExactly(1, 1, 1);
    assertEvidence(w, amendedFirst, 0, 0, 1);
    assertEvidence(w, cancelledFirst, 0, 1, 0);
  }

  // ------------------------------------------------------------------------------------------
  // A manager change or a manager decision racing an override (step 1, the manager graph)
  // ------------------------------------------------------------------------------------------

  @Test
  void aManagerChangeAndAnOverrideSerializeInEitherOrder() throws Exception {
    LeaveWorld w = new LeaveWorld(mvc, jdbc, lookups);
    LocalDate t = w.today;
    String managed = w.policy("MGR", "MANAGER");
    Person manager = w.person("Josué", "Kabeya");
    Person assignedFirst = w.person("Bénédicte", "Mbuyi");
    Person overriddenFirst = w.person("Aline", "Tshibanda");
    String laterManaged = w.request(assignedFirst, managed, t.plusDays(10), t.plusDays(10));
    String overridden = w.request(overriddenFirst, managed, t.plusDays(10), t.plusDays(10));
    Map<String, Object> approve = decision("APPROVED", "fr", "Aucun responsable admissible.");

    // (1) The manager change queues first: the request is covered, the override finds nothing.
    List<MockHttpServletResponse> first =
        queuedOnGraph(
            w,
            managerChange(w, assignedFirst, manager, t),
            decide(EXCEPTIONS, w.admin, laterManaged, Organizations.newKey(), approve));
    assertThat(code(first.get(0))).isEqualTo("201");
    assertThat(code(first.get(1))).isEqualTo("404 LEAVE_REQUEST_NOT_FOUND");
    assertThat(w.state(laterManaged)).isEqualTo("PENDING");
    // (2) The override queues first: it decides under the absence it saw; the change follows.
    List<MockHttpServletResponse> second =
        queuedOnGraph(
            w,
            decide(EXCEPTIONS, w.admin, overridden, Organizations.newKey(), approve),
            managerChange(w, overriddenFirst, manager, t));
    assertThat(code(second.get(0))).isEqualTo("200");
    assertThat(code(second.get(1))).isEqualTo("201");
    assertThat(w.state(overridden)).isEqualTo("APPROVED");
    assertThat(w.overrides()).containsExactly(1, 1, 1);
    assertEvidence(w, laterManaged, 0, 0, 0);
    assertEvidence(w, overridden, 1, 0, 0);
  }

  @Test
  void aManagerDecisionAndAnOverrideSerializeInEitherOrder() throws Exception {
    LeaveWorld w = new LeaveWorld(mvc, jdbc, lookups);
    LocalDate t = w.today;
    String managed = w.policy("MGR", "MANAGER");
    Person manager = w.person("Josué", "Kabeya");
    Person report = w.person("Bénédicte", "Mbuyi");
    w.manage(report.employee(), manager.employee(), t);
    String decidedFirst = w.request(report, managed, t.plusDays(5), t.plusDays(5));
    String overrideFirst = w.request(report, managed, t.plusDays(9), t.plusDays(9));
    Map<String, Object> approve = decision("APPROVED", "fr", "Accordé.");

    List<MockHttpServletResponse> first =
        queuedOnGraph(
            w,
            decide(MY_APPROVALS, manager.bearer(), decidedFirst, Organizations.newKey(), approve),
            decide(EXCEPTIONS, w.admin, decidedFirst, Organizations.newKey(), approve));
    assertThat(code(first.get(0))).isEqualTo("200");
    assertThat(code(first.get(1))).isEqualTo("409 LEAVE_REQUEST_ALREADY_DECIDED");
    List<MockHttpServletResponse> second =
        queuedOnGraph(
            w,
            decide(EXCEPTIONS, w.admin, overrideFirst, Organizations.newKey(), approve),
            decide(MY_APPROVALS, manager.bearer(), overrideFirst, Organizations.newKey(), approve));
    assertThat(code(second.get(0))).isEqualTo("404 LEAVE_REQUEST_NOT_FOUND");
    assertThat(code(second.get(1))).isEqualTo("200");
    assertThat(w.decisions()).containsExactly(2, 2, 2);
    assertThat(w.overrides()).containsExactly(0, 0, 0);
    assertEvidence(w, decidedFirst, 1, 0, 0);
    assertEvidence(w, overrideFirst, 1, 0, 0);
  }

  // ------------------------------------------------------------------------------------------
  // Identical retries
  // ------------------------------------------------------------------------------------------

  @Test
  void identicalConcurrentAmendmentRetriesReplayOneReceipt() throws Exception {
    LeaveWorld w = new LeaveWorld(mvc, jdbc, lookups);
    String central = w.policy("ADM", "TENANT_ADMIN");
    Person report = w.person("Bénédicte", "Mbuyi");
    LocalDate day = w.today.plusDays(5);
    String id = w.request(report, central, day, day);
    String key = Organizations.newKey();
    Map<String, Object> body =
        amendment(central, day, day.plusDays(1), 2, "fr", "Un jour de plus.");
    List<MockHttpServletRequestBuilder> attempts = new ArrayList<>();
    for (int i = 0; i < PARALLEL; i++) {
      attempts.add(amend(report.bearer(), id, key, body));
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
    assertThat(w.amendments()).containsExactly(1, 1, 1);
    assertThat(
            w.count(
                "SELECT count(*) FROM people.leave_request WHERE tenant_id = ?"
                    + " AND state = 'PENDING'",
                w.tenant()))
        .isEqualTo(1);
  }

  @Test
  void identicalConcurrentOverrideRetriesReplayOneReceipt() throws Exception {
    LeaveWorld w = new LeaveWorld(mvc, jdbc, lookups);
    String managed = w.policy("MGR", "MANAGER");
    Person report = w.person("Bénédicte", "Mbuyi");
    String id = w.request(report, managed, w.today.plusDays(5), w.today.plusDays(5));
    String key = Organizations.newKey();
    List<MockHttpServletRequestBuilder> attempts = new ArrayList<>();
    for (int i = 0; i < PARALLEL; i++) {
      attempts.add(
          decide(EXCEPTIONS, w.admin, id, key, decision("REJECTED", "fr", "Aucun responsable.")));
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
    assertThat(w.overrides()).containsExactly(1, 1, 1);
  }

  // ------------------------------------------------------------------------------------------
  // Atomicity
  // ------------------------------------------------------------------------------------------

  @Test
  void anOutboxFailureRollsTheWholeAmendmentBack() throws Exception {
    OutboxWriter target = AopTestUtils.getUltimateTargetObject(outbox);
    amendmentRolledBack(
        w ->
            doThrow(new IllegalStateException("simulated outbox failure"))
                .when(target)
                .append(any(EventEnvelope.class)),
        () -> reset(target));
  }

  @Test
  void anAuditFailureRollsTheWholeAmendmentBack() throws Exception {
    AuditRecorder target = AopTestUtils.getUltimateTargetObject(audit);
    amendmentRolledBack(
        w ->
            doThrow(new IllegalStateException("simulated audit failure"))
                .when(target)
                .record(
                    argThat(
                        (AuditEvent event) ->
                            event != null && "leave-request.amend".equals(event.action()))),
        () -> {
          reset(target);
          doCallRealMethod().when(target).record(any(AuditEvent.class));
        });
  }

  private void amendmentRolledBack(Consumer<LeaveWorld> fail, Runnable restore) throws Exception {
    LeaveWorld w = new LeaveWorld(mvc, jdbc, lookups);
    String central = w.policy("ADM", "TENANT_ADMIN");
    Person report = w.person("Bénédicte", "Mbuyi");
    LocalDate day = w.today.plusDays(5);
    String id = w.request(report, central, day, day);
    fail.accept(w);
    String key = Organizations.newKey();
    Map<String, Object> body =
        amendment(central, day.plusDays(10), day.plusDays(10), 1, "fr", "Décalé.");
    assertThat(w.call(amend(report.bearer(), id, key, body)).getStatus()).isEqualTo(500);
    restore.run();
    assertThat(w.state(id)).isEqualTo("PENDING");
    assertThat(w.amendments()).containsExactly(0, 0, 0);
    assertThat(w.count("SELECT count(*) FROM people.leave_request WHERE tenant_id = ?", w.tenant()))
        .isEqualTo(1);
    assertThat(
            w.count(
                "SELECT count(*) FROM platform.idempotency_record WHERE idempotency_key = ?", key))
        .isZero();
    // The original's dates were not released.
    w.problem(LeaveWorld.submit(report.bearer(), central, day, day), 409, "LEAVE_REQUEST_OVERLAP");
    MockHttpServletResponse retried = w.call(amend(report.bearer(), id, key, body));
    assertThat(retried.getStatus()).isEqualTo(201);
    assertThat(retried.getHeader("Idempotent-Replayed")).isNull();
    assertThat(w.state(id)).isEqualTo("AMENDED");
    assertThat(w.amendments()).containsExactly(1, 1, 1);
  }

  @Test
  void anOutboxFailureRollsTheWholeOverrideBack() throws Exception {
    OutboxWriter target = AopTestUtils.getUltimateTargetObject(outbox);
    LeaveWorld w = new LeaveWorld(mvc, jdbc, lookups);
    String managed = w.policy("MGR", "MANAGER");
    Person report = w.person("Bénédicte", "Mbuyi");
    String id = w.request(report, managed, w.today.plusDays(5), w.today.plusDays(5));
    doThrow(new IllegalStateException("simulated outbox failure"))
        .when(target)
        .append(any(EventEnvelope.class));
    String key = Organizations.newKey();
    Map<String, Object> approve = decision("APPROVED", "fr", "Aucun responsable admissible.");
    assertThat(w.call(decide(EXCEPTIONS, w.admin, id, key, approve)).getStatus()).isEqualTo(500);
    reset(target);
    assertThat(w.state(id)).isEqualTo("PENDING");
    assertThat(w.overrides()).containsExactly(0, 0, 0);
    assertThat(
            w.count(
                "SELECT count(*) FROM platform.idempotency_record WHERE idempotency_key = ?", key))
        .isZero();
    assertThat(w.call(decide(EXCEPTIONS, w.admin, id, key, approve)).getStatus()).isEqualTo(200);
    assertThat(w.overrides()).containsExactly(1, 1, 1);
  }
}
