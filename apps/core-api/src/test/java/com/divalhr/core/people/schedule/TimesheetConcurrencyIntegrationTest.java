package com.divalhr.core.people.schedule;

import static com.divalhr.core.people.schedule.AttendanceCalls.checkIn;
import static com.divalhr.core.people.schedule.AttendanceCalls.checkOut;
import static com.divalhr.core.people.schedule.AttendanceCalls.instants;
import static com.divalhr.core.people.schedule.CorrectionCalls.commit;
import static com.divalhr.core.people.schedule.CorrectionCalls.commitRecord;
import static com.divalhr.core.people.schedule.CorrectionCalls.previewRecord;
import static com.divalhr.core.people.schedule.CorrectionCalls.values;
import static com.divalhr.core.people.schedule.ScheduleWorld.JSON;
import static com.divalhr.core.people.schedule.ScheduleWorld.proposal;
import static com.divalhr.core.people.schedule.ScheduleWorld.shift;
import static com.divalhr.core.people.schedule.SubstitutionCalls.slotIds;
import static com.divalhr.core.people.schedule.TimesheetCalls.confirmed;
import static com.divalhr.core.people.schedule.TimesheetCalls.generate;
import static com.divalhr.core.people.schedule.TimesheetCalls.previewRefresh;
import static com.divalhr.core.people.schedule.TimesheetCalls.refresh;
import static com.divalhr.core.people.schedule.TimesheetCalls.refreshBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.people.schedule.ScheduleWorld.Person;
import com.divalhr.core.people.schedule.domain.Timesheet;
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
import java.time.LocalTime;
import java.time.ZoneId;
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
 * MVP-070A concurrency and atomicity against real PostgreSQL (T70A-6, T70A-7, T70A-12 items 7, 11
 * and 14): concurrent generations of one week give one generation; concurrent commits of one
 * previewed refresh give one revision and stale refusals; a refresh racing an attendance correction
 * leaves drafts that a fresh preview brings to the source; an audit or outbox failure rolls the
 * whole generation back, and the same command then applies, not replays. The spies match {@link
 * AttendanceCorrectionConcurrencyIntegrationTest}, so both share one context.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
class TimesheetConcurrencyIntegrationTest {

  private static final int PARALLEL = 4;
  private static final ZoneId KINSHASA = ZoneId.of("Africa/Kinshasa");

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

  /** Ana worked Monday 08-16 of the week (checked in and out); the clock is Wednesday noon. */
  private record Week(
      ScheduleWorld w, Person ana, LocalDate monday, String slot, Instant[] at, String record) {

    Map<String, Object> period() {
      return TimesheetCalls.period(w.site(), monday);
    }
  }

