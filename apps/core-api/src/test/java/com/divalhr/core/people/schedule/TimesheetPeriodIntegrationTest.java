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
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.giveBack;
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.submission;
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.submit;
import static com.divalhr.core.people.schedule.TimesheetCalls.confirmed;
import static com.divalhr.core.people.schedule.TimesheetCalls.generate;
import static com.divalhr.core.people.schedule.TimesheetCalls.generation;
import static com.divalhr.core.people.schedule.TimesheetCalls.mine;
import static com.divalhr.core.people.schedule.TimesheetCalls.previewRefresh;
import static com.divalhr.core.people.schedule.TimesheetCalls.refresh;
import static com.divalhr.core.people.schedule.TimesheetCalls.refreshBody;
import static com.divalhr.core.people.schedule.TimesheetExportCalls.create;
import static com.divalhr.core.people.schedule.TimesheetExportCalls.creation;
import static com.divalhr.core.people.schedule.TimesheetExportCalls.csv;
import static com.divalhr.core.people.schedule.TimesheetPeriodCalls.EXPLANATION;
import static com.divalhr.core.people.schedule.TimesheetPeriodCalls.history;
import static com.divalhr.core.people.schedule.TimesheetPeriodCalls.lock;
import static com.divalhr.core.people.schedule.TimesheetPeriodCalls.locking;
import static com.divalhr.core.people.schedule.TimesheetPeriodCalls.previewLock;
import static com.divalhr.core.people.schedule.TimesheetPeriodCalls.previewUnlock;
import static com.divalhr.core.people.schedule.TimesheetPeriodCalls.reasoned;
import static com.divalhr.core.people.schedule.TimesheetPeriodCalls.unlock;
import static com.divalhr.core.people.schedule.TimesheetPeriodCalls.unlocking;
import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.people.schedule.ScheduleWorld.Person;
import com.divalhr.core.people.schedule.domain.Timesheet;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Memberships;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.divalhr.core.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
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
 * MVP-071 (Issue #139, L71-11 items 1 to 10, 12, 13 and 15) against real PostgreSQL: a fully
 * approved week locks once and the lock binds its exact approvals, counts and totals; an empty,
 * partly approved or changed week is refused; a locked week refuses every protected mutation while
 * other weeks go on; an unlock needs exact MFA, a fresh reasoned preview and the current lock,
 * changes no timesheet and no export, and the correction, refresh, resubmission and reapproval that
 * follow allow a relock; authority, isolation and the privacy of the explanation hold throughout.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class TimesheetPeriodIntegrationTest {

  private static final ZoneId KINSHASA = ZoneId.of("Africa/Kinshasa");

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;

  @AfterEach
  void reset() {
    clock.reset();
  }

  private static String key() {
    return Organizations.newKey();
  }

  /**
   * A generated week whose clock is its Sunday noon at the site. Mia manages Ana, Eve and Zoe; Ana
   * worked Monday and Tuesday (two READY rows), Eve and Zoe Monday; every shift is checked in and
   * out. Nobody has submitted yet.
   */
  private record Week(
      ScheduleWorld w,
      String admin,
      Person mia,
      Person ana,
      Person eve,
      Person zoe,
      LocalDate monday,
      String generationId,
      Map<UUID, String> sheets,
      Map<String, String> records,
      Map<String, Instant[]> shifts) {

    String sheet(Person person) {
      return sheets.get(person.employee());
    }

    /** Another administrator of the tenant (each has its own per-subject rate limits). */
    String hr() {
      return Hierarchy.bearer(w.tenant(), "sub-tsp-" + UUID.randomUUID(), "tenant-admin");
    }

    Map<String, Object> scope() {
      return TimesheetPeriodCalls.scope(w.org.legalEntityId(), w.site(), monday);
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
    Person zoe = w.person("Zoe", "Tshala");
    w.manageAll(mia, ana, eve, zoe);
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
                        shift("z0", monday, 8, 16, zoe.employee()),
                        shift("a1", monday.plusDays(1), 8, 16, ana.employee())))));
    Map<String, Instant[]> shifts = new LinkedHashMap<>();
    shifts.put("a0", instants(jdbc, slots.get(0)));
    shifts.put("a1", instants(jdbc, slots.get(3)));
    Instant[] mon = shifts.get("a0");
    Map<String, String> records = new LinkedHashMap<>();
    clock.set(mon[0].plus(Duration.ofMinutes(5)));
    records.put("a0", record(w, checkIn(ana, key(), slots.get(0))));
    records.put("e0", record(w, checkIn(eve, key(), slots.get(1))));
    records.put("z0", record(w, checkIn(zoe, key(), slots.get(2))));
    clock.set(mon[1].minus(Duration.ofMinutes(1)));
    w.expect(checkOut(ana, key(), records.get("a0")), 200);
    w.expect(checkOut(eve, key(), records.get("e0")), 200);
    w.expect(checkOut(zoe, key(), records.get("z0")), 200);
    Instant[] tue = shifts.get("a1");
    clock.set(tue[0].plus(Duration.ofMinutes(5)));
    records.put("a1", record(w, checkIn(ana, key(), slots.get(3))));
    clock.set(tue[1].minus(Duration.ofMinutes(1)));
    w.expect(checkOut(ana, key(), records.get("a1")), 200);
    clock.set(monday.plusDays(6).atTime(LocalTime.NOON).atZone(KINSHASA).toInstant());
    String admin = Hierarchy.bearer(w.tenant(), "sub-tsp-" + UUID.randomUUID(), "tenant-admin");
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
    return new Week(w, admin, mia, ana, eve, zoe, monday, generationId, sheets, records, shifts);
  }

  /** The week with every timesheet approved by Mia (cycle 1). */
  private Week approvedWeek() throws Exception {
    Week t = week();
    approveCycle(t, t.ana());
    approveCycle(t, t.eve());
    approveCycle(t, t.zoe());
    return t;
  }

  private static String record(ScheduleWorld w, MockHttpServletRequestBuilder call)
      throws Exception {
    return w.expect(call, 201).get("attendanceRecordId").asText();
  }

  private static void submitOwn(Week t, Person person) throws Exception {
    String id = t.sheet(person);
    JsonNode own = t.w().expect(mine(person.bearer(), id), 200);
    t.w().expect(submit(person.bearer(), key(), id, submission(own)), 200);
  }

  private static JsonNode approveCycle(Week t, Person person) throws Exception {
    submitOwn(t, person);
    String id = t.sheet(person);
    JsonNode reviewed = t.w().expect(approval(t.mia().bearer(), id), 200);
    return t.w().expect(approve(t.mia().bearer(), key(), id, decision(reviewed)), 200);
  }

  private static JsonNode locked(Week t, JsonNode preview) throws Exception {
    return t.w().expect(lock(t.hr(), key(), locking(t.scope(), preview)), 200);
  }

  private static JsonNode unlocked(Week t, Map<String, Object> reasoned) throws Exception {
    JsonNode preview = t.w().expect(previewUnlock(t.hr(), reasoned), 200);
    return t.w().expect(unlock(t.hr(), key(), unlocking(reasoned, preview)), 200);
  }

  private static void refreshed(Week t) throws Exception {
    String refresher = t.hr();
    JsonNode preview = t.w().expect(previewRefresh(refresher, t.generationId(), t.period()), 200);
    t.w()
        .expect(refresh(refresher, key(), t.generationId(), refreshBody(t.period(), preview)), 200);
  }

  /** A correction of Ana's Monday check-in (20 minutes late), previewed and committed. */
  private static MockHttpServletRequestBuilder anaCorrection(Week t, String fixer)
      throws Exception {
    Instant[] mon = t.shifts().get("a0");
    Map<String, Object> fix = values(mon[0].plus(Duration.ofMinutes(20)), mon[1]);
    JsonNode reviewed = t.w().expect(previewRecord(fixer, t.records().get("a0"), fix), 200);
    return commitRecord(fixer, key(), t.records().get("a0"), commit(fix, reviewed));
  }

  private static Set<String> fields(JsonNode node) {
    Set<String> names = new TreeSet<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  private String approvalIdOf(String timesheetId) {
    return jdbc.queryForObject(
        "SELECT current_action_id::text FROM people.timesheet WHERE id = ?::uuid",
        String.class,
        timesheetId);
  }

  private List<Map<String, Object>> sheetRows(Week t) {
    return jdbc.queryForList(
        "SELECT id, status, current_revision, submission_cycle, current_action_id, worked_minutes"
            + " FROM people.timesheet WHERE generation_id = ?::uuid ORDER BY id",
        t.generationId());
  }

  private List<Map<String, Object>> actionRows(Week t) {
    return jdbc.queryForList(
        "SELECT sequence, action, generation_version, timesheet_count, employee_count, row_count,"
            + " worked_minutes, evidence_digest, locked_action_id, unlock_reason,"
            + " unlock_explanation FROM people.timesheet_period_action WHERE generation_id ="
            + " ?::uuid ORDER BY sequence",
        t.generationId());
  }

  private Map<String, Object> projection(Week t) {
    return jdbc.queryForMap(
        "SELECT state, version, current_action_id FROM people.timesheet_period_lock WHERE"
            + " generation_id = ?::uuid",
        t.generationId());
  }

  // ------------------------------------------------------------------------------------------
  // 1, 4, 5, 13: the lock, its evidence, idempotency and what the others see
  // ------------------------------------------------------------------------------------------

  @Test
  void aFullyApprovedWeekLocksOnceAndTheLockBindsItsExactEvidence() throws Exception {
    Week t = approvedWeek();
    ScheduleWorld w = t.w();
    JsonNode preview = w.expect(previewLock(t.hr(), t.scope()), 200);
    assertThat(preview.get("ready").asBoolean()).isTrue();
    assertThat(preview.get("blockers")).isEmpty();
    assertThat(preview.get("exported").asBoolean()).isFalse();
    assertThat(preview.get("generationVersion").asInt()).isEqualTo(1);
    assertThat(preview.get("lock").toString())
        .isEqualTo(
            "{\"state\":\"OPEN\",\"version\":0,\"currentActionId\":null,\"updatedAt\":null}");
    assertThat(preview.get("states").toString())
        .isEqualTo("{\"draft\":0,\"submitted\":0,\"approved\":3,\"reopened\":0}");
    JsonNode counts = preview.get("counts");
    assertThat(counts.get("timesheets").asInt()).isEqualTo(3);
    assertThat(counts.get("employees").asInt()).isEqualTo(3);
    assertThat(counts.get("rows").asInt()).isEqualTo(4);
    int minutes = counts.get("workedMinutes").asInt();
    assertThat(minutes)
        .isPositive()
        .isEqualTo(
            jdbc.queryForObject(
                "SELECT sum(worked_minutes) FROM people.timesheet WHERE generation_id = ?::uuid",
                Integer.class,
                t.generationId()));
    assertThat(preview.get("scope").get("siteId").asText()).isEqualTo(w.site().toString());
    assertThat(preview.get("scope").get("weekStart").asText()).isEqualTo(t.monday().toString());
    assertThat(preview.toString()).doesNotContain("Kalala").doesNotContain(t.ana().number());

    // The preview's digest does not depend on who previews; the same evidence gives the same
    // digest.
    assertThat(w.expect(previewLock(t.admin(), t.scope()), 200).get("previewDigest"))
        .isEqualTo(preview.get("previewDigest"));

    String key = key();
    MockHttpServletResponse first = w.call(lock(t.admin(), key, locking(t.scope(), preview)));
    assertThat(first.getStatus()).isEqualTo(200);
    assertThat(first.getHeader("Cache-Control")).isEqualTo("private, no-store");
    JsonNode result = JSON.readTree(first.getContentAsString());
    assertThat(result.get("outcome").asText()).isEqualTo("LOCKED");
    assertThat(result.get("lock").get("state").asText()).isEqualTo("LOCKED");
    assertThat(result.get("lock").get("version").asInt()).isEqualTo(1);
    JsonNode action = result.get("action");
    assertThat(action.get("sequence").asInt()).isEqualTo(1);
    assertThat(action.get("action").asText()).isEqualTo("LOCKED");
    assertThat(action.get("counts")).isEqualTo(counts);
    assertThat(action.get("lockedActionId").isNull()).isTrue();
    assertThat(action.get("unlockReason").isNull()).isTrue();
    assertThat(result.get("lock").get("currentActionId").asText())
        .isEqualTo(action.get("actionId").asText());

    // 4: the stored action binds the exact approvals: every timesheet's current action, the
    // counts and the total, the generation version; the projection shows it.
    List<Map<String, Object>> actions = actionRows(t);
    assertThat(actions).hasSize(1);
    Map<String, Object> stored = actions.get(0);
    assertThat(stored.get("timesheet_count")).isEqualTo(3);
    assertThat(stored.get("employee_count")).isEqualTo(3);
    assertThat(stored.get("row_count")).isEqualTo(4);
    assertThat(stored.get("worked_minutes")).isEqualTo(minutes);
    assertThat(stored.get("generation_version")).isEqualTo(1);
    assertThat(stored.get("evidence_digest").toString())
        .isEqualTo(action.get("evidenceDigest").asText())
        .matches("[0-9a-f]{64}");
    assertThat(stored.get("unlock_explanation")).isNull();
    assertThat(projection(t).get("state")).isEqualTo("LOCKED");
    assertThat(projection(t).get("version")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT people.timesheet_period_fully_approved(tenant_id, generation_id,"
                    + " timesheet_count, employee_count, row_count, worked_minutes)"
                    + " FROM people.timesheet_period_action WHERE generation_id = ?::uuid",
                Boolean.class,
                t.generationId()))
        .isTrue();

    // 5: an identical replay returns the original; a changed payload under the key is refused; a
    // new key with the now stale preview is refused (the lock version moved).
    MockHttpServletResponse replay = w.call(lock(t.admin(), key, locking(t.scope(), preview)));
    assertThat(replay.getStatus()).isEqualTo(200);
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(JSON.readTree(replay.getContentAsString())).isEqualTo(result);
    // A plain replace of one letter is a no-op when the digest has none: change the first digit.
    String original = preview.get("previewDigest").asText();
    Map<String, Object> changed =
        locking(t.scope(), 1, 0, (original.charAt(0) == 'a' ? "b" : "a") + original.substring(1));
    w.problem(lock(t.admin(), key, changed), 409, "IDEMPOTENCY_KEY_REUSED");
    w.problem(
        lock(t.admin(), key(), locking(t.scope(), preview)), 409, "TIMESHEET_PERIOD_PREVIEW_STALE");
    assertThat(actionRows(t)).hasSize(1);

    // A preview of the locked week names the lock; locking with it changes nothing.
    JsonNode again = w.expect(previewLock(t.hr(), t.scope()), 200);
    assertThat(again.get("ready").asBoolean()).isFalse();
    assertThat(again.get("blockers").toString()).isEqualTo("[\"ALREADY_LOCKED\"]");
    assertThat(again.get("lock").get("version").asInt()).isEqualTo(1);
    JsonNode unchanged = locked(t, again);
    assertThat(unchanged.get("outcome").asText()).isEqualTo("UNCHANGED");
    assertThat(unchanged.get("action").get("actionId")).isEqualTo(action.get("actionId"));
    assertThat(actionRows(t)).hasSize(1);

    // The history, the generation summary and the timesheet views show the state only.
    JsonNode history = w.expect(history(t.admin(), t.generationId()), 200);
    assertThat(history.get("lock").get("state").asText()).isEqualTo("LOCKED");
    assertThat(history.get("actions")).hasSize(1);
    assertThat(history.get("actions").get(0).get("actionId")).isEqualTo(action.get("actionId"));
    assertThat(history.toString()).doesNotContain("sub-tsp-").doesNotContain("Kalala");
    JsonNode summary = w.expect(generation(t.admin(), t.generationId()), 200).get("generation");
    assertThat(summary.get("periodLock").get("state").asText()).isEqualTo("LOCKED");
    assertThat(summary.get("periodLock").get("version").asInt()).isEqualTo(1);
    // 13: Ana sees that her own week is locked, nothing more; Mia sees it through the approval
    // view she already had; neither sees another employee's timesheet.
    JsonNode own = w.expect(mine(t.ana().bearer(), t.sheet(t.ana())), 200);
    assertThat(own.get("periodLocked").asBoolean()).isTrue();
    assertThat(fields(own)).doesNotContain("periodLock", "lockedBy", "unlockReason");
    w.problem(mine(t.ana().bearer(), t.sheet(t.eve())), 404, "TIMESHEET_TARGET_NOT_FOUND");
    // Mia's approval view exists only for a SUBMITTED timesheet (never one of a locked week), so a
    // locked week never reaches her as a detail; the approved timesheet stays the safe 404.
    w.problem(approval(t.mia().bearer(), t.sheet(t.ana())), 404, "TIMESHEET_TARGET_NOT_FOUND");

    // An export of the same approved evidence turns the preview's boolean on; nothing else.
    JsonNode exportPreview =
        w.expect(
            TimesheetExportCalls.preview(
                t.hr(), TimesheetExportCalls.scope(w.org.legalEntityId(), w.site(), t.monday())),
            200);
    w.expect(
        create(
            t.hr(),
            key(),
            creation(
                TimesheetExportCalls.scope(w.org.legalEntityId(), w.site(), t.monday()),
                exportPreview)),
        200);
    assertThat(w.expect(previewLock(t.hr(), t.scope()), 200).get("exported").asBoolean()).isTrue();
  }

  // ------------------------------------------------------------------------------------------
  // 2, 3: not ready, stale
  // ------------------------------------------------------------------------------------------

  @Test
  void anEmptyPartlyApprovedOrChangedWeekIsRefusedBeforeAnyWrite() throws Exception {
    Week t = week();
    ScheduleWorld w = t.w();
    // 2: drafts only.
    JsonNode drafts = w.expect(previewLock(t.hr(), t.scope()), 200);
    assertThat(drafts.get("ready").asBoolean()).isFalse();
    assertThat(drafts.get("blockers").toString()).isEqualTo("[\"NOT_ALL_APPROVED\"]");
    assertThat(drafts.get("states").get("draft").asInt()).isEqualTo(3);
    w.problem(lock(t.hr(), key(), locking(t.scope(), drafts)), 409, "TIMESHEET_PERIOD_NOT_READY");
    // 2: a submission and two approvals are not enough.
    approveCycle(t, t.ana());
    approveCycle(t, t.eve());
    submitOwn(t, t.zoe());
    JsonNode submitted = w.expect(previewLock(t.hr(), t.scope()), 200);
    assertThat(submitted.get("blockers").toString()).isEqualTo("[\"NOT_ALL_APPROVED\"]");
    assertThat(submitted.get("states").toString())
        .isEqualTo("{\"draft\":0,\"submitted\":1,\"approved\":2,\"reopened\":0}");
    w.problem(
        lock(t.hr(), key(), locking(t.scope(), submitted)), 409, "TIMESHEET_PERIOD_NOT_READY");
    // 3: a preview taken before the last approval is stale once Mia approves.
    JsonNode beforeApproval = submitted;
    JsonNode reviewed = w.expect(approval(t.mia().bearer(), t.sheet(t.zoe())), 200);
    w.expect(approve(t.mia().bearer(), key(), t.sheet(t.zoe()), decision(reviewed)), 200);
    w.problem(
        lock(t.hr(), key(), locking(t.scope(), beforeApproval)),
        409,
        "TIMESHEET_PERIOD_PREVIEW_STALE");
    // 2: a correction reopens Ana's approved week: REOPENED blocks.
    JsonNode ready = w.expect(previewLock(t.hr(), t.scope()), 200);
    assertThat(ready.get("ready").asBoolean()).isTrue();
    w.expect(anaCorrection(t, t.hr()), 201);
    JsonNode reopened = w.expect(previewLock(t.hr(), t.scope()), 200);
    assertThat(reopened.get("blockers").toString()).isEqualTo("[\"NOT_ALL_APPROVED\"]");
    assertThat(reopened.get("states").get("reopened").asInt()).isEqualTo(1);
    w.problem(
        lock(t.hr(), key(), locking(t.scope(), ready)), 409, "TIMESHEET_PERIOD_PREVIEW_STALE");
    w.problem(lock(t.hr(), key(), locking(t.scope(), reopened)), 409, "TIMESHEET_PERIOD_NOT_READY");
    // 3: the refresh changes the generation version; Ana's cycle 2 is approved; a preview from
    // before the refresh is stale, and so is a body with the wrong versions or an expired preview.
    refreshed(t);
    approveCycle(t, t.ana());
    JsonNode fresh = w.expect(previewLock(t.hr(), t.scope()), 200);
    assertThat(fresh.get("ready").asBoolean()).isTrue();
    assertThat(fresh.get("generationVersion").asInt()).isEqualTo(2);
    w.problem(
        lock(t.hr(), key(), locking(t.scope(), ready)), 409, "TIMESHEET_PERIOD_PREVIEW_STALE");
    w.problem(
        lock(t.hr(), key(), locking(t.scope(), 1, 0, fresh.get("previewDigest").asText())),
        409,
        "TIMESHEET_PERIOD_PREVIEW_STALE");
    w.problem(
        lock(t.hr(), key(), locking(t.scope(), 2, 1, fresh.get("previewDigest").asText())),
        409,
        "TIMESHEET_PERIOD_PREVIEW_STALE");
    Instant now = clock.instant();
    Instant expires = Instant.parse(fresh.get("expiresAt").asText());
    clock.set(expires);
    w.problem(
        lock(t.hr(), key(), locking(t.scope(), fresh)), 409, "TIMESHEET_PERIOD_PREVIEW_STALE");
    clock.set(now);
    assertThat(actionRows(t)).isEmpty();
    assertThat(
            w.count(
                "SELECT count(*) FROM people.timesheet_period_lock WHERE tenant_id = ?",
                w.tenant()))
        .isZero();
    // Then it locks, binding generation version 2 and Ana's second approval.
    JsonNode result = locked(t, fresh);
    assertThat(result.get("outcome").asText()).isEqualTo("LOCKED");
    assertThat(result.get("action").get("generationVersion").asInt()).isEqualTo(2);
    // 2: a generated week without any timesheet (inserted as the generation rows of a site whose
    // employees all left would be) is EMPTY.
    LocalDate next = t.monday().plusDays(7);
    UUID emptyGeneration = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO people.timesheet_generation (tenant_id, id, site_id, week_start, week_end,"
            + " site_timezone, period_start_at, period_end_at, version, status, generated_at,"
            + " generated_by) SELECT tenant_id, ?::uuid, site_id, week_start + 7, week_end + 7,"
            + " site_timezone, period_start_at + interval '7 days', period_end_at + interval '7"
            + " days', 1, 'DRAFT', generated_at, generated_by FROM people.timesheet_generation"
            + " WHERE id = ?::uuid",
        emptyGeneration.toString(),
        t.generationId());
    Map<String, Object> nextScope =
        TimesheetPeriodCalls.scope(w.org.legalEntityId(), w.site(), next);
    JsonNode empty = w.expect(previewLock(t.hr(), nextScope), 200);
    assertThat(empty.get("blockers").toString()).isEqualTo("[\"EMPTY\"]");
    assertThat(empty.get("counts").get("timesheets").asInt()).isZero();
    w.problem(lock(t.hr(), key(), locking(nextScope, empty)), 409, "TIMESHEET_PERIOD_NOT_READY");
    // A week that is not generated is the safe 404.
    w.problem(
        previewLock(
            t.hr(), TimesheetPeriodCalls.scope(w.org.legalEntityId(), w.site(), next.plusDays(7))),
        404,
        "TIMESHEET_TARGET_NOT_FOUND");
  }

  // ------------------------------------------------------------------------------------------
  // 6, 7, 9: the effects of a lock
  // ------------------------------------------------------------------------------------------

  @Test
  void aLockedWeekRefusesEveryProtectedMutationAndLeavesTheRestAlone() throws Exception {
    Week t = approvedWeek();
    ScheduleWorld w = t.w();
    Map<String, Object> exportScope =
        TimesheetExportCalls.scope(w.org.legalEntityId(), w.site(), t.monday());
    JsonNode exportPreview = w.expect(TimesheetExportCalls.preview(t.hr(), exportScope), 200);
    String exportId =
        w.expect(create(t.hr(), key(), creation(exportScope, exportPreview)), 200)
            .get("export")
            .get("exportId")
            .asText();
    byte[] exportBytes = w.call(csv(t.hr(), exportId)).getContentAsByteArray();
    List<Map<String, Object>> before = sheetRows(t);
    locked(t, w.expect(previewLock(t.hr(), t.scope()), 200));

    // 6: the correction preview stays readable; the commit is refused, and nothing is reopened.
    Instant[] mon = t.shifts().get("a0");
    Map<String, Object> fix = values(mon[0].plus(Duration.ofMinutes(20)), mon[1]);
    String fixer = t.hr();
    JsonNode reviewed = w.expect(previewRecord(fixer, t.records().get("a0"), fix), 200);
    w.problem(
        commitRecord(fixer, key(), t.records().get("a0"), commit(fix, reviewed)),
        409,
        "TIMESHEET_PERIOD_LOCKED");
    assertThat(
            w.count(
                "SELECT count(*) FROM people.attendance_correction WHERE tenant_id = ?",
                w.tenant()))
        .isZero();
    // 6: no refresh, no submission, no decision, no return.
    String refresher = t.hr();
    JsonNode refreshPreview =
        w.expect(previewRefresh(refresher, t.generationId(), t.period()), 200);
    w.problem(
        refresh(refresher, key(), t.generationId(), refreshBody(t.period(), refreshPreview)),
        409,
        "TIMESHEET_PERIOD_LOCKED");
    String ana = t.sheet(t.ana());
    JsonNode own = w.expect(mine(t.ana().bearer(), ana), 200);
    w.problem(
        submit(t.ana().bearer(), key(), ana, submission(own)), 409, "TIMESHEET_PERIOD_LOCKED");
    // A decision body shaped like a review of the approved timesheet (its current action): the
    // guard refuses before the state check does.
    Map<String, Object> decision = new LinkedHashMap<>();
    decision.put("revision", 1);
    decision.put("generationVersion", 1);
    decision.put("cycle", 1);
    decision.put("submissionActionId", approvalIdOf(ana));
    decision.put("confirm", true);
    w.problem(approve(t.mia().bearer(), key(), ana, decision), 409, "TIMESHEET_PERIOD_LOCKED");
    Map<String, Object> returned = new LinkedHashMap<>(decision);
    returned.put("reason", "Le lundi doit être vérifié.");
    w.problem(giveBack(t.mia().bearer(), key(), ana, returned), 409, "TIMESHEET_PERIOD_LOCKED");
    // (The database holds the same boundary against direct writes:
    // TimesheetPeriodMigrationIntegrationTest.)
    // 9: nothing changed: the timesheets, their actions and the export are as before.
    assertThat(sheetRows(t)).isEqualTo(before);
    assertThat(w.call(csv(t.hr(), exportId)).getContentAsByteArray()).isEqualTo(exportBytes);
    // Reads go on: history, the generation, Ana's own view, the export.
    w.expect(generation(t.admin(), t.generationId()), 200);
    assertThat(w.expect(mine(t.ana().bearer(), ana), 200).get("periodLocked").asBoolean()).isTrue();
    w.expect(TimesheetExportCalls.export(t.hr(), exportId), 200);
    // A new export of the unchanged approved evidence follows its own rules (EXISTING).
    JsonNode samePreview = w.expect(TimesheetExportCalls.preview(t.hr(), exportScope), 200);
    assertThat(
            w.expect(create(t.hr(), key(), creation(exportScope, samePreview)), 200)
                .get("outcome")
                .asText())
        .isEqualTo("EXISTING");

    // 7: the following week of the same site is unaffected: its shift is corrected and its
    // generation refreshed.
    LocalDate next = t.monday().plusDays(7);
    String slot =
        slotIds(
                w,
                w.published(
                    proposal(
                        w.site(),
                        next,
                        next,
                        List.of(shift("e", next, 8, 16, t.eve().employee())))))
            .get(0);
    Instant[] at = instants(jdbc, slot);
    Instant now = clock.instant();
    clock.set(at[0].plus(Duration.ofMinutes(2)));
    String record = record(w, checkIn(t.eve(), key(), slot));
    clock.set(at[1].minus(Duration.ofMinutes(2)));
    w.expect(checkOut(t.eve(), key(), record), 200);
    clock.set(next.plusDays(6).atTime(LocalTime.NOON).atZone(KINSHASA).toInstant());
    String nextGeneration =
        w.expect(generate(t.admin(), key(), confirmed(TimesheetCalls.period(w.site(), next))), 200)
            .get("generation")
            .get("generationId")
            .asText();
    Map<String, Object> nextFix = values(at[0].plus(Duration.ofMinutes(10)), at[1]);
    JsonNode nextReviewed = w.expect(previewRecord(fixer, record, nextFix), 200);
    w.expect(commitRecord(fixer, key(), record, commit(nextFix, nextReviewed)), 201);
    Map<String, Object> nextPeriod = TimesheetCalls.period(w.site(), next);
    JsonNode nextRefresh = w.expect(previewRefresh(refresher, nextGeneration, nextPeriod), 200);
    w.expect(refresh(refresher, key(), nextGeneration, refreshBody(nextPeriod, nextRefresh)), 200);
    clock.set(now);
    assertThat(sheetRows(t)).isEqualTo(before);
  }

  // ------------------------------------------------------------------------------------------
  // 8, 9, 10: the unlock and the cycle that allows a relock
  // ------------------------------------------------------------------------------------------

  @Test
  void anUnlockNeedsAFreshReasonedPreviewAndTheCurrentLockAndTheCycleAllowsARelock()
      throws Exception {
    Week t = approvedWeek();
    ScheduleWorld w = t.w();
    Map<String, Object> exportScope =
        TimesheetExportCalls.scope(w.org.legalEntityId(), w.site(), t.monday());
    String exportId =
        w.expect(
                create(
                    t.hr(),
                    key(),
                    creation(
                        exportScope,
                        w.expect(TimesheetExportCalls.preview(t.hr(), exportScope), 200))),
                200)
            .get("export")
            .get("exportId")
            .asText();
    byte[] exportBytes = w.call(csv(t.hr(), exportId)).getContentAsByteArray();
    Map<String, Object> reasoned =
        reasoned(t.scope(), "ATTENDANCE_CORRECTION_REQUIRED", EXPLANATION);
    // An open week has nothing to unlock.
    w.problem(previewUnlock(t.hr(), reasoned), 409, "TIMESHEET_PERIOD_NOT_LOCKED");
    JsonNode lockResult = locked(t, w.expect(previewLock(t.hr(), t.scope()), 200));
    String lockActionId = lockResult.get("action").get("actionId").asText();
    List<Map<String, Object>> before = sheetRows(t);

    // 8: the reason grammar.
    for (Object[] bad :
        List.of(
            new Object[] {"OTHER", "Court."},
            new Object[] {"OTHER", "x".repeat(501)},
            new Object[] {"OTHER", "Voir <b>le lecteur</b> du quai."},
            new Object[] {"OTHER", "Voir https://example.test/quai pour le détail."},
            new Object[] {"OTHER", "Un retour\u0007 chariot interdit ici."},
            new Object[] {"OTHER", null},
            new Object[] {"PAYROLL_CLOSED", EXPLANATION},
            new Object[] {null, EXPLANATION},
            new Object[] {7, EXPLANATION})) {
      w.problem(
          previewUnlock(t.hr(), reasoned(t.scope(), bad[0], bad[1])), 400, "VALIDATION_FAILED");
    }
    // 8: the preview binds the current lock, the reason and the explanation.
    JsonNode preview = w.expect(previewUnlock(t.hr(), reasoned), 200);
    assertThat(preview.get("currentLock").get("actionId").asText()).isEqualTo(lockActionId);
    assertThat(preview.get("reasonCode").asText()).isEqualTo("ATTENDANCE_CORRECTION_REQUIRED");
    assertThat(preview.get("warnings").toString())
        .isEqualTo("[\"EXPORTS_UNCHANGED\",\"APPROVALS_UNCHANGED\"]");
    assertThat(preview.toString()).doesNotContain(EXPLANATION);
    Map<String, Object> wrongAction = new LinkedHashMap<>(unlocking(reasoned, preview));
    wrongAction.put("lockActionId", UUID.randomUUID().toString());
    w.problem(unlock(t.hr(), key(), wrongAction), 409, "TIMESHEET_PERIOD_PREVIEW_STALE");
    Map<String, Object> wrongVersion = new LinkedHashMap<>(unlocking(reasoned, preview));
    wrongVersion.put("lockVersion", 3);
    w.problem(unlock(t.hr(), key(), wrongVersion), 409, "TIMESHEET_PERIOD_PREVIEW_STALE");
    Map<String, Object> otherReason =
        unlocking(reasoned(t.scope(), "ORGANIZATION_REQUEST", EXPLANATION), preview);
    w.problem(unlock(t.hr(), key(), otherReason), 409, "TIMESHEET_PERIOD_PREVIEW_STALE");
    Map<String, Object> otherText =
        unlocking(
            reasoned(t.scope(), "ATTENDANCE_CORRECTION_REQUIRED", EXPLANATION + " Vraiment."),
            preview);
    w.problem(unlock(t.hr(), key(), otherText), 409, "TIMESHEET_PERIOD_PREVIEW_STALE");
    Map<String, Object> unconfirmed = new LinkedHashMap<>(unlocking(reasoned, preview));
    unconfirmed.put("confirm", false);
    w.problem(unlock(t.hr(), key(), unconfirmed), 400, "VALIDATION_FAILED");
    w.problem(unlock(t.hr(), null, unlocking(reasoned, preview)), 400, "VALIDATION_FAILED");
    assertThat(actionRows(t)).hasSize(1);
    // The trimmed explanation is the same explanation.
    Map<String, Object> padded =
        reasoned(t.scope(), "ATTENDANCE_CORRECTION_REQUIRED", "  " + EXPLANATION + " \n");
    assertThat(w.expect(previewUnlock(t.hr(), padded), 200).get("previewDigest"))
        .isEqualTo(preview.get("previewDigest"));

    // The unlock: version 2, OPEN, the UNLOCKED action reverses the lock with its evidence.
    String key = key();
    MockHttpServletResponse first = w.call(unlock(t.admin(), key, unlocking(reasoned, preview)));
    assertThat(first.getStatus()).isEqualTo(200);
    JsonNode result = JSON.readTree(first.getContentAsString());
    assertThat(result.get("outcome").asText()).isEqualTo("UNLOCKED");
    assertThat(result.get("lock").get("state").asText()).isEqualTo("OPEN");
    assertThat(result.get("lock").get("version").asInt()).isEqualTo(2);
    JsonNode action = result.get("action");
    assertThat(action.get("action").asText()).isEqualTo("UNLOCKED");
    assertThat(action.get("sequence").asInt()).isEqualTo(2);
    assertThat(action.get("lockedActionId").asText()).isEqualTo(lockActionId);
    assertThat(action.get("unlockReason").asText()).isEqualTo("ATTENDANCE_CORRECTION_REQUIRED");
    assertThat(action.get("evidenceDigest"))
        .isEqualTo(lockResult.get("action").get("evidenceDigest"));
    assertThat(result.toString()).doesNotContain(EXPLANATION);
    MockHttpServletResponse replay = w.call(unlock(t.admin(), key, unlocking(reasoned, preview)));
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(JSON.readTree(replay.getContentAsString())).isEqualTo(result);
    w.problem(unlock(t.admin(), key, otherReason), 409, "IDEMPOTENCY_KEY_REUSED");
    w.problem(
        unlock(t.hr(), key(), unlocking(reasoned, preview)), 409, "TIMESHEET_PERIOD_NOT_LOCKED");
    List<Map<String, Object>> actions = actionRows(t);
    assertThat(actions).hasSize(2);
    assertThat(actions.get(1).get("unlock_explanation")).isEqualTo(EXPLANATION);
    assertThat(actions.get(1).get("unlock_reason")).isEqualTo("ATTENDANCE_CORRECTION_REQUIRED");
    assertThat(actions.get(1).get("evidence_digest"))
        .isEqualTo(actions.get(0).get("evidence_digest"));
    assertThat(projection(t).get("state")).isEqualTo("OPEN");
    // 9: unlocking alone changed no timesheet and no export.
    assertThat(sheetRows(t)).isEqualTo(before);
    assertThat(w.call(csv(t.hr(), exportId)).getContentAsByteArray()).isEqualTo(exportBytes);
    assertThat(
            w.expect(mine(t.ana().bearer(), t.sheet(t.ana())), 200).get("periodLocked").asBoolean())
        .isFalse();

    // 10: the correction now reopens Ana's week (MVP-070D), the lock preview says so, and the
    // refresh, resubmission and reapproval make the week ready; the relock is version 3.
    w.expect(anaCorrection(t, t.hr()), 201);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM people.timesheet WHERE id = ?::uuid",
                String.class,
                t.sheet(t.ana())))
        .isEqualTo("REOPENED");
    JsonNode blocked = w.expect(previewLock(t.hr(), t.scope()), 200);
    assertThat(blocked.get("blockers").toString()).isEqualTo("[\"NOT_ALL_APPROVED\"]");
    assertThat(blocked.get("states").get("reopened").asInt()).isEqualTo(1);
    assertThat(blocked.get("lock").get("version").asInt()).isEqualTo(2);
    refreshed(t);
    approveCycle(t, t.ana());
    JsonNode ready = w.expect(previewLock(t.hr(), t.scope()), 200);
    assertThat(ready.get("ready").asBoolean()).isTrue();
    assertThat(ready.get("generationVersion").asInt()).isEqualTo(2);
    assertThat(ready.get("exported").asBoolean()).isFalse(); // the evidence changed
    JsonNode relocked = locked(t, ready);
    assertThat(relocked.get("outcome").asText()).isEqualTo("LOCKED");
    assertThat(relocked.get("lock").get("version").asInt()).isEqualTo(3);
    assertThat(relocked.get("action").get("generationVersion").asInt()).isEqualTo(2);
    assertThat(relocked.get("action").get("evidenceDigest"))
        .isNotEqualTo(lockResult.get("action").get("evidenceDigest"));
    assertThat(relocked.get("action").get("counts").get("workedMinutes").asInt())
        .isLessThan(lockResult.get("action").get("counts").get("workedMinutes").asInt());
    JsonNode history = w.expect(history(t.admin(), t.generationId()), 200);
    assertThat(history.get("actions").findValuesAsText("sequence")).containsExactly("3", "2", "1");
    assertThat(history.get("actions").findValuesAsText("action"))
        .containsExactly("LOCKED", "UNLOCKED", "LOCKED");
    assertThat(history.toString()).doesNotContain(EXPLANATION).doesNotContain("sub-tsp-");
    // The earlier export is unchanged throughout.
    assertThat(w.call(csv(t.hr(), exportId)).getContentAsByteArray()).isEqualTo(exportBytes);
    // Every earlier action is still there, untouched.
    assertThat(actionRows(t).subList(0, 2)).isEqualTo(actions);
  }

  // ------------------------------------------------------------------------------------------
  // 12: authority and isolation
  // ------------------------------------------------------------------------------------------

  @Test
  void onlyTheTenantsAdministratorsWithExactMfaLockUnlockAndReadHistory() throws Exception {
    Week t = approvedWeek();
    ScheduleWorld w = t.w();
    JsonNode preview = w.expect(previewLock(t.admin(), t.scope()), 200);
    Map<String, Object> reasoned = reasoned(t.scope(), "OTHER", EXPLANATION);
    ScheduleWorld other = new ScheduleWorld(mvc, jdbc, lookups);
    String foreign =
        Hierarchy.bearer(other.tenant(), "sub-tsp-f-" + UUID.randomUUID(), "tenant-admin");
    // Another tenant's administrator gets the one safe 404 everywhere.
    w.problem(previewLock(foreign, t.scope()), 404, "TIMESHEET_TARGET_NOT_FOUND");
    w.problem(lock(foreign, key(), locking(t.scope(), preview)), 404, "TIMESHEET_TARGET_NOT_FOUND");
    w.problem(previewUnlock(foreign, reasoned), 404, "TIMESHEET_TARGET_NOT_FOUND");
    w.problem(history(foreign, t.generationId()), 404, "TIMESHEET_TARGET_NOT_FOUND");
    // A legal entity that does not own the site, a foreign legal entity, malformed and unknown
    // identifiers are the same 404; a non-Monday is a validation error.
    UUID otherEntity =
        Hierarchy.newLegalEntity(mvc, w.tenant(), Hierarchy.code("LX"), "2026-01-01", null);
    for (Object entity : List.of(otherEntity, other.org.legalEntityId(), "nope")) {
      w.problem(
          previewLock(t.admin(), TimesheetPeriodCalls.scope(entity, w.site(), t.monday())),
          404,
          "TIMESHEET_TARGET_NOT_FOUND");
    }
    w.problem(
        previewLock(
            t.admin(), TimesheetPeriodCalls.scope(w.org.legalEntityId(), w.otherSite, t.monday())),
        404,
        "TIMESHEET_TARGET_NOT_FOUND");
    w.problem(history(t.admin(), "not-a-uuid"), 404, "TIMESHEET_TARGET_NOT_FOUND");
    w.problem(history(t.admin(), UUID.randomUUID().toString()), 404, "TIMESHEET_TARGET_NOT_FOUND");
    w.problem(
        previewLock(
            t.admin(),
            TimesheetPeriodCalls.scope(w.org.legalEntityId(), w.site(), t.monday().plusDays(1))),
        400,
        "VALIDATION_FAILED");
    // Employees, the manager and a platform administrator have no access to any of it.
    String platform =
        Hierarchy.bearer(w.tenant(), "sub-platform-" + UUID.randomUUID(), "platform-admin");
    for (String bearer : List.of(t.ana().bearer(), t.mia().bearer(), platform)) {
      assertThat(w.call(previewLock(bearer, t.scope())).getStatus()).isEqualTo(403);
      assertThat(w.call(lock(bearer, key(), locking(t.scope(), preview))).getStatus())
          .isEqualTo(403);
      assertThat(w.call(previewUnlock(bearer, reasoned)).getStatus()).isEqualTo(403);
      assertThat(
              w.call(unlock(bearer, key(), unlocking(reasoned, previewShape(preview)))).getStatus())
          .isEqualTo(403);
      assertThat(w.call(history(bearer, t.generationId())).getStatus()).isEqualTo(403);
    }
    // A tenant administrator who signed in with a password only must step up first.
    String subject = "sub-tsp-pwd-" + UUID.randomUUID();
    Memberships.grant(w.tenant(), subject, "tenant-admin");
    String passwordOnly =
        "Bearer "
            + TestTokens.token()
                .tenant(w.tenant())
                .subject(subject)
                .roles(List.of("tenant-admin"))
                .acr(TestTokens.PASSWORD_ACR)
                .build();
    w.problem(previewLock(passwordOnly, t.scope()), 403, "MFA_REQUIRED");
    w.problem(lock(passwordOnly, key(), locking(t.scope(), preview)), 403, "MFA_REQUIRED");
    w.problem(previewUnlock(passwordOnly, reasoned), 403, "MFA_REQUIRED");
    w.problem(history(passwordOnly, t.generationId()), 403, "MFA_REQUIRED");
    // Shape errors: an unknown property (a tenant in the body), no confirmation, no digest, no key.
    Map<String, Object> extra = new LinkedHashMap<>(t.scope());
    extra.put("tenantId", other.tenant().toString());
    w.problem(previewLock(t.admin(), extra), 400, "VALIDATION_FAILED");
    Map<String, Object> unconfirmed = new LinkedHashMap<>(locking(t.scope(), preview));
    unconfirmed.remove("confirm");
    w.problem(lock(t.admin(), key(), unconfirmed), 400, "VALIDATION_FAILED");
    w.problem(lock(t.admin(), null, locking(t.scope(), preview)), 400, "VALIDATION_FAILED");
    w.problem(lock(t.admin(), key(), locking(t.scope(), 1, 0, "ABC")), 400, "VALIDATION_FAILED");
    w.problem(
        lock(t.admin(), key(), locking(t.scope(), 0, 0, preview.get("previewDigest").asText())),
        400,
        "VALIDATION_FAILED");
    assertThat(actionRows(t)).isEmpty();
    // The real administrator locks; the foreign one still sees nothing of it.
    locked(t, preview);
    w.problem(history(foreign, t.generationId()), 404, "TIMESHEET_TARGET_NOT_FOUND");
    assertThat(w.expect(history(t.admin(), t.generationId()), 200).get("actions")).hasSize(1);
  }

  /** A fake unlock preview shape with the lock preview's state (for forbidden callers). */
  private static JsonNode previewShape(JsonNode lockPreview) throws Exception {
    Map<String, Object> shape = new LinkedHashMap<>();
    shape.put("lock", Map.of("version", 1));
    shape.put("currentLock", Map.of("actionId", UUID.randomUUID().toString()));
    shape.put("previewDigest", lockPreview.get("previewDigest").asText());
    return JSON.valueToTree(shape);
  }

  // ------------------------------------------------------------------------------------------
  // 15: the explanation and the actor stay out of audit metadata, events, logs and errors
  // ------------------------------------------------------------------------------------------

  @Test
  void auditEventsLogsAndErrorsCarryNoExplanationNameNumberOrSubject(CapturedOutput output)
      throws Exception {
    Week t = approvedWeek();
    ScheduleWorld w = t.w();
    JsonNode preview = w.expect(previewLock(t.admin(), t.scope()), 200);
    JsonNode lockResult = w.expect(lock(t.admin(), key(), locking(t.scope(), preview)), 200);
    String lockActionId = lockResult.get("action").get("actionId").asText();
    Map<String, Object> reasoned = reasoned(t.scope(), "TIMESHEET_EVIDENCE_ERROR", EXPLANATION);
    JsonNode unlockPreview = w.expect(previewUnlock(t.admin(), reasoned), 200);
    w.expect(unlock(t.admin(), key(), unlocking(reasoned, unlockPreview)), 200);
    w.expect(history(t.admin(), t.generationId()), 200);
    String stale = w.call(lock(t.admin(), key(), locking(t.scope(), preview))).getContentAsString();
    assertThat(stale).contains("TIMESHEET_PERIOD_PREVIEW_STALE");
    String notLocked =
        w.call(unlock(t.admin(), key(), unlocking(reasoned, unlockPreview))).getContentAsString();
    assertThat(notLocked).contains("TIMESHEET_PERIOD_NOT_LOCKED");

    List<String> actions =
        jdbc.queryForList(
            "SELECT action FROM platform.audit_event WHERE tenant_id = ? AND action LIKE"
                + " 'timesheet-period.%' ORDER BY occurred_at",
            String.class, w.tenant());
    assertThat(actions)
        .containsExactlyInAnyOrder(
            "timesheet-period.lock-preview",
            "timesheet-period.lock",
            "timesheet-period.unlock-preview",
            "timesheet-period.unlock",
            "timesheet-period.history");
    JsonNode lockAudit =
        JSON.readTree(
            jdbc.queryForObject(
                "SELECT metadata::text FROM platform.audit_event WHERE tenant_id = ? AND action"
                    + " = 'timesheet-period.lock'",
                String.class,
                w.tenant()));
    assertThat(fields(lockAudit))
        .containsExactly(
            "action",
            "generationVersion",
            "lockVersion",
            "rowCount",
            "schemaVersion",
            "timesheetCount",
            "week",
            "workedMinuteBand");
    JsonNode unlockAudit =
        JSON.readTree(
            jdbc.queryForObject(
                "SELECT metadata::text FROM platform.audit_event WHERE tenant_id = ? AND action"
                    + " = 'timesheet-period.unlock'",
                String.class,
                w.tenant()));
    assertThat(fields(unlockAudit))
        .containsExactly(
            "action",
            "generationVersion",
            "lockVersion",
            "reasonCode",
            "rowCount",
            "schemaVersion",
            "timesheetCount",
            "week",
            "workedMinuteBand");
    assertThat(unlockAudit.get("reasonCode").asText()).isEqualTo("TIMESHEET_EVIDENCE_ERROR");
    assertThat(unlockAudit.get("workedMinuteBand").asText()).isEqualTo("1-2400");
    String audit =
        String.join(
            "\n",
            jdbc.queryForList(
                "SELECT metadata::text || ' ' || coalesce(after_state_sha256, '') FROM"
                    + " platform.audit_event WHERE tenant_id = ? AND action LIKE"
                    + " 'timesheet-period.%'",
                String.class, w.tenant()));
    List<String> events =
        jdbc.queryForList(
            "SELECT envelope::text FROM platform.outbox_event WHERE envelope ->> 'tenantId' = ?"
                + " AND envelope ->> 'eventType' LIKE 'people.timesheet-period.%' ORDER BY"
                + " created_at",
            String.class, w.tenant().toString());
    assertThat(events).hasSize(2);
    JsonNode lockedEvent = JSON.readTree(events.get(0));
    JsonNode unlockedEvent = JSON.readTree(events.get(1));
    assertThat(lockedEvent.get("eventType").asText())
        .isEqualTo("people.timesheet-period.locked.v1");
    assertThat(unlockedEvent.get("eventType").asText())
        .isEqualTo("people.timesheet-period.unlocked.v1");
    assertThat(fields(lockedEvent.get("data")))
        .containsExactly(
            "actionId",
            "employeeCount",
            "generationId",
            "generationVersion",
            "legalEntityId",
            "lockVersion",
            "occurredAt",
            "periodEndAt",
            "periodStartAt",
            "rowCount",
            "siteId",
            "state",
            "timesheetCount",
            "weekStart",
            "workedMinuteBand");
    assertThat(fields(unlockedEvent.get("data")))
        .containsExactly(
            "actionId",
            "employeeCount",
            "generationId",
            "generationVersion",
            "legalEntityId",
            "lockVersion",
            "lockedActionId",
            "occurredAt",
            "periodEndAt",
            "periodStartAt",
            "reasonCode",
            "rowCount",
            "siteId",
            "state",
            "timesheetCount",
            "weekStart",
            "workedMinuteBand");
    assertThat(lockedEvent.get("data").get("actionId").asText()).isEqualTo(lockActionId);
    assertThat(unlockedEvent.get("data").get("lockedActionId").asText()).isEqualTo(lockActionId);
    assertThat(unlockedEvent.get("data").get("state").asText()).isEqualTo("OPEN");
    String evidence = lockResult.get("action").get("evidenceDigest").asText();
    for (String text : List.of(audit, events.get(0), events.get(1), stale, notLocked)) {
      assertThat(text)
          .doesNotContain(EXPLANATION)
          .doesNotContain("corriger")
          .doesNotContain("sub-tsp-")
          .doesNotContain(evidence)
          .doesNotContain(t.sheet(t.ana()))
          .doesNotContain("Kalala")
          .doesNotContain("Ngoy");
      for (Person person : List.of(t.ana(), t.eve(), t.mia())) {
        assertThat(text)
            .doesNotContain(person.number())
            .doesNotContain(person.subject())
            .doesNotContain(person.employee().toString());
      }
    }
    // The audit metadata holds no identifier and no exact total; the state digest is a digest.
    assertThat(lockAudit.toString() + unlockAudit.toString())
        .doesNotContain(lockActionId)
        .doesNotContain(t.generationId())
        .doesNotContain(
            String.valueOf(lockResult.get("action").get("counts").get("workedMinutes")));
    assertThat(
            jdbc.queryForList(
                "SELECT after_state_sha256 FROM platform.audit_event WHERE tenant_id = ? AND action"
                    + " IN ('timesheet-period.lock', 'timesheet-period.unlock')",
                String.class,
                w.tenant()))
        .allMatch(digest -> digest.matches("[0-9a-f]{64}"));
    // Logs: operation, outcome, transition, reason code and bands; no identifier, date,
    // subject, explanation, exact minutes or digest.
    String application =
        String.join(
            "\n",
            output
                .getAll()
                .lines()
                .filter(line -> line.contains("\"logger_name\":\"divalhr.timesheet-period\""))
                .toList());
    assertThat(application)
        .contains("timesheet_period_locked")
        .contains("\"transition\":\"OPEN_TO_LOCKED\"")
        .contains("timesheet_period_unlocked")
        .contains("\"transition\":\"LOCKED_TO_OPEN\"")
        .contains("\"reasonCode\":\"TIMESHEET_EVIDENCE_ERROR\"")
        .contains("\"timesheets\":\"1-10\"")
        .contains("timesheet_period_command_rejected")
        .contains("timesheet_period_read");
    assertThat(application)
        .doesNotContain(EXPLANATION)
        .doesNotContain(lockActionId)
        .doesNotContain(t.generationId())
        .doesNotContain(t.monday().toString())
        .doesNotContain(evidence)
        .doesNotContain("sub-tsp-")
        .doesNotContain(t.ana().number())
        .doesNotContain("Kalala")
        .doesNotContain("\"workedMinutes\"")
        .doesNotContain(preview.get("previewDigest").asText());
  }
}
