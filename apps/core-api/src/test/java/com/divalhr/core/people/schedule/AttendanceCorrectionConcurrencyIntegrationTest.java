package com.divalhr.core.people.schedule;

import static com.divalhr.core.people.schedule.AttendanceCalls.checkIn;
import static com.divalhr.core.people.schedule.AttendanceCalls.checkOut;
import static com.divalhr.core.people.schedule.AttendanceCalls.instants;
import static com.divalhr.core.people.schedule.CorrectionCalls.commit;
import static com.divalhr.core.people.schedule.CorrectionCalls.commitMissing;
import static com.divalhr.core.people.schedule.CorrectionCalls.commitRecord;
import static com.divalhr.core.people.schedule.CorrectionCalls.missing;
import static com.divalhr.core.people.schedule.CorrectionCalls.previewMissing;
import static com.divalhr.core.people.schedule.CorrectionCalls.previewRecord;
import static com.divalhr.core.people.schedule.CorrectionCalls.values;
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
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
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
 * MVP-062 concurrency and atomicity against real PostgreSQL (C62-5, C62-8, C62-11 items 8 and 13):
 * concurrent corrections of one previewed version give one correction and stale refusals; a
 * correction racing the employee's check-out leaves one valid history; concurrent creations of one
 * missing record give one record; an audit or outbox failure rolls the whole correction back
 * (correction, projection, audit, outbox), and the same commit then applies, not replays. The spies
 * match {@link OfflineAttendanceConcurrencyIntegrationTest}, so both share one context.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
class AttendanceCorrectionConcurrencyIntegrationTest {

  private static final int PARALLEL = 4;

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

  /** Ana works 08-16 on the day. */
  private record Shift(ScheduleWorld w, Person ana, String slot, Instant[] at) {}

