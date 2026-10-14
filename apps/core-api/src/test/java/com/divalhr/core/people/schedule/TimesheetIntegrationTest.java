package com.divalhr.core.people.schedule;

import static com.divalhr.core.people.schedule.AttendanceCalls.checkIn;
import static com.divalhr.core.people.schedule.AttendanceCalls.checkOut;
import static com.divalhr.core.people.schedule.AttendanceCalls.instants;
import static com.divalhr.core.people.schedule.CorrectionCalls.commit;
import static com.divalhr.core.people.schedule.CorrectionCalls.commitRecord;
import static com.divalhr.core.people.schedule.CorrectionCalls.previewRecord;
import static com.divalhr.core.people.schedule.CorrectionCalls.values;
import static com.divalhr.core.people.schedule.ScheduleWorld.JSON;
import static com.divalhr.core.people.schedule.ScheduleWorld.cancel;
import static com.divalhr.core.people.schedule.ScheduleWorld.proposal;
import static com.divalhr.core.people.schedule.ScheduleWorld.shift;
import static com.divalhr.core.people.schedule.ScheduleWorld.slot;
import static com.divalhr.core.people.schedule.SubstitutionCalls.approve;
import static com.divalhr.core.people.schedule.SubstitutionCalls.request;
import static com.divalhr.core.people.schedule.SubstitutionCalls.slotIds;
import static com.divalhr.core.people.schedule.TimesheetCalls.confirmed;
import static com.divalhr.core.people.schedule.TimesheetCalls.generate;
import static com.divalhr.core.people.schedule.TimesheetCalls.generation;
import static com.divalhr.core.people.schedule.TimesheetCalls.mine;
import static com.divalhr.core.people.schedule.TimesheetCalls.period;
import static com.divalhr.core.people.schedule.TimesheetCalls.previewGeneration;
import static com.divalhr.core.people.schedule.TimesheetCalls.previewRefresh;
import static com.divalhr.core.people.schedule.TimesheetCalls.refresh;
import static com.divalhr.core.people.schedule.TimesheetCalls.refreshBody;
import static com.divalhr.core.people.schedule.TimesheetCalls.timesheet;
import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.people.schedule.ScheduleWorld.Person;
import com.divalhr.core.people.schedule.domain.Timesheet;
import com.divalhr.core.support.Bootstraps;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.divalhr.core.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-070A (Issue #128, T70A-12 items 1 to 15 and 17 to 19) against real PostgreSQL: a site-local
 * week generated from operational schedules, effective assignees and the effective (corrected)
 * attendance; the dated placement's cost center; the closed row states and truncated minutes; the
 * idempotent generation and the existing week; a refresh preview that writes nothing and a commit
 * that needs the identical fresh preview, appending revisions; exact-MFA tenant administrators
 * only, inside their tenant; employees reading only their own drafts; and the privacy of audit
 * metadata, events and logs.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class TimesheetIntegrationTest {

  private static final ZoneId KINSHASA = ZoneId.of("Africa/Kinshasa");

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;

  @AfterEach
  void reset() {
    clock.reset();
  }

  private static String hr(ScheduleWorld w) {
    return Hierarchy.bearer(w.tenant(), "sub-ts-" + UUID.randomUUID(), "tenant-admin");
  }

  private static String key() {
    return Organizations.newKey();
  }

  /**
   * The week under test, published before it starts and lived through with a settable clock.
   *
   * <ul>
   *   <li>Ana: Monday (s0) checked in and out, then corrected; Tuesday (s1) no attendance (her
   *       placement carries the cost center from Tuesday on); Wednesday (s2) checked in only;
   *       Sunday night (s5) still ahead; the next Monday (s6) outside the week.
   *   <li>Ben: Thursday (s3), substituted by Cid before it started.
   *   <li>Eve: Friday (s4), but she moved to the other site from Friday on (no qualifying
   *       employment).
   *   <li>Dan: Saturday, in a schedule that was cancelled.
   * </ul>
   */
  private record Week(
      ScheduleWorld w,
      Person mia,
      Person ana,
      Person ben,
      Person cid,
      Person dan,
      Person eve,
      LocalDate monday,
      List<String> slots,
      String cancelled,
      String r0,
      String r2,
      UUID costCenter) {

    String slot(int i) {
      return slots.get(i);
    }

    Map<String, Object> period() {
      return TimesheetCalls.period(w.site(), monday);
    }
  }

  private Week week() throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person mia = w.person("Mia", "Mbuyi");
    Person ana = w.person("Ana", "Kalala");
    Person ben = w.person("Ben", "Ilunga");
    Person cid = w.person("Cid", "Tshibanda");
    Person dan = w.person("Dan", "Mukendi");
    Person eve = w.person("Ève", "Mwamba");
    w.manageAll(mia, ana, ben, cid);
    LocalDate monday = Timesheet.Period.mondayOf(w.today).plusDays(7);
    // Three schedules (one site's schedules never overlap): Monday to Friday, Dan's cancelled
    // Saturday, Sunday to the next Monday.
    List<String> slots =
        new ArrayList<>(
            slotIds(
                w,
                w.published(
                    proposal(
                        w.site(),
                        monday,
                        monday.plusDays(4),
                        List.of(
                            shift("s0", monday, 8, 16, ana.employee()),
                            shift("s1", monday.plusDays(1), 8, 16, ana.employee()),
                            shift("s2", monday.plusDays(2), 8, 16, ana.employee()),
                            shift("s3", monday.plusDays(3), 8, 16, ben.employee()),
                            shift("s4", monday.plusDays(4), 8, 16, eve.employee()))))));
    String cancelled =
        w.published(
            proposal(
                w.site(),
                monday.plusDays(5),
                monday.plusDays(5),
                List.of(shift("d", monday.plusDays(5), 8, 16, dan.employee()))));
    slots.addAll(
        slotIds(
            w,
            w.published(
                proposal(
                    w.site(),
                    monday.plusDays(6),
                    monday.plusDays(7),
                    List.of(
                        slot(
                            "s5",
                            monday.plusDays(6) + "T22:00",
                            monday.plusDays(7) + "T06:00",
                            "Night guard",
                            "Gardien de nuit",
                            ana.employee()),
                        shift("s6", monday.plusDays(7), 14, 20, ana.employee()))))));
    w.expect(cancel(w.admin, key(), cancelled, "Fermeture"), 200);
    // Ben's Thursday goes to Cid.
    String requested = w.expect(request(ben, key(), slots.get(3)), 201).get("id").asText();
    w.expect(approve(mia, requested, key(), cid.employee()), 200);
    // Eve moves to the other site from Friday on.
    w.expect(
        w.change(
            eve.employee(), w.placement(w.otherSite, w.otherSiteDepartment, monday.plusDays(4))),
        201);
    // Ana's placement carries the organization's cost center from Tuesday on.
    Map<String, Object> placement = w.placement(w.site(), w.org.departmentId(), monday.plusDays(1));
    @SuppressWarnings("unchecked")
    Map<String, Object> unit = (Map<String, Object>) placement.get("placement");
    unit.remove("departmentId");
    unit.put("costCenterId", w.org.costCenterId().toString());
    w.expect(w.change(ana.employee(), placement), 201);

    // Monday: Ana checks in and out; Wednesday: in only; then Monday is corrected.
    Instant[] s0 = instants(jdbc, slots.get(0));
    Instant[] s2 = instants(jdbc, slots.get(2));
    clock.set(s0[0].plus(Duration.ofMinutes(5)));
    String r0 = w.expect(checkIn(ana, key(), slots.get(0)), 201).get("attendanceRecordId").asText();
    clock.set(s0[1].minus(Duration.ofMinutes(1)));
    w.expect(checkOut(ana, key(), r0), 200);
    clock.set(s2[0].plus(Duration.ofMinutes(10)));
    String r2 = w.expect(checkIn(ana, key(), slots.get(2)), 201).get("attendanceRecordId").asText();
    correct(w, r0, s0[0].plusSeconds(10), s0[0].plus(Duration.ofHours(8)).minusSeconds(20));
    // Sunday noon at the site: the current week.
    clock.set(monday.plusDays(6).atTime(LocalTime.NOON).atZone(KINSHASA).toInstant());
    return new Week(
        w, mia, ana, ben, cid, dan, eve, monday, slots, cancelled, r0, r2, w.org.costCenterId());
  }

  private void correct(ScheduleWorld w, String record, Instant in, Instant out) throws Exception {
    String admin = hr(w);
    Map<String, Object> proposal = values(in, out);
    JsonNode preview = w.expect(previewRecord(admin, record, proposal), 200);
    w.expect(commitRecord(admin, key(), record, commit(proposal, preview)), 201);
  }

  /** Generates the week as one administrator; returns the result. */
  private static JsonNode generated(Week t) throws Exception {
    return t.w().expect(generate(hr(t.w()), key(), confirmed(t.period())), 200);
  }

  /** The draft IDs of a generation, by employee. */
  private static Map<UUID, String> drafts(Week t, String generationId) throws Exception {
    Map<UUID, String> drafts = new LinkedHashMap<>();
    t.w()
        .expect(generation(hr(t.w()), generationId), 200)
        .get("timesheets")
        .forEach(
            d ->
                drafts.put(
                    UUID.fromString(d.get("employee").get("id").asText()),
                    d.get("timesheetId").asText()));
    return drafts;
  }

  private static Map<String, JsonNode> rowsBySlot(JsonNode detail) {
    Map<String, JsonNode> rows = new LinkedHashMap<>();
    detail.get("rows").forEach(r -> rows.put(r.get("slotId").asText(), r));
    return rows;
  }

  /** Generations, drafts, revisions, rows and timesheet outbox events of the tenant. */
  private List<Integer> written(ScheduleWorld w) {
    return List.of(
        w.count("SELECT count(*) FROM people.timesheet_generation WHERE tenant_id = ?", w.tenant()),
        w.count("SELECT count(*) FROM people.timesheet WHERE tenant_id = ?", w.tenant()),
        w.count("SELECT count(*) FROM people.timesheet_revision WHERE tenant_id = ?", w.tenant()),
        w.count("SELECT count(*) FROM people.timesheet_row WHERE tenant_id = ?", w.tenant()),
        w.count(
            "SELECT count(*) FROM platform.outbox_event WHERE envelope ->> 'tenantId' = ?"
                + " AND envelope ->> 'eventType' LIKE 'people.timesheet.%'",
            w.tenant().toString()),
        w.count(
            "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?"
                + " AND action IN ('timesheet.generate', 'timesheet.refresh')",
            w.tenant()));
  }

  private static JsonNode stable(JsonNode problem) {
    ObjectNode copy = (ObjectNode) problem.deepCopy();
    copy.remove("correlationId");
    copy.remove("instance");
    return copy;
  }

  // ------------------------------------------------------------------------------------------
  // 1 to 6: generation from the sources
  // ------------------------------------------------------------------------------------------

  @Test
  void aWeekIsGeneratedFromEffectiveAssigneesAndEffectiveAttendance() throws Exception {
    Week t = week();
    ScheduleWorld w = t.w();
    Instant[] s0 = instants(jdbc, t.slot(0));
    String admin = hr(w);

    // The preview: what would be created, nothing written.
    List<Integer> before = written(w);
    JsonNode preview = w.expect(previewGeneration(admin, t.period()), 200);
    assertThat(written(w)).isEqualTo(before);
    assertThat(preview.get("generation").isNull()).isTrue();
    assertThat(preview.get("drafts").asInt()).isEqualTo(2);
    JsonNode totals = preview.get("totals");
    assertThat(totals.get("rows").asInt()).isEqualTo(5);
    assertThat(totals.get("readyRows").asInt()).isEqualTo(1);
    assertThat(totals.get("missingRows").asInt()).isEqualTo(3);
    assertThat(totals.get("openRows").asInt()).isEqualTo(1);
    assertThat(totals.get("changedRows").asInt()).isZero();
    assertThat(totals.get("blockedRows").asInt()).isEqualTo(4);
    assertThat(totals.get("workedMinutes").asInt()).isEqualTo(479);
    assertThat(preview.get("maxDrafts").asInt()).isEqualTo(500);
    assertThat(preview.get("maxRows").asInt()).isEqualTo(5000);
    JsonNode period = preview.get("period");
    assertThat(period.get("weekStart").asText()).isEqualTo(t.monday().toString());
    assertThat(period.get("weekEnd").asText()).isEqualTo(t.monday().plusDays(7).toString());
    assertThat(period.get("lastDay").asText()).isEqualTo(t.monday().plusDays(6).toString());
    assertThat(period.get("timezone").asText()).isEqualTo("Africa/Kinshasa");
    assertThat(Instant.parse(period.get("periodStartAt").asText()))
        .isEqualTo(t.monday().atStartOfDay(KINSHASA).toInstant());
    assertThat(Instant.parse(period.get("periodEndAt").asText()))
        .isEqualTo(t.monday().plusDays(7).atStartOfDay(KINSHASA).toInstant());

    // The generation.
    JsonNode result = w.expect(generate(admin, key(), confirmed(t.period())), 200);
    assertThat(result.get("outcome").asText()).isEqualTo("CREATED");
    JsonNode summary = result.get("generation");
    assertThat(summary.get("status").asText()).isEqualTo("DRAFT");
    assertThat(summary.get("version").asInt()).isEqualTo(1);
    assertThat(summary.get("drafts").asInt()).isEqualTo(2);
    assertThat(summary.get("refreshedAt").isNull()).isTrue();
    assertThat(summary.get("totals")).isEqualTo(totals);
    String generationId = summary.get("generationId").asText();
    Map<UUID, String> drafts = drafts(t, generationId);
    // One draft per effective assignee with an included slot: Ana and Cid (not Ben, Eve or Dan).
    assertThat(drafts.keySet()).containsExactlyInAnyOrder(t.ana().employee(), t.cid().employee());

    // Ana: the corrected Monday READY (truncated minutes, no cost center yet), Tuesday MISSING
    // (cost center from the dated placement), Wednesday OPEN, Sunday night MISSING.
    JsonNode ana = w.expect(timesheet(admin, drafts.get(t.ana().employee())), 200);
    assertThat(ana.get("status").asText()).isEqualTo("DRAFT");
    assertThat(ana.get("revision").asInt()).isEqualTo(1);
    assertThat(ana.get("employee").get("familyName").asText()).isEqualTo("Kalala");
    Map<String, JsonNode> rows = rowsBySlot(ana);
    assertThat(rows.keySet()).containsExactly(t.slot(0), t.slot(1), t.slot(2), t.slot(5));
    JsonNode monday = rows.get(t.slot(0));
    assertThat(monday.get("state").asText()).isEqualTo("READY");
    assertThat(monday.get("corrected").asBoolean()).isTrue();
    assertThat(monday.get("correctionVersion").asInt()).isEqualTo(1);
    assertThat(monday.get("attendanceRecordId").asText()).isEqualTo(t.r0());
    assertThat(Instant.parse(monday.get("checkedInAt").asText())).isEqualTo(s0[0].plusSeconds(10));
    assertThat(Instant.parse(monday.get("checkedOutAt").asText()))
        .isEqualTo(s0[0].plus(Duration.ofHours(8)).minusSeconds(20));
    assertThat(monday.get("workedMinutes").asInt()).isEqualTo(479);
    assertThat(monday.get("costCenter").isNull()).isTrue();
    assertThat(monday.get("siteId").asText()).isEqualTo(w.site().toString());
    assertThat(monday.get("labels").get("fr").asText()).isEqualTo("Caissière");
    JsonNode tuesday = rows.get(t.slot(1));
    assertThat(tuesday.get("state").asText()).isEqualTo("MISSING_ATTENDANCE");
    assertThat(tuesday.get("workedMinutes").isNull()).isTrue();
    assertThat(tuesday.get("attendanceRecordId").isNull()).isTrue();
    assertThat(tuesday.get("costCenter").get("id").asText()).isEqualTo(t.costCenter().toString());
    assertThat(tuesday.get("costCenter").get("code").asText()).isEqualTo(w.org.costCenter());
    JsonNode wednesday = rows.get(t.slot(2));
    assertThat(wednesday.get("state").asText()).isEqualTo("OPEN_ATTENDANCE");
    assertThat(wednesday.get("attendanceRecordId").asText()).isEqualTo(t.r2());
    assertThat(wednesday.get("checkedOutAt").isNull()).isTrue();
    assertThat(wednesday.get("workedMinutes").isNull()).isTrue();
    assertThat(wednesday.get("corrected").asBoolean()).isFalse();
    assertThat(rows.get(t.slot(5)).get("state").asText()).isEqualTo("MISSING_ATTENDANCE");
    assertThat(rows.get(t.slot(5)).get("labels").get("en").asText()).isEqualTo("Night guard");
    assertThat(ana.get("totals").get("workedMinutes").asInt()).isEqualTo(479);
    assertThat(ana.get("revisions")).hasSize(1);

    // Cid: the substituted Thursday.
    JsonNode cid = w.expect(timesheet(admin, drafts.get(t.cid().employee())), 200);
    assertThat(rowsBySlot(cid).keySet()).containsExactly(t.slot(3));
    assertThat(cid.get("rows").get(0).get("state").asText()).isEqualTo("MISSING_ATTENDANCE");

    // Stored: the row's worked minutes match the stored instants exactly.
    assertThat(
            w.count(
                "SELECT count(*) FROM people.timesheet_row WHERE tenant_id = ?"
                    + " AND state = 'READY' AND worked_minutes = 479",
                w.tenant()))
        .isEqualTo(1);

    // The audit record and the event: bounded metadata, stable IDs and counts only.
    JsonNode metadata =
        JSON.readTree(
            jdbc.queryForObject(
                "SELECT metadata::text FROM platform.audit_event WHERE tenant_id = ?"
                    + " AND action = 'timesheet.generate'",
                String.class,
                w.tenant()));
    Set<String> keys = new TreeSet<>();
    metadata.fieldNames().forEachRemaining(keys::add);
    assertThat(keys)
        .containsExactly(
            "blockedCount",
            "draftCount",
            "readyCount",
            "revisionCount",
            "rowCount",
            "schemaVersion",
            "week");
    assertThat(metadata.get("draftCount").asInt()).isEqualTo(2);
    assertThat(metadata.get("rowCount").asInt()).isEqualTo(5);
    assertThat(metadata.get("readyCount").asInt()).isEqualTo(1);
    assertThat(metadata.get("blockedCount").asInt()).isEqualTo(4);
    String event =
        jdbc.queryForObject(
            "SELECT envelope::text FROM platform.outbox_event WHERE envelope ->> 'tenantId' = ?"
                + " AND envelope ->> 'eventType' = 'people.timesheet.generated.v1'",
            String.class,
            w.tenant().toString());
    JsonNode data = JSON.readTree(event).get("data");
    Set<String> fields = new TreeSet<>();
    data.fieldNames().forEachRemaining(fields::add);
    assertThat(fields)
        .containsExactly(
            "blockedCount",
            "generationId",
            "occurredAt",
            "periodEndAt",
            "periodStartAt",
            "readyCount",
            "revisionCount",
            "rowCount",
            "siteId",
            "version",
            "weekStart");
    for (String text : List.of(event, metadata.toString())) {
      assertThat(text)
          .doesNotContain("Kalala")
          .doesNotContain(t.ana().employee().toString())
          .doesNotContain(t.slot(0))
          .doesNotContain("Caissière");
    }
  }

  // ------------------------------------------------------------------------------------------
  // 7, 8: idempotent generation; the existing week; refused weeks
  // ------------------------------------------------------------------------------------------

  @Test
  void aGenerationReplaysAndAnExistingWeekIsReturnedUnchanged() throws Exception {
    Week t = week();
    ScheduleWorld w = t.w();
    String admin = hr(w);
    String k = key();
    MockHttpServletResponse first = w.call(generate(admin, k, confirmed(t.period())));
    assertThat(first.getStatus()).isEqualTo(200);
    assertThat(first.getHeader("Cache-Control")).isEqualTo("private, no-store");
    List<Integer> after = written(w);
    MockHttpServletResponse replay = w.call(generate(admin, k, confirmed(t.period())));
    assertThat(replay.getStatus()).isEqualTo(200);
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
    // The same key for another body: refused.
    w.problem(
        generate(admin, k, confirmed(period(w.site(), t.monday().minusDays(7)))),
        409,
        "IDEMPOTENCY_KEY_REUSED");
    // A new key, another administrator: the existing generation, unchanged.
    JsonNode existing = generated(t);
    assertThat(existing.get("outcome").asText()).isEqualTo("EXISTING");
    JsonNode created = JSON.readTree(first.getContentAsString(StandardCharsets.UTF_8));
    assertThat(existing.get("generation")).isEqualTo(created.get("generation"));
    assertThat(written(w)).isEqualTo(after);
    // The preview now names the generation.
    JsonNode preview = w.expect(previewGeneration(admin, t.period()), 200);
    assertThat(preview.get("generation").get("generationId").asText())
        .isEqualTo(created.get("generation").get("generationId").asText());

    // A week without included slots: not allowed, nothing written.
    w.problem(
        generate(admin, key(), confirmed(period(w.site(), t.monday().minusDays(7)))),
        409,
        "TIMESHEET_GENERATION_NOT_ALLOWED");
    w.problem(
        generate(admin, key(), confirmed(period(w.otherSite, t.monday()))),
        409,
        "TIMESHEET_GENERATION_NOT_ALLOWED");
    // A future week, a non-Monday, no confirmation, no key: invalid.
    for (Map<String, Object> body :
        List.of(
            confirmed(period(w.site(), t.monday().plusDays(7))),
            confirmed(period(w.site(), t.monday().plusDays(1))),
            period(w.site(), t.monday()))) {
      w.problem(generate(admin, key(), body), 400, "VALIDATION_FAILED");
    }
    w.problem(generate(admin, null, confirmed(t.period())), 400, "VALIDATION_FAILED");
    w.problem(previewGeneration(admin, period(w.site(), "2026-13-01")), 400, "VALIDATION_FAILED");
    Map<String, Object> unknown = confirmed(t.period());
    unknown.put("employeeId", t.ana().employee().toString());
    w.problem(generate(admin, key(), unknown), 400, "VALIDATION_FAILED");
    // An unknown, malformed or non-site unit: the one 404.
    List<JsonNode> problems = new ArrayList<>();
    for (Object site :
        List.of(UUID.randomUUID(), "nope", w.otherSiteDepartment, w.org.costCenterId())) {
      problems.add(
          w.problem(
              previewGeneration(admin, period(site, t.monday())),
              404,
              "TIMESHEET_TARGET_NOT_FOUND"));
      problems.add(
          w.problem(
              generate(admin, key(), confirmed(period(site, t.monday()))),
              404,
              "TIMESHEET_TARGET_NOT_FOUND"));
    }
    for (JsonNode problem : problems) {
      assertThat(stable(problem)).isEqualTo(stable(problems.get(0)));
    }
    assertThat(written(w)).isEqualTo(after);
  }

  // ------------------------------------------------------------------------------------------
  // 9 to 12: refresh preview and commit, append-only revisions
  // ------------------------------------------------------------------------------------------

  @Test
  void aRefreshPreviewWritesNothingAndItsCommitAppendsRevisions() throws Exception {
    Week t = week();
    ScheduleWorld w = t.w();
    String id = generated(t).get("generation").get("generationId").asText();
    Map<UUID, String> drafts = drafts(t, id);
    String admin = hr(w);

    // Nothing changed: the preview says so; its commit still records a new generation version.
    JsonNode quiet = w.expect(previewRefresh(admin, id, t.period()), 200);
    assertThat(quiet.get("version").asInt()).isEqualTo(1);
    assertThat(quiet.get("newDrafts").asInt()).isZero();
    assertThat(quiet.get("changedTimesheets").asInt()).isZero();
    assertThat(quiet.get("unchangedTimesheets").asInt()).isEqualTo(2);
    assertThat(quiet.get("changedRows").asInt()).isZero();
    JsonNode none = w.expect(refresh(admin, key(), id, refreshBody(t.period(), quiet)), 200);
    assertThat(none.get("generation").get("version").asInt()).isEqualTo(2);
    assertThat(none.get("changedTimesheets").asInt()).isZero();
    assertThat(
            w.count(
                "SELECT count(*) FROM people.timesheet_revision WHERE tenant_id = ?", w.tenant()))
        .isEqualTo(2);

    // Attendance changes: Wednesday gets its check-out by correction; Sunday night goes to Cid.
    Instant[] s2 = instants(jdbc, t.slot(2));
    correct(
        w,
        t.r2(),
        s2[0].plus(Duration.ofMinutes(10)),
        s2[0].plus(Duration.ofMinutes(10)).plus(Duration.ofHours(7)).plusSeconds(59));
    String requested = w.expect(request(t.ana(), key(), t.slot(5)), 201).get("id").asText();
    w.expect(approve(t.mia(), requested, key(), t.cid().employee()), 200);

    List<Integer> before = written(w);
    JsonNode preview = w.expect(previewRefresh(admin, id, t.period()), 200);
    w.expect(previewRefresh(admin, id, t.period()), 200);
    assertThat(written(w)).isEqualTo(before);
    assertThat(preview.get("version").asInt()).isEqualTo(2);
    assertThat(preview.get("newDrafts").asInt()).isZero();
    assertThat(preview.get("changedTimesheets").asInt()).isEqualTo(2);
    assertThat(preview.get("unchangedTimesheets").asInt()).isZero();
    // Ana: Wednesday and Sunday night; Cid: Sunday night added.
    assertThat(preview.get("changedRows").asInt()).isEqualTo(3);
    assertThat(preview.get("totals").get("rows").asInt()).isEqualTo(6);
    assertThat(preview.get("totals").get("readyRows").asInt()).isEqualTo(2);
    assertThat(preview.get("totals").get("changedRows").asInt()).isEqualTo(1);
    assertThat(preview.get("totals").get("workedMinutes").asInt()).isEqualTo(479 + 420);

    // Stale: another version, another digest, another administrator; malformed: no confirmation.
    Map<String, Object> body = refreshBody(t.period(), preview);
    Map<String, Object> version = new HashMap<>(body);
    version.put("version", 1);
    w.problem(refresh(admin, key(), id, version), 409, "TIMESHEET_REFRESH_PREVIEW_STALE");
    Map<String, Object> digest = new HashMap<>(body);
    digest.put("previewDigest", "0".repeat(64));
    w.problem(refresh(admin, key(), id, digest), 409, "TIMESHEET_REFRESH_PREVIEW_STALE");
    w.problem(refresh(hr(w), key(), id, body), 409, "TIMESHEET_REFRESH_PREVIEW_STALE");
    Map<String, Object> unconfirmed = new HashMap<>(body);
    unconfirmed.remove("confirm");
    w.problem(refresh(admin, key(), id, unconfirmed), 400, "VALIDATION_FAILED");
    // Another week or site in the body: the one 404.
    Map<String, Object> elsewhere = new HashMap<>(body);
    elsewhere.put("weekStart", t.monday().minusDays(7).toString());
    w.problem(refresh(admin, key(), id, elsewhere), 404, "TIMESHEET_TARGET_NOT_FOUND");
    assertThat(written(w)).isEqualTo(before);

    // The commit; its exact retry replays; the same reviewed preview on a new key is stale.
    String k = key();
    MockHttpServletResponse first = w.call(refresh(admin, k, id, body));
    assertThat(first.getStatus()).isEqualTo(200);
    JsonNode result = JSON.readTree(first.getContentAsString(StandardCharsets.UTF_8));
    assertThat(result.get("generation").get("version").asInt()).isEqualTo(3);
    assertThat(result.get("changedTimesheets").asInt()).isEqualTo(2);
    assertThat(result.get("changedRows").asInt()).isEqualTo(3);
    assertThat(result.get("generation").get("totals")).isEqualTo(preview.get("totals"));
    MockHttpServletResponse replay = w.call(refresh(admin, k, id, body));
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
    w.problem(refresh(admin, k, id, version), 409, "IDEMPOTENCY_KEY_REUSED");
    w.problem(refresh(admin, key(), id, body), 409, "TIMESHEET_REFRESH_PREVIEW_STALE");

    // Ana's draft: revision 2 with Wednesday READY and Sunday night SOURCE_CHANGED (kept, blocked).
    JsonNode ana = w.expect(timesheet(admin, drafts.get(t.ana().employee())), 200);
    assertThat(ana.get("revision").asInt()).isEqualTo(2);
    Map<String, JsonNode> rows = rowsBySlot(ana);
    assertThat(rows.get(t.slot(2)).get("state").asText()).isEqualTo("READY");
    assertThat(rows.get(t.slot(2)).get("workedMinutes").asInt()).isEqualTo(420);
    assertThat(rows.get(t.slot(5)).get("state").asText()).isEqualTo("SOURCE_CHANGED");
    assertThat(rows.get(t.slot(5)).get("attendanceRecordId").isNull()).isTrue();
    assertThat(ana.get("totals").get("workedMinutes").asInt()).isEqualTo(899);
    assertThat(ana.get("totals").get("blockedRows").asInt()).isEqualTo(2);
    List<Integer> revisions = new ArrayList<>();
    List<Integer> versions = new ArrayList<>();
    ana.get("revisions")
        .forEach(
            r -> {
              revisions.add(r.get("revision").asInt());
              versions.add(r.get("generationVersion").asInt());
            });
    assertThat(revisions).as("newest first").containsExactly(2, 1);
    assertThat(versions).containsExactly(3, 1);
    // Revision 1 is kept as it was.
    assertThat(
            w.count(
                "SELECT count(*) FROM people.timesheet_row r JOIN people.timesheet s"
                    + " ON s.tenant_id = r.tenant_id AND s.id = r.timesheet_id"
                    + " WHERE r.tenant_id = ? AND s.employee_id = ? AND r.revision = 1"
                    + " AND r.slot_id = ?::uuid AND r.state = 'OPEN_ATTENDANCE'",
                w.tenant(),
                t.ana().employee(),
                t.slot(2)))
        .isEqualTo(1);
    // Cid: revision 2 with Thursday and Sunday night.
    JsonNode cid = w.expect(timesheet(admin, drafts.get(t.cid().employee())), 200);
    assertThat(cid.get("revision").asInt()).isEqualTo(2);
    assertThat(rowsBySlot(cid).keySet()).containsExactly(t.slot(3), t.slot(5));

    // A preview overtaken by a source change is stale.
    JsonNode overtaken = w.expect(previewRefresh(admin, id, t.period()), 200);
    Instant[] s0 = instants(jdbc, t.slot(0));
    correct(w, t.r0(), s0[0], s0[0].plus(Duration.ofHours(8)));
    w.problem(
        refresh(admin, key(), id, refreshBody(t.period(), overtaken)),
        409,
        "TIMESHEET_REFRESH_PREVIEW_STALE");
    JsonNode fresh = w.expect(previewRefresh(admin, id, t.period()), 200);
    assertThat(fresh.get("changedTimesheets").asInt()).isEqualTo(1);
    w.expect(refresh(admin, key(), id, refreshBody(t.period(), fresh)), 200);
    JsonNode updated = w.expect(timesheet(admin, drafts.get(t.ana().employee())), 200);
    assertThat(updated.get("revision").asInt()).isEqualTo(3);
    assertThat(rowsBySlot(updated).get(t.slot(0)).get("correctionVersion").asInt()).isEqualTo(2);
    assertThat(rowsBySlot(updated).get(t.slot(0)).get("workedMinutes").asInt()).isEqualTo(480);

    // The refresh audits: bounded; one per committed refresh (none, two and one revisions).
    List<Integer> counts = new ArrayList<>();
    for (String text :
        jdbc.queryForList(
            "SELECT metadata::text FROM platform.audit_event WHERE tenant_id = ?"
                + " AND action = 'timesheet.refresh'",
            String.class,
            w.tenant())) {
      counts.add(JSON.readTree(text).get("revisionCount").asInt());
    }
    assertThat(counts).containsExactlyInAnyOrder(0, 2, 1);
    assertThat(
            w.count(
                "SELECT count(*) FROM platform.outbox_event WHERE envelope ->> 'tenantId' = ?"
                    + " AND envelope ->> 'eventType' = 'people.timesheet.refreshed.v1'",
                w.tenant().toString()))
        .isEqualTo(3);
  }

  // ------------------------------------------------------------------------------------------
  // 13, 14: exact-MFA tenant administrators only, inside their tenant
  // ------------------------------------------------------------------------------------------

  @Test
  void onlyAnExactMfaTenantAdministratorGeneratesOrReadsInsideTheTenant() throws Exception {
    Week t = week();
    ScheduleWorld w = t.w();
    String id = generated(t).get("generation").get("generationId").asText();
    String draft = drafts(t, id).get(t.ana().employee());
    JsonNode preview = w.expect(previewRefresh(hr(w), id, t.period()), 200);
    List<Integer> before = written(w);
    String passwordOnly =
        "Bearer "
            + TestTokens.token()
                .tenant(w.tenant())
                .subject("sub-ts-pwd-" + UUID.randomUUID())
                .roles(List.of("tenant-admin"))
                .acr(TestTokens.PASSWORD_ACR)
                .build();
    String platform =
        "Bearer "
            + TestTokens.token()
                .tenant(w.tenant())
                .subject(Bootstraps.platformSubject())
                .roles(List.of("platform-admin"))
                .build();
    for (String bearer : List.of(t.ana().bearer(), t.mia().bearer(), passwordOnly, platform)) {
      for (MockHttpServletRequestBuilder call :
          List.of(
              previewGeneration(bearer, t.period()),
              generate(bearer, key(), confirmed(t.period())),
              generation(bearer, id),
              previewRefresh(bearer, id, t.period()),
              refresh(bearer, key(), id, refreshBody(t.period(), preview)),
              timesheet(bearer, draft))) {
        assertThat(w.call(call).getStatus()).isEqualTo(403);
      }
    }
    w.problem(previewGeneration(passwordOnly, t.period()), 403, "MFA_REQUIRED");
    // A foreign administrator: the same 404 as an unknown or malformed target.
    ScheduleWorld other = new ScheduleWorld(mvc, jdbc, lookups);
    List<JsonNode> problems = new ArrayList<>();
    for (String target : List.of(id, UUID.randomUUID().toString(), "nope")) {
      problems.add(other.problem(generation(hr(other), target), 404, "TIMESHEET_TARGET_NOT_FOUND"));
      problems.add(
          other.problem(
              previewRefresh(hr(other), target, t.period()), 404, "TIMESHEET_TARGET_NOT_FOUND"));
    }
    for (String target : List.of(draft, UUID.randomUUID().toString(), "nope")) {
      problems.add(other.problem(timesheet(hr(other), target), 404, "TIMESHEET_TARGET_NOT_FOUND"));
    }
    problems.add(
        other.problem(previewGeneration(hr(other), t.period()), 404, "TIMESHEET_TARGET_NOT_FOUND"));
    problems.add(
        other.problem(
            generate(hr(other), key(), confirmed(t.period())), 404, "TIMESHEET_TARGET_NOT_FOUND"));
    for (JsonNode problem : problems) {
      assertThat(stable(problem)).isEqualTo(stable(problems.get(0)));
      assertThat(problem.toString()).doesNotContain(id).doesNotContain(draft);
    }
    // Inside the tenant: private, no-store.
    String admin = hr(w);
    for (MockHttpServletRequestBuilder call :
        List.of(
            previewGeneration(admin, t.period()),
            generation(admin, id),
            previewRefresh(admin, id, t.period()),
            timesheet(admin, draft))) {
      MockHttpServletResponse response = w.call(call);
      assertThat(response.getStatus()).isEqualTo(200);
      assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
    }
    assertThat(written(w)).isEqualTo(before);
  }

  // ------------------------------------------------------------------------------------------
  // 15, 17: employees read only their own drafts
  // ------------------------------------------------------------------------------------------

  @Test
  void anEmployeeReadsOnlyTheirOwnDrafts() throws Exception {
    Week t = week();
    ScheduleWorld w = t.w();
    String id = generated(t).get("generation").get("generationId").asText();
    Map<UUID, String> drafts = drafts(t, id);
    String anas = drafts.get(t.ana().employee());
    String cids = drafts.get(t.cid().employee());

    JsonNode page = w.expect(mine(t.ana().bearer()), 200);
    assertThat(page.get("items")).hasSize(1);
    JsonNode item = page.get("items").get(0);
    assertThat(item.get("timesheetId").asText()).isEqualTo(anas);
    assertThat(item.get("status").asText()).isEqualTo("DRAFT");
    assertThat(item.get("totals").get("workedMinutes").asInt()).isEqualTo(479);
    assertThat(page.get("nextCursor").isNull()).isTrue();
    JsonNode detail = w.expect(mine(t.ana().bearer(), anas), 200);
    assertThat(detail.get("rows")).hasSize(4);
    JsonNode first = detail.get("rows").get(0);
    assertThat(first.get("state").asText()).isEqualTo("READY");
    assertThat(first.get("corrected").asBoolean()).isTrue();
    assertThat(first.get("workedMinutes").asInt()).isEqualTo(479);
    // The employee view carries no internal identifiers, cost centers or other employees.
    Set<String> fields = new TreeSet<>();
    first.fieldNames().forEachRemaining(fields::add);
    assertThat(fields)
        .containsExactly(
            "checkedInAt",
            "checkedOutAt",
            "corrected",
            "labels",
            "scheduledEndAt",
            "scheduledStartAt",
            "state",
            "workedMinutes");
    assertThat(detail.toString())
        .doesNotContain(t.cid().employee().toString())
        .doesNotContain("Tshibanda")
        .doesNotContain(t.costCenter().toString());

    // Another employee's draft, an unknown or malformed ID: the same 404.
    List<JsonNode> problems = new ArrayList<>();
    for (String target : List.of(cids, UUID.randomUUID().toString(), "nope")) {
      problems.add(w.problem(mine(t.ana().bearer(), target), 404, "TIMESHEET_TARGET_NOT_FOUND"));
    }
    for (JsonNode problem : problems) {
      assertThat(stable(problem)).isEqualTo(stable(problems.get(0)));
    }
    // Cid sees only his; Ben, Eve and Dan see none; a manager sees only their own (none).
    assertThat(
            w.expect(mine(t.cid().bearer()), 200).get("items").get(0).get("timesheetId").asText())
        .isEqualTo(cids);
    for (Person person : List.of(t.ben(), t.eve(), t.dan(), t.mia())) {
      assertThat(w.expect(mine(person.bearer()), 200).get("items")).isEmpty();
    }
    // An unlinked employee: the link is required.
    w.unlink(t.ana().employee());
    w.problem(mine(t.ana().bearer()), 403, "EMPLOYEE_LINK_REQUIRED");
    w.problem(mine(t.ana().bearer(), anas), 403, "EMPLOYEE_LINK_REQUIRED");
    // A tenant administrator has no self view.
    MockHttpServletResponse admin = w.call(mine(hr(w)));
    assertThat(admin.getStatus()).isEqualTo(403);
    // Private, no-store.
    assertThat(w.call(mine(t.cid().bearer())).getHeader("Cache-Control"))
        .isEqualTo("private, no-store");
    // Disclosure audits for every read.
    assertThat(
            w.count(
                "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?"
                    + " AND action IN ('timesheet.self-list', 'timesheet.self-read')",
                w.tenant()))
        .isGreaterThanOrEqualTo(7);
  }

  // ------------------------------------------------------------------------------------------
  // 18, 19: logs
  // ------------------------------------------------------------------------------------------

  @Test
  void logsCarryOperationsOutcomesAndBandsOnly(CapturedOutput output) throws Exception {
    Week t = week();
    ScheduleWorld w = t.w();
    String admin = hr(w);
    JsonNode result = w.expect(generate(admin, key(), confirmed(t.period())), 200);
    String id = result.get("generation").get("generationId").asText();
    JsonNode preview = w.expect(previewRefresh(admin, id, t.period()), 200);
    w.expect(refresh(admin, key(), id, refreshBody(t.period(), preview)), 200);
    String draft = drafts(t, id).get(t.ana().employee());
    w.expect(timesheet(admin, draft), 200);
    w.expect(mine(t.ana().bearer(), draft), 200);
    String logs =
        String.join(
            "\n",
            output.getAll().lines().filter(line -> line.contains("divalhr.timesheet")).toList());
    assertThat(logs).contains("timesheet_generated").contains("timesheet_refreshed");
    assertThat(output.getAll())
        .doesNotContain(id)
        .doesNotContain(draft)
        .doesNotContain(preview.get("previewDigest").asText())
        .doesNotContain(t.ana().employee().toString())
        .doesNotContain(t.slot(0))
        .doesNotContain("Kalala");
  }
}
