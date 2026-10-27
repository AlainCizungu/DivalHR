package com.divalhr.core.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.operations.TaskWorld.Person;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-080 (Issue #167, D80-12 items 7 and 8) against real PostgreSQL: a concurrent complete and
 * cancel produce exactly one terminal action, identical concurrent retries produce one execution,
 * and a failure while the event is written leaves the projection, the action, the audit trail and
 * the outbox exactly as they were.
 */
@IntegrationTest
class TaskConcurrencyAndRollbackIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @MockitoSpyBean private OutboxWriter outbox;

  private TaskWorld w;
  private Person ana;

  @BeforeEach
  void world() throws Exception {
    w = new TaskWorld(mvc, jdbc, lookups);
    ana = w.person("Ana", "Mukendi");
  }

  @AfterEach
  void resetSpy() {
    reset((OutboxWriter) AopTestUtils.getUltimateTargetObject(outbox));
  }

  private static String key() {
    return Organizations.newKey();
  }

  private List<MockHttpServletResponse> parallel(List<MockHttpServletRequestBuilder> calls)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(calls.size());
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
      for (MockHttpServletRequestBuilder call : calls) {
        futures.add(
            pool.submit(
                () -> {
                  go.await();
                  return w.call(call);
                }));
      }
      go.countDown();
      List<MockHttpServletResponse> responses = new ArrayList<>();
      for (Future<MockHttpServletResponse> future : futures) {
        responses.add(future.get(60, TimeUnit.SECONDS));
      }
      return responses;
    } finally {
      pool.shutdownNow();
    }
  }

  private int terminalActions(String id) {
    return w.count(
        "SELECT count(*) FROM operations.task_action WHERE task_id = ?"
            + " AND action IN ('COMPLETED', 'CANCELLED')",
        UUID.fromString(id));
  }

  @Test
  void concurrentCompleteAndCancelYieldExactlyOneTerminalAction() throws Exception {
    for (int round = 0; round < 4; round++) {
      String id = w.create(ana.employee(), "Course " + round).get("id").asText();
      List<MockHttpServletResponse> responses =
          parallel(
              List.of(
                  TaskWorld.completeRequest(ana.bearer(), id, key(), 1, null),
                  w.cancelRequest(id, key(), 1, "NO_LONGER_REQUIRED", null)));
      List<Integer> statuses = responses.stream().map(MockHttpServletResponse::getStatus).toList();
      assertThat(statuses).containsExactlyInAnyOrder(200, 409);
      assertThat(
              responses.stream()
                  .filter(r -> r.getStatus() == 409)
                  .findFirst()
                  .orElseThrow()
                  .getContentAsString())
          .containsAnyOf("TASK_VERSION_CONFLICT", "TASK_TRANSITION_NOT_ALLOWED");
      assertThat(terminalActions(id)).isEqualTo(1);
      String state = w.adminTask(id).get("state").asText();
      assertThat(state).isIn("COMPLETED", "CANCELLED");
      assertThat(w.adminTask(id).get("history")).hasSize(2);
      assertThat(
              w.count(
                  "SELECT count(*) FROM platform.outbox_event WHERE tenant_id = ? AND envelope ->>"
                      + " 'subject' = ? AND event_type <> 'operations.task.assigned.v1'",
                  w.tenant(),
                  id))
          .isEqualTo(1);
    }
  }

  @Test
  void identicalConcurrentRetriesExecuteOnce() throws Exception {
    String id = w.create(ana.employee(), "Retry").get("id").asText();
    String key = key();
    List<MockHttpServletResponse> responses =
        parallel(
            List.of(
                TaskWorld.startRequest(ana.bearer(), id, key, 1),
                TaskWorld.startRequest(ana.bearer(), id, key, 1),
                TaskWorld.startRequest(ana.bearer(), id, key, 1),
                TaskWorld.startRequest(ana.bearer(), id, key, 1)));
    long created = responses.stream().filter(r -> r.getStatus() == 200).count();
    assertThat(created).isGreaterThanOrEqualTo(1);
    assertThat(
            responses.stream()
                .filter(r -> r.getStatus() >= 300)
                .map(MockHttpServletResponse::getStatus))
        .allMatch(status -> status == 409);
    assertThat(
            w.count(
                "SELECT count(*) FROM operations.task_action WHERE task_id = ? AND action ="
                    + " 'STARTED'",
                UUID.fromString(id)))
        .isEqualTo(1);
    // Afterwards the same key replays the one original result.
    var replay = w.call(TaskWorld.startRequest(ana.bearer(), id, key, 1));
    assertThat(replay.getStatus()).isEqualTo(200);
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
  }

  @Test
  void failureWhileWritingTheEventRollsBackCreation() throws Exception {
    List<Integer> before = w.evidence();
    String key = key();
    failOutbox();
    var response = w.call(w.createRequest(key, TaskWorld.newTask(ana.employee(), "Annulée")));
    assertThat(response.getStatus()).isGreaterThanOrEqualTo(500);
    assertThat(w.evidence()).isEqualTo(before);
    assertThat(
            w.count(
                "SELECT count(*) FROM platform.idempotency_record WHERE idempotency_key = ?", key))
        .isZero();
    // The same key then succeeds once the failure is gone.
    reset((OutboxWriter) AopTestUtils.getUltimateTargetObject(outbox));
    assertThat(
            w.call(w.createRequest(key, TaskWorld.newTask(ana.employee(), "Annulée"))).getStatus())
        .isEqualTo(201);
  }

  @Test
  void failureWhileWritingTheEventRollsBackATransition() throws Exception {
    String id = w.create(ana.employee(), "Transition").get("id").asText();
    List<Integer> before = w.evidence();
    failOutbox();
    var start = w.call(TaskWorld.startRequest(ana.bearer(), id, key(), 1));
    var cancel = w.call(w.cancelRequest(id, key(), 1, "NO_LONGER_REQUIRED", null));
    var complete = w.call(TaskWorld.completeRequest(ana.bearer(), id, key(), 1, "note"));
    assertThat(List.of(start.getStatus(), cancel.getStatus(), complete.getStatus()))
        .allMatch(status -> status >= 500);
    assertThat(w.evidence()).isEqualTo(before);
    reset((OutboxWriter) AopTestUtils.getUltimateTargetObject(outbox));
    assertThat(w.adminTask(id).get("state").asText()).isEqualTo("OPEN");
    assertThat(w.adminTask(id).get("version").asInt()).isEqualTo(1);
    assertThat(w.adminTask(id).get("history")).hasSize(1);
    w.start(ana, id, 1);
  }

  private void failOutbox() {
    OutboxWriter target = AopTestUtils.getUltimateTargetObject(outbox);
    doThrow(new IllegalStateException("simulated outbox failure"))
        .when(target)
        .append(any(EventEnvelope.class));
  }
}
