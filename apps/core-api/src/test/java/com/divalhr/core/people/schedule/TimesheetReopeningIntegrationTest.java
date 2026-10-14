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
import static com.divalhr.core.people.schedule.SubstitutionCalls.slotIds;
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.approval;
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.approve;
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.decision;
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.giveBack;
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.queue;
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.submission;
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.submit;
import static com.divalhr.core.people.schedule.TimesheetCalls.confirmed;
import static com.divalhr.core.people.schedule.TimesheetCalls.generate;
import static com.divalhr.core.people.schedule.TimesheetCalls.generation;
import static com.divalhr.core.people.schedule.TimesheetCalls.mine;
import static com.divalhr.core.people.schedule.TimesheetCalls.previewRefresh;
import static com.divalhr.core.people.schedule.TimesheetCalls.refresh;
import static com.divalhr.core.people.schedule.TimesheetCalls.refreshBody;
import static com.divalhr.core.people.schedule.TimesheetCalls.timesheet;
import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.people.schedule.ScheduleWorld.Person;
import com.divalhr.core.people.schedule.domain.Timesheet;
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
 * MVP-070D (Issue #132, T70D-11 items 1 to 9, 11, 12 and 14) against real PostgreSQL: a material
 * correction reopens exactly the approved timesheet it changed, in its own transaction; an
 * immaterial one, a failed one, drafts and submitted time reopen nothing; the earlier approval,
 * revision and history never change; the refresh makes the reopened timesheet a draft at its next
 * revision while unchanged approved time stays locked; the next cycle is submitted and approved
 * through the MVP-070B/C rules; authority, isolation, replays and privacy hold throughout.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class TimesheetReopeningIntegrationTest {

  private static final ZoneId KINSHASA = ZoneId.of("Africa/Kinshasa");
  private static final String REASON = "Le mardi doit être revu avec la présence corrigée.";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;

  @AfterEach
  void reset() {
    clock.reset();
  }

  private static String hr(ScheduleWorld w) {
    return Hierarchy.bearer(w.tenant(), "sub-tsr-" + UUID.randomUUID(), "tenant-admin");
  }

  private static String key() {
    return Organizations.newKey();
  }

  /**
   * A generated week whose clock is its Sunday noon at the site. Mia manages Ana, Eve and Ben.
   *
   * <ul>
   *   <li>Ana: Monday and Tuesday checked in and out (two READY rows); she also checked in early
   *       for a Wednesday shift whose schedule was then cancelled (excluded from her timesheet).
   *   <li>Eve: Monday checked in and out (one READY row).
   *   <li>Ben: Monday checked in only (OPEN_ATTENDANCE, a draft).
   * </ul>
   *
   * Ana's and Eve's timesheets are submitted and approved by Mia.
   */
  private record Week(
      ScheduleWorld w,
      String admin,
      Person mia,
      Person ana,
      Person eve,
      Person ben,
      LocalDate monday,
      String generationId,
      Map<UUID, String> sheets,
      Map<String, String> records,
      Map<String, Instant[]> shifts) {

    String sheet(Person person) {
      return sheets.get(person.employee());
    }

    Map<String, Object> period() {
      return TimesheetCalls.period(w.site(), monday);
    }
  }

  private Week week() throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person mia = w.person("Mia", "Mbuyi");
    Person ana = w.person("Ana", "Kalala");
    Person eve = w.person("Eve", "Ngoy");
    Person ben = w.person("Ben", "Ilunga");
    w.manageAll(mia, ana, eve, ben);
    LocalDate monday = Timesheet.Period.mondayOf(w.today).plusDays(7);
    List<String> slots =
        slotIds(
            w,
            w.published(
                proposal(
                    w.site(),
                    monday,
                    monday.plusDays(1),
                    List.of(
                        shift("a0", monday, 8, 16, ana.employee()),
                        shift("e0", monday, 8, 16, eve.employee()),
                        shift("b0", monday, 8, 16, ben.employee()),
                        shift("a1", monday.plusDays(1), 8, 16, ana.employee())))));
    LocalDate wednesday = monday.plusDays(2);
    String cancelled =
        w.published(
            proposal(
                w.site(),
                wednesday,
                wednesday,
                List.of(shift("x", wednesday, 8, 16, ana.employee()))));
    String x = slotIds(w, cancelled).get(0);
    Map<String, Instant[]> shifts = new LinkedHashMap<>();
    shifts.put("a0", instants(jdbc, slots.get(0)));
    shifts.put("a1", instants(jdbc, slots.get(3)));
    shifts.put("x", instants(jdbc, x));
    Instant[] mon = shifts.get("a0");
    Map<String, String> records = new LinkedHashMap<>();
    clock.set(mon[0].plus(Duration.ofMinutes(5)));
    records.put("a0", record(w, checkIn(ana, key(), slots.get(0))));
    records.put("e0", record(w, checkIn(eve, key(), slots.get(1))));
    record(w, checkIn(ben, key(), slots.get(2)));
    clock.set(mon[1].minus(Duration.ofMinutes(1)));
    w.expect(checkOut(ana, key(), records.get("a0")), 200);
    w.expect(checkOut(eve, key(), records.get("e0")), 200);
    Instant[] tue = shifts.get("a1");
    clock.set(tue[0].plus(Duration.ofMinutes(5)));
    records.put("a1", record(w, checkIn(ana, key(), slots.get(3))));
    clock.set(tue[1].minus(Duration.ofMinutes(1)));
    w.expect(checkOut(ana, key(), records.get("a1")), 200);
    Instant[] wed = shifts.get("x");
    clock.set(wed[0].minus(Duration.ofHours(1)));
    records.put("x", record(w, checkIn(ana, key(), x)));
    w.expect(cancel(w.admin, key(), cancelled, "Fermeture du dépôt"), 200);
    clock.set(monday.plusDays(6).atTime(LocalTime.NOON).atZone(KINSHASA).toInstant());
    String admin = hr(w);
    String generationId =
        w.expect(generate(admin, key(), confirmed(TimesheetCalls.period(w.site(), monday))), 200)
            .get("generation")
            .get("generationId")
            .asText();
    Map<UUID, String> sheets = new LinkedHashMap<>();
    w.expect(generation(admin, generationId), 200)
        .get("timesheets")
        .forEach(
            d ->
                sheets.put(
                    UUID.fromString(d.get("employee").get("id").asText()),
                    d.get("timesheetId").asText()));
    Week t = new Week(w, admin, mia, ana, eve, ben, monday, generationId, sheets, records, shifts);
    approveCycle(t, ana);
    approveCycle(t, eve);
    return t;
  }

  private static String record(ScheduleWorld w, MockHttpServletRequestBuilder call)
      throws Exception {
    return w.expect(call, 201).get("attendanceRecordId").asText();
  }

  /** The person submits their reviewed timesheet and Mia approves it; returns the approval. */
  private static JsonNode approveCycle(Week t, Person person) throws Exception {
    ScheduleWorld w = t.w();
    String id = t.sheet(person);
    JsonNode own = w.expect(mine(person.bearer(), id), 200);
    w.expect(submit(person.bearer(), key(), id, submission(own)), 200);
    JsonNode reviewed = w.expect(approval(t.mia().bearer(), id), 200);
    return w.expect(approve(t.mia().bearer(), key(), id, decision(reviewed)), 200);
  }

  /** Commits a correction of a record with fresh times; returns the receipt. */
  private static JsonNode corrected(Week t, String record, Instant in, Instant out)
      throws Exception {
    Map<String, Object> proposal = values(in, out);
    JsonNode preview = t.w().expect(previewRecord(t.admin(), record, proposal), 200);
    return t.w().expect(commitRecord(t.admin(), key(), record, commit(proposal, preview)), 201);
  }

  private static Set<String> fields(JsonNode node) {
    Set<String> names = new TreeSet<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  private static List<String> kinds(JsonNode actions) {
    List<String> kinds = new ArrayList<>();
    actions.forEach(a -> kinds.add(a.get("action").asText() + ":" + a.get("cycle").asInt()));
    return kinds;
  }

  private static List<String> types(JsonNode warnings) {
    List<String> values = new ArrayList<>();
    warnings.forEach(n -> values.add(n.asText()));
    return values;
  }

  /** Every action of the tenant, in full (the evidence must never change). */
  private List<Map<String, Object>> evidence(ScheduleWorld w) {
    return jdbc.queryForList(
        "SELECT * FROM people.timesheet_action WHERE tenant_id = ? ORDER BY timesheet_id,"
            + " sequence",
        w.tenant());
  }

  private List<Map<String, Object>> revisions(ScheduleWorld w) {
    return jdbc.queryForList(
        "SELECT * FROM people.timesheet_revision WHERE tenant_id = ? ORDER BY timesheet_id,"
            + " revision",
        w.tenant());
  }

  private String state(String timesheetId) {
    return jdbc.queryForObject(
        "SELECT status || ':' || submission_cycle || ':r' || current_revision FROM"
            + " people.timesheet WHERE id = ?::uuid",
        String.class,
        timesheetId);
  }

  private List<String> history(String timesheetId) {
    return jdbc.queryForList(
        "SELECT action || ':' || cycle || ':' || revision || ':v' || generation_version FROM"
            + " people.timesheet_action WHERE timesheet_id = ?::uuid ORDER BY sequence",
        String.class,
        timesheetId);
  }

  private int reopenings(ScheduleWorld w) {
    return w.count(
        "SELECT count(*) FROM people.timesheet_action WHERE tenant_id = ? AND action = 'REOPENED'",
        w.tenant());
  }

  // ------------------------------------------------------------------------------------------
  // 1, 2, 4, 5, 6, 7, 8: reopen, refresh, resubmit, reapprove
  // ------------------------------------------------------------------------------------------

  @Test
  void aMaterialCorrectionReopensOnlyTheApprovedTimesheetItChangedWhichIsReapproved()
      throws Exception {
    Week t = week();
    ScheduleWorld w = t.w();
    String ana = t.sheet(t.ana());
    String eve = t.sheet(t.eve());
    String ben = t.sheet(t.ben());
    assertThat(state(ana)).isEqualTo("APPROVED:1:r1");
    assertThat(state(eve)).isEqualTo("APPROVED:1:r1");
    List<Map<String, Object>> approvedEvidence = evidence(w);
    List<Map<String, Object>> approvedRevisions = revisions(w);

    // 2: a correction of Ana's cancelled Wednesday shift (outside her timesheet) warns that
    // approved time covers it, but changes none of its sources: nothing is reopened.
    Instant[] wed = t.shifts().get("x");
    Map<String, Object> wednesday = values(wed[0], wed[1]);
    JsonNode immaterial = w.expect(previewRecord(t.admin(), t.records().get("x"), wednesday), 200);
    assertThat(types(immaterial.get("warnings")))
        .contains("APPROVED_TIME_MAY_REOPEN", "SCHEDULE_NOT_OPERATIONAL");
    JsonNode none =
        w.expect(
            commitRecord(t.admin(), key(), t.records().get("x"), commit(wednesday, immaterial)),
            201);
    assertThat(none.get("reopenedTimesheets").asInt()).isZero();
    assertThat(state(ana)).isEqualTo("APPROVED:1:r1");
    assertThat(reopenings(w)).isZero();
    // A draft's correction (Ben's open record gets its check-out) reopens nothing either.
    Instant[] mon = t.shifts().get("a0");
    JsonNode benPreview =
        w.expect(previewRecord(t.admin(), recordOf(t, t.ben()), values(mon[0], mon[1])), 200);
    assertThat(types(benPreview.get("warnings"))).doesNotContain("APPROVED_TIME_MAY_REOPEN");

    // 1, 4: Ana's Monday is corrected: her approved timesheet is reopened, Eve's is not.
    Instant in = mon[0].plus(Duration.ofMinutes(20));
    Instant out = mon[1].minus(Duration.ofMinutes(10));
    Map<String, Object> monday = values(in, out);
    JsonNode preview = w.expect(previewRecord(t.admin(), t.records().get("a0"), monday), 200);
    assertThat(types(preview.get("warnings"))).contains("APPROVED_TIME_MAY_REOPEN");
    String correctionKey = key();
    Map<String, Object> body = commit(monday, preview);
    JsonNode receipt =
        w.expect(commitRecord(t.admin(), correctionKey, t.records().get("a0"), body), 201);
    assertThat(receipt.get("reopenedTimesheets").asInt()).isEqualTo(1);
    // 12: the same key and payload replay the receipt and reopen nothing again; another payload
    // under the key is refused.
    MockHttpServletResponse replayed =
        w.call(commitRecord(t.admin(), correctionKey, t.records().get("a0"), body));
    assertThat(replayed.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(JSON.readTree(replayed.getContentAsString())).isEqualTo(receipt);
    Map<String, Object> other = new LinkedHashMap<>(body);
    other.put("explanation", "Une autre explication suffisamment longue.");
    w.problem(
        commitRecord(t.admin(), correctionKey, t.records().get("a0"), other),
        409,
        "IDEMPOTENCY_KEY_REUSED");
    assertThat(reopenings(w)).isEqualTo(1);
    assertThat(state(ana)).isEqualTo("REOPENED:1:r1");
    assertThat(state(eve)).isEqualTo("APPROVED:1:r1");
    assertThat(state(ben)).isEqualTo("DRAFT:0:r1");

    // 5: every earlier action and revision is unchanged; the reopening binds the approval.
    List<Map<String, Object>> after = evidence(w);
    assertThat(after).hasSize(approvedEvidence.size() + 1);
    assertThat(after).containsAll(approvedEvidence);
    assertThat(revisions(w)).isEqualTo(approvedRevisions);
    Map<String, Object> reopening =
        jdbc.queryForMap(
            "SELECT r.*, a.id AS approval_id, a.source_digest AS approved_digest FROM"
                + " people.timesheet_action r JOIN people.timesheet_action a ON a.tenant_id ="
                + " r.tenant_id AND a.id = r.superseded_action_id WHERE r.timesheet_id ="
                + " ?::uuid AND r.action = 'REOPENED'",
            ana);
    assertThat(reopening.get("sequence")).isEqualTo(3);
    assertThat(reopening.get("cycle")).isEqualTo(1);
    assertThat(reopening.get("revision")).isEqualTo(1);
    assertThat(reopening.get("source_digest")).isEqualTo(reopening.get("approved_digest"));
    assertThat(reopening.get("after_source_digest")).isNotEqualTo(reopening.get("source_digest"));
    assertThat(reopening.get("correction_id").toString())
        .isEqualTo(receipt.get("correctionId").asText());
    assertThat(reopening.get("manager_employee_id")).isEqualTo(t.mia().employee());
    assertThat(reopening.get("return_reason")).isNull();

    // 6, T70D-6: the employee reads her reopened timesheet and cannot submit it yet; the manager
    // has nothing to decide; the administrators read state and history.
    JsonNode own = w.expect(mine(t.ana().bearer(), ana), 200);
    assertThat(own.get("status").asText()).isEqualTo("REOPENED");
    assertThat(own.get("submission").get("eligible").asBoolean()).isFalse();
    assertThat(own.get("submission").get("blockers").toString())
        .isEqualTo("[\"REFRESH_REQUIRED\"]");
    assertThat(kinds(own.get("actions")))
        .containsExactly("REOPENED:1", "APPROVED:1", "SUBMITTED:1");
    assertThat(fields(own.get("actions").get(0)))
        .containsExactly(
            "action", "cycle", "occurredAt", "returnReason", "revision", "workedMinutes");
    assertThat(own.toString())
        .doesNotContain(receipt.get("correctionId").asText())
        .doesNotContain(String.valueOf(reopening.get("after_source_digest")))
        .doesNotContain(CorrectionCalls.EXPLANATION);
    w.problem(
        submit(t.ana().bearer(), key(), ana, submission(own)),
        409,
        "TIMESHEET_SUBMISSION_NOT_ALLOWED");
    assertThat(w.expect(queue(t.mia().bearer()), 200).get("items")).isEmpty();
    w.problem(approval(t.mia().bearer(), ana), 404, "TIMESHEET_TARGET_NOT_FOUND");
    JsonNode detail = w.expect(timesheet(t.admin(), ana), 200);
    assertThat(detail.get("status").asText()).isEqualTo("REOPENED");
    assertThat(kinds(detail.get("actions")))
        .containsExactly("REOPENED:1", "APPROVED:1", "SUBMITTED:1");
    assertThat(detail.toString()).doesNotContain(receipt.get("correctionId").asText());
    assertThat(
            w.expect(generation(t.admin(), t.generationId()), 200)
                .get("timesheets")
                .findValuesAsText("status"))
        .containsExactlyInAnyOrder("REOPENED", "APPROVED", "DRAFT");
    // The administrators never submit or decide.
    assertThat(w.call(submit(t.admin(), key(), ana, submission(own))).getStatus()).isEqualTo(403);
    assertThat(w.call(queue(t.admin())).getStatus()).isEqualTo(403);

    // 4, 6, 8: the refresh reports the reopened and locked timesheets; the reopened one becomes a
    // draft at its next revision; Eve's approval stays locked and unchanged.
    JsonNode refreshPreview =
        w.expect(previewRefresh(t.admin(), t.generationId(), t.period()), 200);
    assertThat(refreshPreview.get("reopenedTimesheets").asInt()).isEqualTo(1);
    assertThat(refreshPreview.get("lockedTimesheets").asInt()).isEqualTo(1);
    // Ana's reopened timesheet changes; Eve's locked approval and Ben's draft keep their revision.
    assertThat(refreshPreview.get("changedTimesheets").asInt()).isEqualTo(1);
    assertThat(refreshPreview.get("unchangedTimesheets").asInt()).isEqualTo(2);
    JsonNode refreshed =
        w.expect(
            refresh(t.admin(), key(), t.generationId(), refreshBody(t.period(), refreshPreview)),
            200);
    assertThat(refreshed.get("reopenedTimesheets").asInt()).isEqualTo(1);
    assertThat(state(ana)).isEqualTo("DRAFT:1:r2");
    assertThat(state(eve)).isEqualTo("APPROVED:1:r1");
    assertThat(history(eve)).containsExactly("SUBMITTED:1:1:v1", "APPROVED:1:1:v1");
    assertThat(evidence(w)).containsAll(approvedEvidence);
    JsonNode refreshAudit =
        JSON.readTree(
            jdbc.queryForObject(
                "SELECT metadata::text FROM platform.audit_event WHERE tenant_id = ? AND action"
                    + " = 'timesheet.refresh'",
                String.class,
                w.tenant()));
    assertThat(refreshAudit.get("reopenedCount").asInt()).isEqualTo(1);

    // 7: the employee sees the new revision and the earlier cycle, and submits cycle 2, which is
    // routed again; a material change while it is submitted keeps the stale-decision rule (9).
    own = w.expect(mine(t.ana().bearer(), ana), 200);
    assertThat(own.get("status").asText()).isEqualTo("DRAFT");
    assertThat(own.get("revision").asInt()).isEqualTo(2);
    assertThat(own.get("generationVersion").asInt()).isEqualTo(2);
    assertThat(own.get("submission").get("eligible").asBoolean()).isTrue();
    assertThat(kinds(own.get("actions")))
        .containsExactly("REOPENED:1", "APPROVED:1", "SUBMITTED:1");
    JsonNode second = w.expect(submit(t.ana().bearer(), key(), ana, submission(own)), 200);
    assertThat(second.get("cycle").asInt()).isEqualTo(2);
    assertThat(second.get("revision").asInt()).isEqualTo(2);
    w.problem(
        previewRefresh(t.admin(), t.generationId(), t.period()),
        409,
        "TIMESHEET_GENERATION_NOT_ALLOWED");
    JsonNode reviewed = w.expect(approval(t.mia().bearer(), ana), 200);
    Instant[] tue = t.shifts().get("a1");
    JsonNode submittedCorrection =
        corrected(t, t.records().get("a1"), tue[0], tue[1].minus(Duration.ofMinutes(30)));
    assertThat(submittedCorrection.get("reopenedTimesheets").asInt()).isZero();
    assertThat(state(ana)).isEqualTo("SUBMITTED:2:r2");
    w.problem(
        approve(t.mia().bearer(), key(), ana, decision(reviewed)), 409, "TIMESHEET_DECISION_STALE");
    w.expect(
        giveBack(t.mia().bearer(), key(), ana, TimesheetApprovalCalls.giveBack(reviewed, REASON)),
        200);
    refreshPreview = w.expect(previewRefresh(t.admin(), t.generationId(), t.period()), 200);
    assertThat(refreshPreview.get("reopenedTimesheets").asInt()).isZero();
    w.expect(
        refresh(t.admin(), key(), t.generationId(), refreshBody(t.period(), refreshPreview)), 200);
    own = w.expect(mine(t.ana().bearer(), ana), 200);
    w.expect(submit(t.ana().bearer(), key(), ana, submission(own)), 200);
    reviewed = w.expect(approval(t.mia().bearer(), ana), 200);
    assertThat(reviewed.get("cycle").asInt()).isEqualTo(3);
    JsonNode approved = w.expect(approve(t.mia().bearer(), key(), ana, decision(reviewed)), 200);
    assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
    assertThat(history(ana))
        .containsExactly(
            "SUBMITTED:1:1:v1",
            "APPROVED:1:1:v1",
            "REOPENED:1:1:v1",
            "SUBMITTED:2:2:v2",
            "RETURNED:2:2:v2",
            "SUBMITTED:3:3:v3",
            "APPROVED:3:3:v3");
    // The first approval never changed.
    assertThat(evidence(w)).containsAll(approvedEvidence);

    // T70D-10: one reopening audit with bounded metadata and one event with stable IDs only.
    JsonNode metadata =
        JSON.readTree(
            jdbc.queryForObject(
                "SELECT metadata::text FROM platform.audit_event WHERE tenant_id = ? AND action"
                    + " = 'timesheet.reopen'",
                String.class,
                w.tenant()));
    assertThat(fields(metadata))
        .containsExactly(
            "action",
            "cycle",
            "generationVersion",
            "revision",
            "rowCount",
            "schemaVersion",
            "workedMinuteBand");
    assertThat(metadata.get("action").asText()).isEqualTo("REOPENED");
    String event =
        jdbc.queryForObject(
            "SELECT envelope::text FROM platform.outbox_event WHERE envelope ->> 'tenantId' = ?"
                + " AND envelope ->> 'eventType' = 'people.timesheet.reopened.v1'",
            String.class,
            w.tenant().toString());
    JsonNode data = JSON.readTree(event).get("data");
    assertThat(fields(data))
        .containsExactly(
            "actionId",
            "correctionId",
            "cycle",
            "employeeId",
            "generationId",
            "generationVersion",
            "occurredAt",
            "periodEndAt",
            "periodStartAt",
            "revision",
            "rowCount",
            "siteId",
            "status",
            "supersededActionId",
            "timesheetId",
            "weekStart",
            "workedMinutes");
    assertThat(data.get("status").asText()).isEqualTo("REOPENED");
    assertThat(event)
        .doesNotContain("Kalala")
        .doesNotContain(String.valueOf(reopening.get("after_source_digest")))
        .doesNotContain(String.valueOf(reopening.get("source_digest")))
        .doesNotContain(CorrectionCalls.EXPLANATION);
  }

  private String recordOf(Week t, Person person) {
    return jdbc.queryForObject(
        "SELECT id::text FROM people.attendance_record WHERE employee_id = ? ORDER BY"
            + " slot_start_at LIMIT 1",
        String.class,
        person.employee());
  }

  // ------------------------------------------------------------------------------------------
  // 3, 11: a failed correction, authority and isolation
  // ------------------------------------------------------------------------------------------

  @Test
  void aFailedOrForeignCorrectionReopensNothing() throws Exception {
    Week t = week();
    ScheduleWorld w = t.w();
    String ana = t.sheet(t.ana());
    Instant[] mon = t.shifts().get("a0");
    Map<String, Object> first = values(mon[0].plus(Duration.ofMinutes(15)), mon[1]);
    JsonNode stalePreview = w.expect(previewRecord(t.admin(), t.records().get("a0"), first), 200);
    // Another administrator's correction of the same record commits first (and reopens).
    corrected(t, t.records().get("a0"), mon[0].plus(Duration.ofMinutes(10)), mon[1]);
    assertThat(reopenings(w)).isEqualTo(1);
    List<Map<String, Object>> evidence = evidence(w);
    // 3: the stale commit fails and writes nothing: no second reopening, no other change.
    w.problem(
        commitRecord(t.admin(), key(), t.records().get("a0"), commit(first, stalePreview)),
        409,
        "ATTENDANCE_CORRECTION_PREVIEW_STALE");
    assertThat(evidence(w)).isEqualTo(evidence);
    // Ana's timesheet is no longer APPROVED: a further correction no longer warns of it.
    Instant[] tue = t.shifts().get("a1");
    Map<String, Object> tuesday = values(tue[0], tue[1].minus(Duration.ofMinutes(5)));
    JsonNode reopenedPreview =
        w.expect(previewRecord(t.admin(), t.records().get("a1"), tuesday), 200);
    assertThat(types(reopenedPreview.get("warnings"))).doesNotContain("APPROVED_TIME_MAY_REOPEN");

    // 11: another tenant's administrator, an employee, the manager and a platform administrator
    // never reach the record; nothing is reopened.
    ScheduleWorld other = new ScheduleWorld(mvc, jdbc, lookups);
    String foreign = hr(other);
    Instant[] eve = t.shifts().get("a0");
    Map<String, Object> eveFix = values(eve[0].plus(Duration.ofMinutes(30)), eve[1]);
    w.problem(
        previewRecord(foreign, t.records().get("e0"), eveFix),
        404,
        "ATTENDANCE_CORRECTION_TARGET_NOT_FOUND");
    assertThat(w.call(previewRecord(t.eve().bearer(), t.records().get("e0"), eveFix)).getStatus())
        .isEqualTo(403);
    assertThat(w.call(previewRecord(t.mia().bearer(), t.records().get("e0"), eveFix)).getStatus())
        .isEqualTo(403);
    String platform =
        Hierarchy.bearer(w.tenant(), "sub-platform-" + UUID.randomUUID(), "platform-admin");
    assertThat(w.call(previewRecord(platform, t.records().get("e0"), eveFix)).getStatus())
        .isEqualTo(403);
    assertThat(w.call(timesheet(platform, ana)).getStatus()).isEqualTo(403);
    assertThat(state(t.sheet(t.eve()))).isEqualTo("APPROVED:1:r1");
    assertThat(reopenings(w)).isEqualTo(1);
    // The foreign tenant's reads of the reopened timesheet are the same safe responses.
    w.problem(timesheet(foreign, ana), 404, "TIMESHEET_TARGET_NOT_FOUND");
    Person stranger = other.person("Eli", "Kabila");
    w.problem(mine(stranger.bearer(), ana), 404, "TIMESHEET_TARGET_NOT_FOUND");
    w.problem(mine(t.eve().bearer(), ana), 404, "TIMESHEET_TARGET_NOT_FOUND");
    w.problem(approval(stranger.bearer(), ana), 404, "TIMESHEET_TARGET_NOT_FOUND");
    w.problem(mine(t.ana().bearer(), "not-a-uuid"), 404, "TIMESHEET_TARGET_NOT_FOUND");
  }

  // ------------------------------------------------------------------------------------------
  // 14: privacy of errors, audit, events and logs
  // ------------------------------------------------------------------------------------------

  @Test
  void reopeningAuditEventsAndLogsCarryNoNameExplanationTimeOrSubject(CapturedOutput output)
      throws Exception {
    Week t = week();
    ScheduleWorld w = t.w();
    String ana = t.sheet(t.ana());
    Instant[] mon = t.shifts().get("a0");
    JsonNode receipt =
        corrected(t, t.records().get("a0"), mon[0].plus(Duration.ofMinutes(25)), mon[1]);
    String reopeningId =
        jdbc.queryForObject(
            "SELECT id::text FROM people.timesheet_action WHERE timesheet_id = ?::uuid AND"
                + " action = 'REOPENED'",
            String.class,
            ana);
    String audit =
        String.join(
            "\n",
            jdbc.queryForList(
                "SELECT metadata::text FROM platform.audit_event WHERE tenant_id = ? AND action"
                    + " IN ('timesheet.reopen', 'attendance.correction-create')",
                String.class,
                w.tenant()));
    String events =
        String.join(
            "\n",
            jdbc.queryForList(
                "SELECT envelope::text FROM platform.outbox_event WHERE envelope ->> 'tenantId' = ?"
                    + " AND envelope ->> 'eventType' = 'people.timesheet.reopened.v1'",
                String.class,
                w.tenant().toString()));
    assertThat(audit)
        .doesNotContain(ana)
        .doesNotContain(reopeningId)
        .doesNotContain(receipt.get("correctionId").asText())
        .doesNotContain(t.ana().employee().toString());
    for (String text : List.of(audit, events)) {
      assertThat(text)
          .doesNotContain("Kalala")
          .doesNotContain("Mbuyi")
          .doesNotContain(t.ana().number())
          .doesNotContain(t.ana().subject())
          .doesNotContain(t.mia().subject())
          .doesNotContain(CorrectionCalls.EXPLANATION);
    }
    String application =
        String.join(
            "\n",
            output
                .getAll()
                .lines()
                .filter(line -> line.contains("\"logger_name\":\"divalhr."))
                .toList());
    assertThat(application).contains("timesheet_reopened");
    assertThat(application)
        .doesNotContain(ana)
        .doesNotContain(reopeningId)
        .doesNotContain(receipt.get("correctionId").asText())
        .doesNotContain(t.ana().employee().toString())
        .doesNotContain(CorrectionCalls.EXPLANATION)
        .doesNotContain("Kalala");
  }
}
