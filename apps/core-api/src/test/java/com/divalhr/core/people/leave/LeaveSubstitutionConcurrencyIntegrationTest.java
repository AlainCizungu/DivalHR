package com.divalhr.core.people.leave;

import static com.divalhr.core.people.leave.LeaveWorld.EXCEPTIONS;
import static com.divalhr.core.people.leave.LeaveWorld.JSON;
import static com.divalhr.core.people.leave.LeaveWorld.MY_APPROVALS;
import static com.divalhr.core.people.leave.LeaveWorld.appoint;
import static com.divalhr.core.people.leave.LeaveWorld.appointment;
import static com.divalhr.core.people.leave.LeaveWorld.cancellation;
import static com.divalhr.core.people.leave.LeaveWorld.decide;
import static com.divalhr.core.people.leave.LeaveWorld.decision;
import static com.divalhr.core.people.leave.LeaveWorld.revoke;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.people.application.ManagerGraphLock;
import com.divalhr.core.people.leave.LeaveWorld.Person;
import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.idempotency.IdempotencyScope;
import com.divalhr.core.platform.idempotency.IdempotencyService;
import com.divalhr.core.platform.idempotency.StoredResponse;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import com.divalhr.core.platform.tenancy.TenantId;
import com.divalhr.core.support.Employees;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * MVP-041G concurrency and atomicity against real PostgreSQL (D41G-5): each pair is queued behind
 * the tenant's manager-graph lock in both orders and settles to exactly one valid outcome, without
 * deadlock, orphan, stale authorization or duplicate evidence: an appointment's creation or
 * revocation against a substitute decision (and the substitute's inbox), a primary manager against
 * the substitute, a manager-line removal or correction against a substitute decision and a
 * routing-exception override; identical retries replay one result; an outbox, audit or
 * idempotency-completion failure rolls the appointment, the revocation or the substitute decision
 * back with its key.
 */
@IntegrationTest
class LeaveSubstitutionConcurrencyIntegrationTest {

  private static final int PARALLEL = 8;
  private static final String REASON = "Validé pendant l’absence du responsable.";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
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

  // ------------------------------------------------------------------------------------------
  // Helpers
  // ------------------------------------------------------------------------------------------

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
   * Runs the calls queued, in this order, behind the tenant's manager-graph lock, then releases it;
   * returns each call's outcome code, in the same order.
   */
  private List<String> queued(LeaveWorld w, List<MockHttpServletRequestBuilder> calls)
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
                          graph.lock(new TenantId(w.tenant()));
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

  private static MockHttpServletRequestBuilder decideAs(Person caller, String id, String outcome)
      throws Exception {
    return decide(
        MY_APPROVALS, caller.bearer(), id, Organizations.newKey(), decision(outcome, "fr", REASON));
  }

  private static MockHttpServletRequestBuilder appointNow(
      LeaveWorld w, Person manager, Person substitute, LocalDate from, LocalDate to)
      throws Exception {
    return appoint(
        w.admin,
        Organizations.newKey(),
        appointment(
            manager.employee(), substitute.employee(), from, to, "fr", "Intérim du responsable."));
  }

  private static MockHttpServletRequestBuilder revokeNow(LeaveWorld w, String id) throws Exception {
    return revoke(w.admin, id, Organizations.newKey(), cancellation("en", "Back early."));
  }

  /** The commit of a previewed manager change of the report, as a builder (for races). */
  private static MockHttpServletRequestBuilder managerChange(
      LeaveWorld w, Person report, Map<String, Object> command) throws Exception {
    JsonNode preview =
        w.expect(
            Employees.postJson(
                w.admin,
                LeaveWorld.EMPLOYEES + "/" + report.employee() + "/employment-changes/preview",
                JSON.writeValueAsString(command)),
            200);
    return w.managerChange(report.employee(), command, preview);
  }

  /** Clears the report's manager from {@code from} on. */
  private static Map<String, Object> clear(LocalDate from) {
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("type", "CHANGE");
    command.put("effectiveFrom", from.toString());
    Map<String, Object> none = new LinkedHashMap<>();
    none.put("employeeId", null);
    command.put("manager", none);
    return command;
  }

  /** Corrects the report's active line naming {@code from} to name {@code to}. */
  private Map<String, Object> correction(Person report, Person from, Person to) {
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT id::text AS id, effective_from FROM people.employment_assignment"
                + " WHERE employee_id = ? AND kind = 'MANAGER' AND superseded_by_change_id IS NULL"
                + " AND manager_employee_id = ?",
            report.employee(),
            from.employee());
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("type", "CORRECTION");
    command.put("effectiveFrom", row.get("effective_from").toString());
    command.put("manager", Map.of("employeeId", to.employee().toString()));
    command.put("reasonCode", "DATA_ENTRY_ERROR");
    command.put("correctsAssignmentId", row.get("id"));
    return command;
  }

  private static List<String> ids(JsonNode page) {
    List<String> ids = new ArrayList<>();
    page.get("items").forEach(item -> ids.add(item.get("id").asText()));
    return ids;
  }

  // ------------------------------------------------------------------------------------------
  // Appointment and revocation against substitute work
  // ------------------------------------------------------------------------------------------

  @Test
  void anAppointmentOrARevocationAndASubstituteDecisionAreSerializedInEitherOrder()
      throws Exception {
    LeaveWorld w = new LeaveWorld(mvc, jdbc, lookups);
    LocalDate t = w.today;
    String managed = w.policy("MGR", "MANAGER");
    Person manager = w.person("Josué", "Kabeya");
    Person substitute = w.person("Grâce", "Lukusa");
    Person other = w.person("Héritier", "Banza");
    Person deputy = w.person("Irène", "Nsimba");
    Person report = w.person("Bénédicte", "Mbuyi");
    Person colleague = w.person("Aline", "Tshibanda");
    w.manage(report.employee(), manager.employee(), t);
    w.manage(colleague.employee(), other.employee(), t);
    String a = w.request(report, managed, t.plusDays(10), t.plusDays(10));
    String b = w.request(report, managed, t.plusDays(12), t.plusDays(12));
    String c = w.request(colleague, managed, t.plusDays(10), t.plusDays(10));
    String d = w.request(colleague, managed, t.plusDays(12), t.plusDays(12));

    // (1) The appointment queues first: the decision then sees it and commits as the substitute.
    assertThat(
            queued(
                w,
                List.of(
                    appointNow(w, manager, substitute, t, t.plusDays(30)),
                    decideAs(substitute, a, "APPROVED"))))
        .containsExactly("201", "200");
    String appointment =
        jdbc.queryForObject(
            "SELECT id::text FROM people.leave_approval_substitution WHERE tenant_id = ?"
                + " AND manager_employee_id = ?",
            String.class,
            w.tenant(),
            manager.employee());
    // (2) The decision queues first: it commits under the effective appointment, then the
    // revocation commits; neither rewrites the other.
    assertThat(queued(w, List.of(decideAs(substitute, b, "REJECTED"), revokeNow(w, appointment))))
        .containsExactly("200", "201");
    // (3) The decision queues before the appointment exists: it is refused, then the appointment.
    assertThat(
            queued(
                w,
                List.of(
                    decideAs(deputy, c, "APPROVED"),
                    appointNow(w, other, deputy, t, t.plusDays(30)))))
        .containsExactly("404 LEAVE_REQUEST_NOT_FOUND", "201");
    String second =
        jdbc.queryForObject(
            "SELECT id::text FROM people.leave_approval_substitution WHERE tenant_id = ?"
                + " AND manager_employee_id = ?",
            String.class,
            w.tenant(),
            other.employee());
    // While the revocation waits, the inbox still reads the committed appointment; once it
    // commits, the delegated work is gone.
    assertThat(ids(w.expect(get(MY_APPROVALS).header("Authorization", deputy.bearer()), 200)))
        .containsExactlyInAnyOrder(c, d);
    // (4) The revocation queues first: the decision is refused.
    assertThat(queued(w, List.of(revokeNow(w, second), decideAs(deputy, d, "APPROVED"))))
        .containsExactly("201", "404 LEAVE_REQUEST_NOT_FOUND");
    assertThat(ids(w.expect(get(MY_APPROVALS).header("Authorization", deputy.bearer()), 200)))
        .isEmpty();
    assertThat(List.of(w.state(a), w.state(b), w.state(c), w.state(d)))
        .containsExactly("APPROVED", "REJECTED", "PENDING", "PENDING");
    assertThat(w.substituteDecisions()).containsExactly(2, 2, 2);
    assertThat(w.substitutions()).containsExactly(2, 2, 4, 4);
  }

  // ------------------------------------------------------------------------------------------
  // A revocation and its replacement (R98-1)
  // ------------------------------------------------------------------------------------------

  @Test
  void aReplacementIsSerializedWithTheRevocationItReplaces() throws Exception {
    LeaveWorld w = new LeaveWorld(mvc, jdbc, lookups);
    LocalDate t = w.today;
    Person manager = w.person("Josué", "Kabeya");
    Person first = w.person("Grâce", "Lukusa");
    Person second = w.person("Héritier", "Banza");
    Person report = w.person("Bénédicte", "Mbuyi");
    w.manage(report.employee(), manager.employee(), t);
    String a = w.appoint(manager, first, t, t.plusDays(30));

    // (1) The replacement queues first while A is still active: the overlap conflict; A's
    // revocation then commits; the same replacement, retried with its key, is accepted.
    String replacementKey = Organizations.newKey();
    Map<String, Object> replacement =
        appointment(
            manager.employee(), second.employee(), t.plusDays(5), t.plusDays(30), "fr", "Relève.");
    assertThat(queued(w, List.of(appoint(w.admin, replacementKey, replacement), revokeNow(w, a))))
        .containsExactly("409 LEAVE_APPROVAL_SUBSTITUTION_OVERLAP", "201");
    assertThat(code(w.call(appoint(w.admin, replacementKey, replacement)))).isEqualTo("201");
    String b =
        jdbc.queryForObject(
            "SELECT substitution_id::text FROM people.leave_approval_substitution_coverage"
                + " WHERE tenant_id = ?",
            String.class,
            w.tenant());

    // (2) The revocation queues first: the overlapping replacement after it is accepted.
    assertThat(
            queued(
                w,
                List.of(
                    revokeNow(w, b),
                    appointNow(w, manager, first, t.plusDays(10), t.plusDays(20)))))
        .containsExactly("201", "201");
    assertThat(w.substitutions()).containsExactly(3, 2, 5, 5);
    assertThat(coverage(w)).isEqualTo(1);
  }

  // ------------------------------------------------------------------------------------------
  // The primary manager against the substitute
  // ------------------------------------------------------------------------------------------

  @Test
  void thePrimaryManagerAndTheSubstituteRaceAndTheFirstValidDecisionWins() throws Exception {
    LeaveWorld w = new LeaveWorld(mvc, jdbc, lookups);
    LocalDate t = w.today;
    String managed = w.policy("MGR", "MANAGER");
    Person manager = w.person("Josué", "Kabeya");
    Person substitute = w.person("Grâce", "Lukusa");
    Person report = w.person("Bénédicte", "Mbuyi");
    w.manage(report.employee(), manager.employee(), t);
    String x = w.request(report, managed, t.plusDays(10), t.plusDays(10));
    String y = w.request(report, managed, t.plusDays(12), t.plusDays(12));
    String z = w.request(report, managed, t.plusDays(14), t.plusDays(14));
    w.appoint(manager, substitute, t, t.plusDays(30));

    assertThat(
            queued(
                w, List.of(decideAs(substitute, x, "APPROVED"), decideAs(manager, x, "REJECTED"))))
        .containsExactly("200", "409 LEAVE_REQUEST_ALREADY_DECIDED");
    assertThat(
            queued(
                w, List.of(decideAs(manager, y, "APPROVED"), decideAs(substitute, y, "REJECTED"))))
        .containsExactly("200", "409 LEAVE_REQUEST_ALREADY_DECIDED");
    // Unordered: exactly one of many attempts wins, whoever sends it.
    List<MockHttpServletRequestBuilder> attempts = new ArrayList<>();
    for (int i = 0; i < PARALLEL; i++) {
      attempts.add(
          decideAs(i % 2 == 0 ? manager : substitute, z, i % 3 == 0 ? "REJECTED" : "APPROVED"));
    }
    List<String> outcomes = new ArrayList<>();
    for (MockHttpServletResponse response : parallel(w, attempts)) {
      outcomes.add(code(response));
    }
    assertThat(outcomes).filteredOn("200"::equals).hasSize(1);
    assertThat(outcomes)
        .filteredOn(o -> !o.equals("200"))
        .containsOnly("409 LEAVE_REQUEST_ALREADY_DECIDED");
    assertThat(
            w.count(
                "SELECT count(*) FROM people.leave_request_decision WHERE tenant_id = ?",
                w.tenant()))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT decision_authority FROM people.leave_request_decision"
                    + " WHERE request_id = ?::uuid",
                String.class,
                x))
        .isEqualTo("MANAGER_SUBSTITUTE");
    assertThat(
            jdbc.queryForObject(
                "SELECT decision_authority FROM people.leave_request_decision"
                    + " WHERE request_id = ?::uuid",
                String.class,
                y))
        .isEqualTo("MANAGER");
  }

  // ------------------------------------------------------------------------------------------
  // Manager lines and routing-exception overrides against a substitute decision
  // ------------------------------------------------------------------------------------------

  @Test
  void managerLineChangesSubstituteDecisionsAndOverridesAreSerializedInEitherOrder()
      throws Exception {
    LeaveWorld w = new LeaveWorld(mvc, jdbc, lookups);
    LocalDate t = w.today;
    String managed = w.policy("MGR", "MANAGER");
    Person manager = w.person("Josué", "Kabeya");
    Person substitute = w.person("Grâce", "Lukusa");
    Person outsider = w.person("Amani", "Ilunga");
    Person first = w.person("Bénédicte", "Mbuyi");
    Person second = w.person("Aline", "Tshibanda");
    Person third = w.person("Chance", "Mwamba");
    Person fourth = w.person("Divine", "Kalonji");
    for (Person report : List.of(first, second, third, fourth)) {
      w.manage(report.employee(), manager.employee(), t);
    }
    String r1 = w.request(first, managed, t.plusDays(10), t.plusDays(10));
    String r2 = w.request(second, managed, t.plusDays(10), t.plusDays(10));
    String r3 = w.request(third, managed, t.plusDays(10), t.plusDays(10));
    String r4 = w.request(fourth, managed, t.plusDays(10), t.plusDays(10));
    w.appoint(manager, substitute, t, t.plusDays(30));
    Map<String, Object> override = decision("APPROVED", "fr", "Décision administrative.");

    // (1) The line removal queues first: the substitute is refused, the override decides.
    assertThat(
            queued(
                w,
                List.of(
                    managerChange(w, first, clear(t.plusDays(1))),
                    decideAs(substitute, r1, "APPROVED"),
                    decide(EXCEPTIONS, w.admin, r1, Organizations.newKey(), override))))
        .containsExactly("201", "404 LEAVE_REQUEST_NOT_FOUND", "200");
    // (2) The substitute queues first: it decides under the line; the removal commits; the
    // override finds a decided request.
    assertThat(
            queued(
                w,
                List.of(
                    decideAs(substitute, r2, "REJECTED"),
                    managerChange(w, second, clear(t.plusDays(1))),
                    decide(EXCEPTIONS, w.admin, r2, Organizations.newKey(), override))))
        .containsExactly("200", "201", "409 LEAVE_REQUEST_ALREADY_DECIDED");
    // (3) A correction to another manager queues first: the substitute is refused.
    assertThat(
            queued(
                w,
                List.of(
                    managerChange(w, third, correction(third, manager, outsider)),
                    decideAs(substitute, r3, "APPROVED"))))
        .containsExactly("201", "404 LEAVE_REQUEST_NOT_FOUND");
    // (4) The substitute queues first: it decides, then the correction commits.
    assertThat(
            queued(
                w,
                List.of(
                    decideAs(substitute, r4, "APPROVED"),
                    managerChange(w, fourth, correction(fourth, manager, outsider)))))
        .containsExactly("200", "201");
    assertThat(List.of(w.state(r1), w.state(r2), w.state(r3), w.state(r4)))
        .containsExactly("APPROVED", "REJECTED", "PENDING", "APPROVED");
    assertThat(w.substituteDecisions()).containsExactly(2, 2, 2);
    assertThat(w.overrides()).containsExactly(1, 1, 1);
  }

  // ------------------------------------------------------------------------------------------
  // Identical retries
  // ------------------------------------------------------------------------------------------

  @Test
  void identicalConcurrentRetriesReplayOneAppointmentRevocationAndDecision() throws Exception {
    LeaveWorld w = new LeaveWorld(mvc, jdbc, lookups);
    LocalDate t = w.today;
    String managed = w.policy("MGR", "MANAGER");
    Person manager = w.person("Josué", "Kabeya");
    Person substitute = w.person("Grâce", "Lukusa");
    Person report = w.person("Bénédicte", "Mbuyi");
    w.manage(report.employee(), manager.employee(), t);
    String id = w.request(report, managed, t.plusDays(10), t.plusDays(10));

    Map<String, Object> body =
        appointment(manager.employee(), substitute.employee(), t, t.plusDays(30), "fr", "Intérim.");
    String key = Organizations.newKey();
    JsonNode created = sameReceipt(w, () -> appoint(w.admin, key, body), 201);
    String appointment = created.get("substitutionId").asText();
    assertThat(w.substitutions()).containsExactly(1, 0, 1, 1);

    Map<String, Object> decided = decision("APPROVED", "fr", REASON);
    String decisionKey = Organizations.newKey();
    sameReceipt(w, () -> decide(MY_APPROVALS, substitute.bearer(), id, decisionKey, decided), 200);
    assertThat(w.substituteDecisions()).containsExactly(1, 1, 1);

    Map<String, Object> revocation = cancellation("en", "Back early.");
    String revokeKey = Organizations.newKey();
    sameReceipt(w, () -> revoke(w.admin, appointment, revokeKey, revocation), 201);
    assertThat(w.substitutions()).containsExactly(1, 1, 2, 2);
  }

  @FunctionalInterface
  private interface Call {
    MockHttpServletRequestBuilder build() throws Exception;
  }

  private static JsonNode sameReceipt(LeaveWorld w, Call call, int status) throws Exception {
    List<MockHttpServletRequestBuilder> attempts = new ArrayList<>();
    for (int i = 0; i < PARALLEL; i++) {
      attempts.add(call.build());
    }
    List<JsonNode> receipts = new ArrayList<>();
    int replayed = 0;
    for (MockHttpServletResponse response : parallel(w, attempts)) {
      assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
      receipts.add(JSON.readTree(response.getContentAsString()));
      if ("true".equals(response.getHeader("Idempotent-Replayed"))) {
        replayed++;
      }
    }
    assertThat(Set.copyOf(receipts)).hasSize(1);
    assertThat(replayed).isEqualTo(PARALLEL - 1);
    return receipts.get(0);
  }

  // ------------------------------------------------------------------------------------------
  // Atomicity
  // ------------------------------------------------------------------------------------------

  @Test
  void anOutboxFailureRollsEveryWriteBack() throws Exception {
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
        List.of(
            "people.leave-approval-substitution.created.v1",
            "people.leave-request.substitute-approved.v1",
            "people.leave-approval-substitution.revoked.v1"));
  }

  @Test
  void anAuditFailureRollsEveryWriteBack() throws Exception {
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
        List.of(
            "leave-approval-substitution.create",
            "leave-request.approve",
            "leave-approval-substitution.revoke"));
  }

  @Test
  void anIdempotencyCompletionFailureRollsEveryWriteBack() throws Exception {
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
        List.of(
            "leave-approval-substitution.create",
            "leave-request.manager-decide",
            "leave-approval-substitution.revoke"));
  }

  @FunctionalInterface
  private interface Failure {
    void inject(String target);
  }

  /**
   * The appointment, a substitute decision and the revocation, each failing once (nothing written,
   * no key kept) and then retried with the same key.
   */
  private void rolledBack(Failure fail, Runnable restore, List<String> targets) throws Exception {
    LeaveWorld w = new LeaveWorld(mvc, jdbc, lookups);
    LocalDate t = w.today;
    String managed = w.policy("MGR", "MANAGER");
    Person manager = w.person("Josué", "Kabeya");
    Person substitute = w.person("Grâce", "Lukusa");
    Person report = w.person("Bénédicte", "Mbuyi");
    w.manage(report.employee(), manager.employee(), t);
    String id = w.request(report, managed, t.plusDays(10), t.plusDays(10));

    // The appointment.
    Map<String, Object> body =
        appointment(manager.employee(), substitute.employee(), t, t.plusDays(30), "fr", "Intérim.");
    String key = Organizations.newKey();
    fail.inject(targets.get(0));
    assertThat(w.call(appoint(w.admin, key, body)).getStatus()).isEqualTo(500);
    restore.run();
    assertThat(w.substitutions()).containsExactly(0, 0, 0, 0);
    assertThat(coverage(w)).isZero();
    assertThat(keys(w, key)).isZero();
    JsonNode created = w.expect(appoint(w.admin, key, body), 201);
    String appointment = created.get("substitutionId").asText();
    assertThat(w.substitutions()).containsExactly(1, 0, 1, 1);

    // The substitute decision.
    Map<String, Object> decided = decision("APPROVED", "fr", REASON);
    String decisionKey = Organizations.newKey();
    fail.inject(targets.get(1));
    assertThat(
            w.call(decide(MY_APPROVALS, substitute.bearer(), id, decisionKey, decided)).getStatus())
        .isEqualTo(500);
    restore.run();
    assertThat(w.state(id)).isEqualTo("PENDING");
    assertThat(w.substituteDecisions()).containsExactly(0, 0, 0);
    assertThat(keys(w, decisionKey)).isZero();
    MockHttpServletResponse retried =
        w.call(decide(MY_APPROVALS, substitute.bearer(), id, decisionKey, decided));
    assertThat(retried.getStatus()).isEqualTo(200);
    assertThat(retried.getHeader("Idempotent-Replayed")).isNull();
    assertThat(w.substituteDecisions()).containsExactly(1, 1, 1);

    // The revocation.
    Map<String, Object> revocation = cancellation("en", "Back early.");
    String revokeKey = Organizations.newKey();
    fail.inject(targets.get(2));
    assertThat(w.call(revoke(w.admin, appointment, revokeKey, revocation)).getStatus())
        .isEqualTo(500);
    restore.run();
    assertThat(w.substitutions()).containsExactly(1, 0, 1, 1);
    // The revocation's coverage change rolled back with it: the dates are still held.
    assertThat(coverage(w)).isEqualTo(1);
    assertThat(keys(w, revokeKey)).isZero();
    w.expect(revoke(w.admin, appointment, revokeKey, revocation), 201);
    assertThat(w.substitutions()).containsExactly(1, 1, 2, 2);
    assertThat(coverage(w)).isZero();
  }

  private static int coverage(LeaveWorld w) {
    return w.count(
        "SELECT count(*) FROM people.leave_approval_substitution_coverage WHERE tenant_id = ?",
        w.tenant());
  }

  private static int keys(LeaveWorld w, String key) {
    return w.count(
        "SELECT count(*) FROM platform.idempotency_record WHERE idempotency_key = ?", key);
  }
}