  private Shift fresh(int inDays) throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person ana = w.person("Ana", "Kalala");
    LocalDate day = w.today.plusDays(inDays);
    String schedule =
        w.published(proposal(w.site(), day, day, List.of(shift("a", day, 8, 16, ana.employee()))));
    String slot = slotIds(w, schedule).get(0);
    return new Shift(w, ana, slot, instants(jdbc, slot));
  }

  private static String admin(ScheduleWorld w) {
    return Hierarchy.bearer(w.tenant(), "sub-corr-race-" + UUID.randomUUID(), "tenant-admin");
  }

  private int count(String sql, Object... args) {
    Integer n = jdbc.queryForObject(sql, Integer.class, args);
    return n == null ? 0 : n;
  }

  /** Corrections, records, correction audit records and correction outbox events. */
  private List<Integer> written(UUID tenant) {
    return List.of(
        count("SELECT count(*) FROM people.attendance_correction WHERE tenant_id = ?", tenant),
        count("SELECT count(*) FROM people.attendance_record WHERE tenant_id = ?", tenant),
        count(
            "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?"
                + " AND action = 'attendance.correction-create'",
            tenant),
        count(
            "SELECT count(*) FROM platform.outbox_event WHERE envelope ->> 'tenantId' = ?"
                + " AND envelope ->> 'eventType' = 'people.attendance.corrected.v1'",
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
  void concurrentCorrectionsOfOneVersionGiveOneCorrectionAndStaleRefusals() throws Exception {
    Shift s = fresh(3);
    clock.set(s.at()[0].plus(Duration.ofMinutes(25)));
    String record =
        s.w()
            .expect(checkIn(s.ana(), Organizations.newKey(), s.slot()), 201)
            .get("attendanceRecordId")
            .asText();
    clock.set(s.at()[1].plus(Duration.ofHours(2)));
    List<MockHttpServletRequestBuilder> calls = new ArrayList<>();
    for (int i = 0; i < PARALLEL; i++) {
      // Each administrator previews a different correction of version 0.
      String admin = admin(s.w());
      Map<String, Object> proposal = values(s.at()[0], s.at()[1].minus(Duration.ofMinutes(i)));
      JsonNode preview = s.w().expect(previewRecord(admin, record, proposal), 200);
      calls.add(commitRecord(admin, Organizations.newKey(), record, commit(proposal, preview)));
    }
    List<String> codes = new ArrayList<>();
    for (MockHttpServletResponse response : parallel(s.w(), calls)) {
      codes.add(code(response));
    }
    assertThat(codes).filteredOn("201"::equals).hasSize(1);
    assertThat(codes)
        .filteredOn(c -> !"201".equals(c))
        .allMatch("409 ATTENDANCE_CORRECTION_PREVIEW_STALE"::equals);
    assertThat(written(s.w().tenant())).containsExactly(1, 1, 1, 1);
    assertThat(
            count(
                "SELECT count(*) FROM people.attendance_record WHERE id = ?::uuid"
                    + " AND correction_version = 1",
                record))
        .isEqualTo(1);
  }

  @Test
  void concurrentCreationsOfOneMissingRecordGiveOneRecord() throws Exception {
    Shift s = fresh(3);
    clock.set(s.at()[1].plus(Duration.ofHours(2)));
    List<MockHttpServletRequestBuilder> calls = new ArrayList<>();
    for (int i = 0; i < PARALLEL; i++) {
      String admin = admin(s.w());
      Map<String, Object> proposal =
          missing(s.slot(), s.ana().employee(), s.at()[0].plus(Duration.ofMinutes(i)), null);
      JsonNode preview = s.w().expect(previewMissing(admin, proposal), 200);
      calls.add(commitMissing(admin, Organizations.newKey(), commit(proposal, preview)));
    }
    List<String> codes = new ArrayList<>();
    for (MockHttpServletResponse response : parallel(s.w(), calls)) {
      codes.add(code(response));
    }
    assertThat(codes).filteredOn("201"::equals).hasSize(1);
    assertThat(codes)
        .filteredOn(c -> !"201".equals(c))
        .allMatch("409 ATTENDANCE_CORRECTION_PREVIEW_STALE"::equals);
    assertThat(written(s.w().tenant())).containsExactly(1, 1, 1, 1);
  }

  @Test
  void aCorrectionRacingTheEmployeesCheckOutLeavesOneValidHistory() throws Exception {
    Shift s = fresh(3);
    clock.set(s.at()[0].plus(Duration.ofMinutes(25)));
    String record =
        s.w()
            .expect(checkIn(s.ana(), Organizations.newKey(), s.slot()), 201)
            .get("attendanceRecordId")
            .asText();
    clock.set(s.at()[1]);
    String admin = admin(s.w());
    Map<String, Object> proposal = values(s.at()[0], s.at()[1].minus(Duration.ofMinutes(1)));
    JsonNode preview = s.w().expect(previewRecord(admin, record, proposal), 200);
    List<String> codes = new ArrayList<>();
    for (MockHttpServletResponse response :
        parallel(
            s.w(),
            List.of(
                commitRecord(admin, Organizations.newKey(), record, commit(proposal, preview)),
                checkOut(s.ana(), Organizations.newKey(), record)))) {
      codes.add(code(response));
    }
    // Either order is one valid history: the correction first (the employee's check-out is then
    // refused: a check-out exists), or the check-out first (the preview is then stale).
    assertThat(codes)
        .isIn(
            List.of("201", "409 ATTENDANCE_ALREADY_CHECKED_OUT"),
            List.of("409 ATTENDANCE_CORRECTION_PREVIEW_STALE", "200"));
    assertThat(
            count(
                "SELECT count(*) FROM people.attendance_record WHERE id = ?::uuid"
                    + " AND status = 'COMPLETED'",
                record))
        .isEqualTo(1);
  }

  private void rolledBack(Consumer<String> fail, Runnable heal, String step) throws Exception {
    Shift s = fresh(3);
    clock.set(s.at()[1].plus(Duration.ofHours(2)));
    String admin = admin(s.w());
    Map<String, Object> proposal = missing(s.slot(), s.ana().employee(), s.at()[0], s.at()[1]);
    JsonNode preview = s.w().expect(previewMissing(admin, proposal), 200);
    Map<String, Object> body = commit(proposal, preview);
    String key = Organizations.newKey();
    fail.accept(step);
    try {
      assertThat(s.w().call(commitMissing(admin, key, body)).getStatus()).isEqualTo(500);
    } finally {
      heal.run();
    }
    assertThat(written(s.w().tenant())).containsExactly(0, 0, 0, 0);
    // Nothing was kept, so the same commit (same key) applies now, not replayed.
    MockHttpServletResponse applied = s.w().call(commitMissing(admin, key, body));
    assertThat(applied.getStatus()).isEqualTo(201);
    assertThat(applied.getHeader("Idempotent-Replayed")).isNull();
    assertThat(written(s.w().tenant())).containsExactly(1, 1, 1, 1);
  }

  @Test
  void anOutboxFailureRollsTheWholeCorrectionBack() throws Exception {
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
        "people.attendance.corrected.v1");
  }

  @Test
  void anAuditFailureRollsTheWholeCorrectionBack() throws Exception {
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
        "attendance.correction-create");
  }
}
