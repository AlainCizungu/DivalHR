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
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.approval;
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.approve;
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.decision;
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.submission;
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.submit;
import static com.divalhr.core.people.schedule.TimesheetCalls.confirmed;
import static com.divalhr.core.people.schedule.TimesheetCalls.generate;
import static com.divalhr.core.people.schedule.TimesheetCalls.mine;
import static com.divalhr.core.people.schedule.TimesheetExportCalls.create;
import static com.divalhr.core.people.schedule.TimesheetExportCalls.creation;
import static com.divalhr.core.people.schedule.TimesheetExportCalls.csv;
import static com.divalhr.core.people.schedule.TimesheetExportCalls.manifest;
import static com.divalhr.core.people.schedule.TimesheetExportCalls.preview;
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
 * MVP-070E concurrency and atomicity against real PostgreSQL (T70E-11 items 4 and 11): creations
 * racing each other or an approval change serialize on the scheduling lock to one valid outcome; an
 * audit or outbox failure rolls the creation back, and the same command then applies; a failed
 * download audit returns no file. The spies match {@link
 * TimesheetApprovalConcurrencyIntegrationTest}, so they share one context.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
class TimesheetExportConcurrencyIntegrationTest {

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

  /** Ana's Monday 08-16 week, generated, submitted and approved by Mia; the clock its Wednesday. */
  private record Week(
      ScheduleWorld w,
      String admin,
      Person mia,
      Person ana,
      LocalDate monday,
      String timesheetId,
      String record,
      Instant[] shift) {

    Map<String, Object> scope() {
      return TimesheetExportCalls.scope(w.org.legalEntityId(), w.site(), monday);
    }
  }

  private Week approved() throws Exception {
    clock.reset();
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person mia = w.person("Mia", "Mbuyi");
    Person ana = w.person("Ana", "Kalala");
    w.manageAll(mia, ana);
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
    String admin = admin(w);
    String generationId =
        w.expect(
                generate(
                    admin,
                    Organizations.newKey(),
                    confirmed(TimesheetCalls.period(w.site(), monday))),
                200)
            .get("generation")
            .get("generationId")
            .asText();
    String timesheetId =
        jdbc.queryForObject(
            "SELECT id::text FROM people.timesheet WHERE generation_id = ?::uuid",
            String.class,
            generationId);
    JsonNode own = w.expect(mine(ana.bearer(), timesheetId), 200);
    w.expect(submit(ana.bearer(), Organizations.newKey(), timesheetId, submission(own)), 200);
    JsonNode reviewed = w.expect(approval(mia.bearer(), timesheetId), 200);
    w.expect(approve(mia.bearer(), Organizations.newKey(), timesheetId, decision(reviewed)), 200);
    return new Week(w, admin, mia, ana, monday, timesheetId, record, at);
  }

  private static String admin(ScheduleWorld w) {
    return Hierarchy.bearer(w.tenant(), "sub-tse-race-" + UUID.randomUUID(), "tenant-admin");
  }

  private int count(String sql, Object... args) {
    Integer n = jdbc.queryForObject(sql, Integer.class, args);
    return n == null ? 0 : n;
  }

  /** Exports, rows, creation audits and events of the tenant. */
  private List<Integer> written(Week t) {
    UUID tenant = t.w().tenant();
    return List.of(
        count("SELECT count(*) FROM people.timesheet_export WHERE tenant_id = ?", tenant),
        count("SELECT count(*) FROM people.timesheet_export_row WHERE tenant_id = ?", tenant),
        count(
            "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ? AND action ="
                + " 'timesheet-export.create'",
            tenant),
        count(
            "SELECT count(*) FROM platform.outbox_event WHERE envelope ->> 'tenantId' = ? AND"
                + " envelope ->> 'eventType' = 'people.timesheet-export.created.v1'",
            tenant.toString()));
  }