  private Week fresh() throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person ana = w.person("Ana", "Kalala");
    LocalDate monday = Timesheet.Period.mondayOf(w.today).plusDays(7);
    String slot =
        slotIds(
                w,
                w.published(
                    proposal(
                        w.site(),
                        monday,
                        monday,
                        List.of(shift("a", monday, 8, 16, ana.employee())))))
            .get(0);
    Instant[] at = instants(jdbc, slot);
    clock.set(at[0].plus(Duration.ofMinutes(3)));
    String record =
        w.expect(checkIn(ana, Organizations.newKey(), slot), 201)
            .get("attendanceRecordId")
            .asText();
    clock.set(at[1].minus(Duration.ofMinutes(2)));
    w.expect(checkOut(ana, Organizations.newKey(), record), 200);
    clock.set(monday.plusDays(2).atTime(LocalTime.NOON).atZone(KINSHASA).toInstant());
    return new Week(w, ana, monday, slot, at, record);
  }

  private static String admin(ScheduleWorld w) {
    return Hierarchy.bearer(w.tenant(), "sub-ts-race-" + UUID.randomUUID(), "tenant-admin");
  }

  private int count(String sql, Object... args) {
    Integer n = jdbc.queryForObject(sql, Integer.class, args);
    return n == null ? 0 : n;
  }

  /** Generations, drafts, revisions, rows, timesheet audit records and outbox events. */
  private List<Integer> written(UUID tenant) {
    return List.of(
        count("SELECT count(*) FROM people.timesheet_generation WHERE tenant_id = ?", tenant),
        count("SELECT count(*) FROM people.timesheet WHERE tenant_id = ?", tenant),
        count("SELECT count(*) FROM people.timesheet_revision WHERE tenant_id = ?", tenant),
        count("SELECT count(*) FROM people.timesheet_row WHERE tenant_id = ?", tenant),
        count(
            "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?"
                + " AND action IN ('timesheet.generate', 'timesheet.refresh')",
            tenant),
        count(
            "SELECT count(*) FROM platform.outbox_event WHERE envelope ->> 'tenantId' = ?"
                + " AND envelope ->> 'eventType' LIKE 'people.timesheet.%'",
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
    JsonNode body = JSON.readTree(response.getContentAsString());
    int status = response.getStatus();
    if (status == 200) {
      return body.has("outcome") ? "200 " + body.get("outcome").asText() : "200";
    }
    if (status == 201) {
      return "201";
    }
    return status + " " + body.get("code").asText();
  }

  private String generated(Week t) throws Exception {
    return t.w()
        .expect(generate(admin(t.w()), Organizations.newKey(), confirmed(t.period())), 200)
        .get("generation")
        .get("generationId")
        .asText();
  }

  @Test
  void concurrentGenerationsOfOneWeekGiveOneGeneration() throws Exception {
    Week t = fresh();
    List<MockHttpServletRequestBuilder> calls = new ArrayList<>();
    for (int i = 0; i < PARALLEL; i++) {
      calls.add(generate(admin(t.w()), Organizations.newKey(), confirmed(t.period())));
    }
    List<String> codes = new ArrayList<>();
    for (MockHttpServletResponse response : parallel(t.w(), calls)) {
      codes.add(code(response));
    }
    assertThat(codes).filteredOn("200 CREATED"::equals).hasSize(1);
    assertThat(codes).filteredOn(c -> !"200 CREATED".equals(c)).allMatch("200 EXISTING"::equals);
    assertThat(written(t.w().tenant())).containsExactly(1, 1, 1, 1, 1, 1);
  }

  @Test
  void concurrentCommitsOfOnePreviewedRefreshGiveOneRevision() throws Exception {
    Week t = fresh();
    String id = generated(t);
    // The source changes, then each administrator previews the same refresh.
    String hr = admin(t.w());
    Map<String, Object> correction =
        values(t.at()[0], t.at()[0].plus(Duration.ofHours(7)).plusSeconds(30));
    JsonNode reviewed = t.w().expect(previewRecord(hr, t.record(), correction), 200);
    t.w()
        .expect(
            commitRecord(hr, Organizations.newKey(), t.record(), commit(correction, reviewed)),
            201);
    List<MockHttpServletRequestBuilder> calls = new ArrayList<>();
    for (int i = 0; i < PARALLEL; i++) {
      String admin = admin(t.w());
      JsonNode preview = t.w().expect(previewRefresh(admin, id, t.period()), 200);
      assertThat(preview.get("changedTimesheets").asInt()).isEqualTo(1);
      calls.add(refresh(admin, Organizations.newKey(), id, refreshBody(t.period(), preview)));
    }
    List<String> codes = new ArrayList<>();
    for (MockHttpServletResponse response : parallel(t.w(), calls)) {
      codes.add(code(response));
    }
    assertThat(codes).filteredOn("200"::equals).hasSize(1);
    assertThat(codes)
        .filteredOn(c -> !"200".equals(c))
        .allMatch("409 TIMESHEET_REFRESH_PREVIEW_STALE"::equals);
    assertThat(written(t.w().tenant())).containsExactly(1, 1, 2, 2, 2, 2);
    assertThat(count("SELECT version FROM people.timesheet_generation WHERE id = ?::uuid", id))
        .isEqualTo(2);
  }

  @Test
  void aRefreshRacingACorrectionLeavesDraftsAFreshPreviewBringsToTheSource() throws Exception {
    Week t = fresh();
    String id = generated(t);
    String admin = admin(t.w());
    // An unrelated change makes the refresh non-empty.
    Map<String, Object> first = values(t.at()[0], t.at()[1].minus(Duration.ofMinutes(10)));
    String hr = admin(t.w());
    JsonNode reviewed = t.w().expect(previewRecord(hr, t.record(), first), 200);
    t.w()
        .expect(commitRecord(hr, Organizations.newKey(), t.record(), commit(first, reviewed)), 201);
    JsonNode preview = t.w().expect(previewRefresh(admin, id, t.period()), 200);
    Map<String, Object> second = values(t.at()[0], t.at()[1].minus(Duration.ofMinutes(20)));
    String hr2 = admin(t.w());
    JsonNode reviewed2 = t.w().expect(previewRecord(hr2, t.record(), second), 200);
    List<String> codes = new ArrayList<>();
    for (MockHttpServletResponse response :
        parallel(
            t.w(),
            List.of(
                refresh(admin, Organizations.newKey(), id, refreshBody(t.period(), preview)),
                commitRecord(
                    hr2, Organizations.newKey(), t.record(), commit(second, reviewed2))))) {
      codes.add(code(response));
    }
    // Either the refresh committed the reviewed source, or the correction overtook its preview.
    assertThat(codes.get(1)).isEqualTo("201");
    assertThat(codes.get(0)).isIn("200", "409 TIMESHEET_REFRESH_PREVIEW_STALE");
    // A fresh preview and commit bring the draft to the current source.
    JsonNode fresh = t.w().expect(previewRefresh(admin, id, t.period()), 200);
    assertThat(fresh.get("changedTimesheets").asInt()).isEqualTo(1);
    t.w().expect(refresh(admin, Organizations.newKey(), id, refreshBody(t.period(), fresh)), 200);
    assertThat(
            count(
                "SELECT t.worked_minutes FROM people.timesheet t WHERE t.generation_id = ?::uuid",
                id))
        .isEqualTo(460);
    JsonNode quiet = t.w().expect(previewRefresh(admin, id, t.period()), 200);
    assertThat(quiet.get("changedTimesheets").asInt()).isZero();
  }

  private void rolledBack(Consumer<String> fail, Runnable heal, String step) throws Exception {
    Week t = fresh();
    String admin = admin(t.w());
    String key = Organizations.newKey();
    fail.accept(step);
    try {
      assertThat(t.w().call(generate(admin, key, confirmed(t.period()))).getStatus())
          .isEqualTo(500);
    } finally {
      heal.run();
    }
    assertThat(written(t.w().tenant())).containsExactly(0, 0, 0, 0, 0, 0);
    // Nothing was kept, so the same command (same key) applies now, not replayed.
    MockHttpServletResponse applied = t.w().call(generate(admin, key, confirmed(t.period())));
    assertThat(applied.getStatus()).isEqualTo(200);
    assertThat(applied.getHeader("Idempotent-Replayed")).isNull();
    assertThat(code(applied)).isEqualTo("200 CREATED");
    assertThat(written(t.w().tenant())).containsExactly(1, 1, 1, 1, 1, 1);
  }

  @Test
  void anOutboxFailureRollsTheWholeGenerationBack() throws Exception {
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
        "people.timesheet.generated.v1");
  }

  @Test
  void anAuditFailureRollsTheWholeGenerationBack() throws Exception {
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
        "timesheet.generate");
  }
}
