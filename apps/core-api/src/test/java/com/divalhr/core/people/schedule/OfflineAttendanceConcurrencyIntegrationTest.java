package com.divalhr.core.people.schedule;

import static com.divalhr.core.people.schedule.AttendanceCalls.instants;
import static com.divalhr.core.people.schedule.AttendanceCalls.offline;
import static com.divalhr.core.people.schedule.AttendanceCalls.sync;
import static com.divalhr.core.people.schedule.ScheduleWorld.JSON;
import static com.divalhr.core.people.schedule.ScheduleWorld.proposal;
import static com.divalhr.core.people.schedule.ScheduleWorld.shift;
import static com.divalhr.core.people.schedule.SubstitutionCalls.slotIds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.people.schedule.ScheduleWorld.Person;
import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.idempotency.IdempotencyService;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-061A concurrency and atomicity against real PostgreSQL (A61A-2, A61A-7, A61A-9 items 8 and
 * 13): racing identical submissions give one event and one result, the others replaying it; racing
 * different events for one shift leave one valid history; an audit or outbox failure rolls the
 * whole application back (event, provenance, record, audit, outbox), and the same submission is
 * then applied, not replayed. The spies match {@link AttendanceConcurrencyIntegrationTest}, so both
 * share one application context.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
class OfflineAttendanceConcurrencyIntegrationTest {

  // Within the subject's write limit (10 per minute).
  private static final int PARALLEL = 5;
  private static final int KINSHASA = 60;

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;
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

  /** Ana works 08-16 on the day; the clock stands at the start. */
  private record Shift(ScheduleWorld w, Person ana, String slot, Instant[] at) {}

  private Shift fresh(int inDays) throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person ana = w.person("Ana", "Kalala");
    LocalDate day = w.today.plusDays(inDays);
    String schedule =
        w.published(proposal(w.site(), day, day, List.of(shift("a", day, 8, 16, ana.employee()))));
    String slot = slotIds(w, schedule).get(0);
    Instant[] at = instants(jdbc, slot);
    clock.set(at[0]);
    return new Shift(w, ana, slot, at);
  }

  private int count(String sql, Object... args) {
    Integer n = jdbc.queryForObject(sql, Integer.class, args);
    return n == null ? 0 : n;
  }

  /** Events, records, offline audit records and attendance outbox events of the tenant. */
  private List<Integer> written(UUID tenant) {
    return List.of(
        count("SELECT count(*) FROM people.attendance_event WHERE tenant_id = ?", tenant),
        count("SELECT count(*) FROM people.attendance_record WHERE tenant_id = ?", tenant),
        count(
            "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?"
                + " AND action = 'attendance.offline-event-sync'",
            tenant),
        count(
            "SELECT count(*) FROM platform.outbox_event WHERE envelope ->> 'tenantId' = ?"
                + " AND envelope ->> 'eventType' LIKE 'people.attendance.%'",
            tenant.toString()));
  }

  private static List<MockHttpServletResponse> parallel(
      ScheduleWorld w, List<MockHttpServletRequestBuilder> calls) throws Exception {
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
    int status = response.getStatus();
    return status == 200 || status == 201
        ? Integer.toString(status)
        : status + " " + JSON.readTree(response.getContentAsString()).get("code").asText();
  }

  @Test
  void racingIdenticalSubmissionsGiveOneEventAndOneResult() throws Exception {
    Shift s = fresh(3);
    Map<String, Object> body =
        offline(
            UUID.randomUUID().toString(),
            s.slot(),
            "CHECK_IN",
            s.at()[0].minus(Duration.ofMinutes(20)),
            KINSHASA);
    List<MockHttpServletRequestBuilder> calls = new ArrayList<>();
    for (int i = 0; i < PARALLEL; i++) {
      calls.add(sync(s.ana().bearer(), body));
    }
    List<MockHttpServletResponse> responses = parallel(s.w(), calls);
    List<String> codes = new ArrayList<>();
    List<JsonNode> results = new ArrayList<>();
    for (MockHttpServletResponse response : responses) {
      codes.add(code(response));
      JsonNode result = JSON.readTree(response.getContentAsString());
      ((com.fasterxml.jackson.databind.node.ObjectNode) result).remove("outcome");
      results.add(result);
    }
    assertThat(codes).filteredOn("201"::equals).hasSize(1);
    assertThat(codes).filteredOn("200"::equals).hasSize(PARALLEL - 1);
    assertThat(results).allMatch(results.get(0)::equals);
    assertThat(written(s.w().tenant())).containsExactly(1, 1, 1, 1);
  }

  @Test
  void racingDifferentEventsForOneShiftLeaveOneValidHistory() throws Exception {
    Shift s = fresh(3);
    List<MockHttpServletRequestBuilder> calls = new ArrayList<>();
    for (int i = 0; i < PARALLEL; i++) {
      calls.add(
          sync(
              s.ana().bearer(),
              offline(
                  UUID.randomUUID().toString(),
                  s.slot(),
                  "CHECK_IN",
                  s.at()[0].minus(Duration.ofMinutes(10L + i)),
                  KINSHASA)));
    }
    List<String> codes = new ArrayList<>();
    for (MockHttpServletResponse response : parallel(s.w(), calls)) {
      codes.add(code(response));
    }
    assertThat(codes).filteredOn("201"::equals).hasSize(1);
    assertThat(codes)
        .filteredOn(c -> !"201".equals(c))
        .allMatch("409 OFFLINE_ATTENDANCE_CONFLICT"::equals);
    assertThat(written(s.w().tenant())).containsExactly(1, 1, 1, 1);
    assertThat(
            count(
                "SELECT count(*) FROM people.attendance_record r JOIN people.attendance_event e"
                    + " ON e.tenant_id = r.tenant_id AND e.id = r.check_in_event_id"
                    + " WHERE r.slot_id = ?::uuid AND e.recorded_at = r.checked_in_at",
                s.slot()))
        .isEqualTo(1);
  }

  private void rolledBack(Consumer<String> fail, Runnable heal, String step) throws Exception {
    Shift s = fresh(3);
    Map<String, Object> body =
        offline(
            UUID.randomUUID().toString(),
            s.slot(),
            "CHECK_IN",
            s.at()[0].minus(Duration.ofMinutes(20)),
            KINSHASA);
    fail.accept(step);
    try {
      assertThat(s.w().call(sync(s.ana().bearer(), body)).getStatus()).isEqualTo(500);
    } finally {
      heal.run();
    }
    assertThat(written(s.w().tenant())).containsExactly(0, 0, 0, 0);
    // Nothing was kept, so the same submission is applied now, not replayed.
    JsonNode applied = s.w().expect(sync(s.ana().bearer(), body), 201);
    assertThat(applied.get("outcome").asText()).isEqualTo("APPLIED");
    assertThat(written(s.w().tenant())).containsExactly(1, 1, 1, 1);
  }

  @Test
  void anOutboxFailureRollsTheWholeApplicationBack() throws Exception {
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
  void anAuditFailureRollsTheWholeApplicationBack() throws Exception {
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
        "attendance.offline-event-sync");
  }
}