  private static List<String> parallel(ScheduleWorld w, List<MockHttpServletRequestBuilder> calls)
      throws Exception {
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
      List<String> codes = new ArrayList<>();
      for (Future<MockHttpServletResponse> future : futures) {
        MockHttpServletResponse response = future.get(60, TimeUnit.SECONDS);
        JsonNode body = JSON.readTree(response.getContentAsString());
        codes.add(
            response.getStatus() >= 300
                ? response.getStatus() + " " + body.get("code").asText()
                : body.has("outcome")
                    ? body.get("outcome").asText()
                    : String.valueOf(response.getStatus()));
      }
      return codes;
    } finally {
      pool.shutdownNow();
    }
  }

  // ------------------------------------------------------------------------------------------
  // Races
  // ------------------------------------------------------------------------------------------

  @Test
  void concurrentCreationsOfIdenticalEvidenceWriteOneExport() throws Exception {
    Week t = approved();
    JsonNode preview = t.w().expect(preview(t.admin(), t.scope()), 200);
    List<MockHttpServletRequestBuilder> calls = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      calls.add(create(admin(t.w()), Organizations.newKey(), creation(t.scope(), preview)));
    }
    List<String> outcomes = parallel(t.w(), calls);
    assertThat(outcomes).filteredOn("CREATED"::equals).hasSize(1);
    assertThat(outcomes).filteredOn("EXISTING"::equals).hasSize(2);
    assertThat(written(t)).containsExactly(1, 1, 1, 1);
  }

  @Test
  void aCreationRacingAReopeningNeverExportsTheSupersededApproval() throws Exception {
    Week t = approved();
    String approvalId =
        jdbc.queryForObject(
            "SELECT current_action_id::text FROM people.timesheet WHERE id = ?::uuid",
            String.class,
            t.timesheetId());
    JsonNode preview = t.w().expect(preview(t.admin(), t.scope()), 200);
    Map<String, Object> fix = values(t.shift()[0].plus(Duration.ofMinutes(20)), t.shift()[1]);
    JsonNode reviewed = t.w().expect(previewRecord(t.admin(), t.record(), fix), 200);
    List<String> outcomes =
        parallel(
            t.w(),
            List.of(
                create(admin(t.w()), Organizations.newKey(), creation(t.scope(), preview)),
                commitRecord(
                    t.admin(), Organizations.newKey(), t.record(), commit(fix, reviewed))));
    // The export first: it holds the approval that was current, and the correction then reopens
    // it (the export stays as it is). The correction first: the export's preview is stale.
    assertThat(outcomes.get(1)).isEqualTo("201");
    if ("CREATED".equals(outcomes.get(0))) {
      assertThat(
              count(
                  "SELECT count(*) FROM people.timesheet_export_row WHERE approval_action_id ="
                      + " ?::uuid",
                  approvalId))
          .isEqualTo(1);
    } else {
      assertThat(outcomes.get(0)).isEqualTo("409 TIMESHEET_EXPORT_PREVIEW_STALE");
      assertThat(written(t)).containsExactly(0, 0, 0, 0);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM people.timesheet WHERE id = ?::uuid",
                String.class,
                t.timesheetId()))
        .isEqualTo("REOPENED");
    // After the reopening nothing is eligible: the superseded approval is never exported.
    JsonNode after = t.w().expect(preview(t.admin(), t.scope()), 200);
    assertThat(after.get("eligible").get("timesheets").asInt()).isZero();
    t.w()
        .problem(
            create(admin(t.w()), Organizations.newKey(), creation(t.scope(), after)),
            409,
            "TIMESHEET_EXPORT_EMPTY");
  }

  // ------------------------------------------------------------------------------------------
  // 11: atomic creation, fail-closed downloads
  // ------------------------------------------------------------------------------------------

  private void rolledBack(Consumer<String> fail, Runnable heal, String step) throws Exception {
    Week t = approved();
    JsonNode preview = t.w().expect(preview(t.admin(), t.scope()), 200);
    String key = Organizations.newKey();
    fail.accept(step);
    try {
      assertThat(t.w().call(create(t.admin(), key, creation(t.scope(), preview))).getStatus())
          .isEqualTo(500);
    } finally {
      heal.run();
    }
    // Neither the header, nor a row, nor the audit or event was kept.
    assertThat(written(t)).containsExactly(0, 0, 0, 0);
    MockHttpServletResponse applied =
        t.w().call(create(t.admin(), key, creation(t.scope(), preview)));
    assertThat(applied.getStatus()).isEqualTo(200);
    assertThat(applied.getHeader("Idempotent-Replayed")).isNull();
    assertThat(JSON.readTree(applied.getContentAsString()).get("outcome").asText())
        .isEqualTo("CREATED");
    assertThat(written(t)).containsExactly(1, 1, 1, 1);
  }

  @Test
  void anOutboxOrAuditFailureRollsTheCreationBack() throws Exception {
    OutboxWriter events = AopTestUtils.getUltimateTargetObject(outbox);
    rolledBack(
        step ->
            doThrow(new IllegalStateException("simulated outbox failure"))
                .when(events)
                .append(
                    argThat(
                        (EventEnvelope event) -> event != null && step.equals(event.eventType()))),
        () -> {
          org.mockito.Mockito.reset(events);
          doCallRealMethod().when(events).append(any(EventEnvelope.class));
        },
        "people.timesheet-export.created.v1");
    AuditRecorder records = AopTestUtils.getUltimateTargetObject(audit);
    rolledBack(
        step ->
            doThrow(new IllegalStateException("simulated audit failure"))
                .when(records)
                .record(
                    argThat((AuditEvent event) -> event != null && step.equals(event.action()))),
        () -> {
          org.mockito.Mockito.reset(records);
          doCallRealMethod().when(records).record(any(AuditEvent.class));
        },
        "timesheet-export.create");
  }

  @Test
  void aFailedDownloadAuditReturnsNoFile() throws Exception {
    Week t = approved();
    JsonNode preview = t.w().expect(preview(t.admin(), t.scope()), 200);
    String exportId =
        t.w()
            .expect(create(t.admin(), Organizations.newKey(), creation(t.scope(), preview)), 200)
            .get("export")
            .get("exportId")
            .asText();
    AuditRecorder records = AopTestUtils.getUltimateTargetObject(audit);
    doThrow(new IllegalStateException("simulated audit failure"))
        .when(records)
        .record(
            argThat(
                (AuditEvent event) ->
                    event != null && "timesheet-export.download".equals(event.action())));
    try {
      for (MockHttpServletRequestBuilder call :
          List.of(csv(t.admin(), exportId), manifest(t.admin(), exportId))) {
        MockHttpServletResponse failed = t.w().call(call);
        assertThat(failed.getStatus()).isEqualTo(500);
        assertThat(failed.getContentType()).startsWith("application/problem+json");
        assertThat(failed.getContentAsString())
            .doesNotContain("schema_version")
            .doesNotContain(t.timesheetId())
            .doesNotContain("csvSha256");
        assertThat(failed.getHeader("Content-Disposition")).isNull();
      }
    } finally {
      org.mockito.Mockito.reset(records);
      doCallRealMethod().when(records).record(any(AuditEvent.class));
    }
    assertThat(
            count(
                "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ? AND action ="
                    + " 'timesheet-export.download'",
                t.w().tenant()))
        .isZero();
    assertThat(t.w().call(csv(t.admin(), exportId)).getStatus()).isEqualTo(200);
  }
}
