package com.divalhr.core.payroll;

import static com.divalhr.core.payroll.PayrollInputCalls.JSON;
import static com.divalhr.core.payroll.PayrollInputCalls.generate;
import static com.divalhr.core.payroll.PayrollInputCalls.generating;
import static com.divalhr.core.payroll.PayrollInputCalls.list;
import static com.divalhr.core.payroll.PayrollInputCalls.preview;
import static com.divalhr.core.payroll.PayrollInputCalls.run;
import static com.divalhr.core.payroll.PayrollInputCalls.scope;
import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.people.schedule.LockedWeeks;
import com.divalhr.core.people.schedule.LockedWeeks.Week;
import com.divalhr.core.people.schedule.ScheduleWorld.Person;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Memberships;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.divalhr.core.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-090A (Issue #141, acceptance criteria 1 to 6, 8 and 9) against real PostgreSQL through the
 * public API only: a complete period of locked weeks generates one traceable run that reconciles
 * exactly to the locked approved time; any missing, open, reopened, stale or mismatched source
 * blocks the whole generation; a foreign tenant or legal entity is never discovered; the identical
 * command is idempotent; a source changed after the preview is one stable conflict with no partial
 * row, audit or event; generated evidence survives later upstream changes and cannot be modified;
 * audits, events, logs and errors carry no name, employee number, subject, digest of a preview or
 * key.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class PayrollInputIntegrationTest {

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

  private LockedWeeks weeks() throws Exception {
    return new LockedWeeks(mvc, jdbc, lookups, clock);
  }

  private static Set<String> fields(JsonNode node) {
    Set<String> names = new TreeSet<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  private static List<String> codes(JsonNode pairs) {
    List<String> codes = new ArrayList<>();
    pairs.forEach(pair -> codes.add(pair.get("code").asText() + "=" + pair.get("count").asInt()));
    return codes;
  }

  private int approvedMinutes(String... generations) {
    int total = 0;
    for (String generation : generations) {
      Integer sum =
          jdbc.queryForObject(
              "SELECT coalesce(sum(worked_minutes), 0) FROM people.timesheet WHERE generation_id ="
                  + " ?::uuid AND status = 'APPROVED'",
              Integer.class,
              generation);
      total += sum == null ? 0 : sum;
    }
    return total;
  }

  private List<Map<String, Object>> runRows(UUID tenant) {
    return jdbc.queryForList(
        "SELECT id, legal_entity_id, version, period_start, period_end, week_count,"
            + " employee_count, timesheet_count, row_count, worked_minutes, excluded_rows,"
            + " excluded_timesheets, evidence_digest FROM payroll.input_run WHERE tenant_id = ?"
            + " ORDER BY generated_at",
        tenant);
  }

  private int payrollAudits(UUID tenant, String action) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ? AND action = ?",
            Integer.class,
            tenant,
            action);
    return n == null ? 0 : n;
  }

  private List<String> events(UUID tenant) {
    return jdbc.queryForList(
        "SELECT envelope::text FROM platform.outbox_event WHERE envelope ->> 'tenantId' = ? AND"
            + " envelope ->> 'eventType' = 'payroll.payroll-input.generated.v1' ORDER BY"
            + " created_at",
        String.class,
        tenant.toString());
  }

  // ------------------------------------------------------------------------------------------
  // 1, 2, 5: one run from complete locked evidence, reconciled exactly, idempotent
  // ------------------------------------------------------------------------------------------

  @Test
  void aCompletePeriodOfLockedWeeksGeneratesOneRunThatReconcilesExactlyToTheLockedTime()
      throws Exception {
    LockedWeeks f = weeks();
    LocalDate monday = f.nextMonday();
    Week first = f.locked(monday);
    Week second = f.locked(monday.plusDays(7));
    String admin = f.admin();

    // A period cutting both weeks: the rows outside it are excluded with a stable code, and the
    // approved timesheets left without a row in it are counted too; the period is still ready.
    Map<String, Object> cut = scope(f.legalEntity(), monday.plusDays(1), monday.plusDays(7));
    JsonNode partial = f.expect(preview(admin, cut), 200);
    assertThat(partial.get("ready").asBoolean()).isTrue();
    assertThat(partial.get("blockers")).isEmpty();
    assertThat(partial.get("eligible").get("rows").asInt())
        .isEqualTo(4); // Ana Tue; Ana, Eve, Zoe Mon
    assertThat(partial.get("eligible").get("timesheets").asInt()).isEqualTo(4);
    assertThat(partial.get("eligible").get("employees").asInt()).isEqualTo(3);
    assertThat(codes(partial.get("excluded")))
        .containsExactly("ROW_OUTSIDE_PERIOD=4", "TIMESHEET_WITHOUT_TIME=2");
    assertThat(partial.get("weeks")).hasSize(2);
    assertThat(partial.get("weeks").get(0).get("counts").get("rows").asInt()).isEqualTo(1);
    assertThat(partial.get("weeks").get(1).get("counts").get("rows").asInt()).isEqualTo(3);

    // The whole two weeks.
    Map<String, Object> whole = scope(f.legalEntity(), monday, monday.plusDays(13));
    JsonNode preview = f.expect(preview(admin, whole), 200);
    assertThat(preview.get("ready").asBoolean()).isTrue();
    assertThat(preview.get("blockers")).isEmpty();
    assertThat(preview.get("scope").get("businessTimezone").asText()).isEqualTo("Africa/Kinshasa");
    assertThat(preview.get("scope").get("legalEntityId").asText())
        .isEqualTo(f.legalEntity().toString());
    assertThat(preview.get("weeks").findValuesAsText("status")).containsExactly("LOCKED", "LOCKED");
    assertThat(preview.get("weeks").findValuesAsText("generationId"))
        .containsExactly(first.generationId(), second.generationId());
    assertThat(preview.get("weeks").get(0).get("lockVersion").asInt()).isEqualTo(1);
    JsonNode eligible = preview.get("eligible");
    int minutes = approvedMinutes(first.generationId(), second.generationId());
    assertThat(eligible.get("timesheets").asInt()).isEqualTo(6);
    assertThat(eligible.get("employees").asInt()).isEqualTo(3);
    assertThat(eligible.get("rows").asInt()).isEqualTo(8);
    assertThat(eligible.get("workedMinutes").asInt()).isPositive().isEqualTo(minutes);
    assertThat(codes(preview.get("excluded")))
        .containsExactly("ROW_OUTSIDE_PERIOD=0", "TIMESHEET_WITHOUT_TIME=0");
    assertThat(preview.get("existingRunId").isNull()).isTrue();
    assertThat(preview.get("existingRunUnchanged").isNull()).isTrue();
    assertThat(preview.get("previewDigest").asText()).matches("[0-9a-f]{64}");
    assertThat(preview.get("sourceVersionsDigest").asText()).matches("[0-9a-f]{64}");
    assertThat(Instant.parse(preview.get("expiresAt").asText())).isAfter(clock.instant());
    assertThat(preview.toString())
        .doesNotContain("Kalala")
        .doesNotContain(f.ana().number())
        .doesNotContain(f.ana().subject());
    // The digest does not depend on who previews.
    assertThat(f.expect(preview(f.administrator(), whole), 200).get("previewDigest"))
        .isEqualTo(preview.get("previewDigest"));

    // 1: the generation.
    String key = key();
    MockHttpServletResponse created = f.call(generate(admin, key, generating(whole, preview)));
    assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(200);
    assertThat(created.getHeader("Cache-Control")).isEqualTo("private, no-store");
    JsonNode result = JSON.readTree(created.getContentAsString());
    assertThat(result.get("outcome").asText()).isEqualTo("GENERATED");
    JsonNode run = result.get("run");
    String runId = run.get("runId").asText();
    assertThat(run.get("status").asText()).isEqualTo("GENERATED");
    assertThat(run.get("version").asInt()).isEqualTo(1);
    assertThat(run.get("schemaVersion").asInt()).isEqualTo(1);
    assertThat(run.get("weekCount").asInt()).isEqualTo(2);
    assertThat(run.get("counts")).isEqualTo(eligible);
    assertThat(run.get("evidenceDigest").asText()).matches("[0-9a-f]{64}");

    // 2: the stored run, weeks, employee rows and source rows reconcile exactly to the locked
    // approved time: every row is a READY row of the current revision of a timesheet APPROVED by
    // its current action, bound to the generation's LOCKED action; the sums agree.
    List<Map<String, Object>> runs = runRows(f.tenant());
    assertThat(runs).hasSize(1);
    Map<String, Object> stored = runs.get(0);
    assertThat(stored.get("week_count")).isEqualTo(2);
    assertThat(stored.get("employee_count")).isEqualTo(3);
    assertThat(stored.get("timesheet_count")).isEqualTo(6);
    assertThat(stored.get("row_count")).isEqualTo(8);
    assertThat(stored.get("worked_minutes")).isEqualTo(minutes);
    assertThat(stored.get("excluded_rows")).isEqualTo(0);
    assertThat(stored.get("excluded_timesheets")).isEqualTo(0);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payroll.input_source_row r JOIN people.timesheet t ON t.id ="
                    + " r.timesheet_id AND t.tenant_id = r.tenant_id JOIN people.timesheet_row s ON"
                    + " s.tenant_id = r.tenant_id AND s.timesheet_id = r.timesheet_id AND"
                    + " s.revision = r.timesheet_revision AND s.slot_id = r.shift_id JOIN"
                    + " people.timesheet_action a ON a.tenant_id = r.tenant_id AND a.id ="
                    + " r.approval_action_id JOIN people.timesheet_period_action k ON k.tenant_id ="
                    + " r.tenant_id AND k.id = r.lock_action_id WHERE r.run_id = ?::uuid AND"
                    + " t.status = 'APPROVED' AND t.current_action_id = r.approval_action_id AND"
                    + " t.current_revision = r.timesheet_revision AND a.action = 'APPROVED' AND"
                    + " a.cycle = r.approval_cycle AND s.state = 'READY' AND s.worked_minutes ="
                    + " r.worked_minutes AND s.employment_id = r.employment_id AND k.action ="
                    + " 'LOCKED' AND k.generation_id = r.generation_id AND k.sequence = 1 AND"
                    + " r.work_date = (s.scheduled_start_at AT TIME ZONE 'Africa/Kinshasa')::date",
                Integer.class,
                runId))
        .isEqualTo(8);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM people.timesheet_row s JOIN people.timesheet t ON t.id ="
                    + " s.timesheet_id AND t.current_revision = s.revision WHERE t.generation_id IN"
                    + " (?::uuid, ?::uuid) AND t.status = 'APPROVED' AND s.state = 'READY' AND NOT"
                    + " EXISTS (SELECT 1 FROM payroll.input_source_row r WHERE r.run_id = ?::uuid"
                    + " AND r.timesheet_id = s.timesheet_id AND r.shift_id = s.slot_id)",
                Integer.class,
                first.generationId(),
                second.generationId(),
                runId))
        .as("no approved row of the period is missing from the run")
        .isZero();
    assertThat(
            jdbc.queryForList(
                "SELECT k.generation_id::text FROM payroll.input_source_week k WHERE k.run_id ="
                    + " ?::uuid AND k.lock_action_id = (SELECT a.id FROM"
                    + " people.timesheet_period_action a WHERE a.generation_id = k.generation_id"
                    + " AND a.sequence = 1) AND k.lock_evidence_digest = (SELECT a.evidence_digest"
                    + " FROM people.timesheet_period_action a WHERE a.generation_id ="
                    + " k.generation_id AND a.sequence = 1) AND k.lock_version = 1 AND"
                    + " k.generation_version = 1 ORDER BY k.week_start",
                String.class,
                runId))
        .containsExactly(first.generationId(), second.generationId());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payroll.input_employee e WHERE e.run_id = ?::uuid AND"
                    + " e.worked_minutes = (SELECT sum(r.worked_minutes) FROM"
                    + " payroll.input_source_row r WHERE r.run_id = e.run_id AND r.employee_id ="
                    + " e.employee_id AND r.employment_id = e.employment_id)",
                Integer.class,
                runId))
        .isEqualTo(3);

    // 5: the identical command replays; a changed payload under the key is refused; a new key
    // with a fresh preview returns the EXISTING run; the preview names it as unchanged.
    MockHttpServletResponse replay = f.call(generate(admin, key, generating(whole, preview)));
    assertThat(replay.getStatus()).isEqualTo(200);
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(JSON.readTree(replay.getContentAsString())).isEqualTo(result);
    Map<String, Object> changed =
        generating(
            whole,
            differentDigest(preview.get("previewDigest").asText()),
            preview.get("sourceVersionsDigest").asText());
    f.problem(generate(admin, key, changed), 409, "IDEMPOTENCY_KEY_REUSED");
    JsonNode again = f.expect(preview(f.administrator(), whole), 200);
    assertThat(again.get("existingRunId").asText()).isEqualTo(runId);
    assertThat(again.get("existingRunUnchanged").asBoolean()).isTrue();
    JsonNode existing = f.expect(generate(f.administrator(), key(), generating(whole, again)), 200);
    assertThat(existing.get("outcome").asText()).isEqualTo("EXISTING");
    assertThat(existing.get("run").get("runId").asText()).isEqualTo(runId);
    assertThat(runRows(f.tenant())).hasSize(1);

    // The run is read with its source weeks and employee inputs, and listed newest first.
    JsonNode detail = f.expect(run(admin, runId), 200);
    assertThat(detail.get("run")).isEqualTo(run);
    assertThat(detail.get("weeks")).hasSize(2);
    assertThat(detail.get("weeks").findValuesAsText("generationId"))
        .containsExactly(first.generationId(), second.generationId());
    assertThat(detail.get("weeks").get(0).get("counts").get("rows").asInt()).isEqualTo(4);
    assertThat(detail.get("employees")).hasSize(3);
    for (JsonNode employee : detail.get("employees")) {
      assertThat(fields(employee))
          .containsExactly("counts", "employeeId", "employmentId", "firstWorkDate", "lastWorkDate");
    }
    JsonNode anaRow = null;
    for (JsonNode employee : detail.get("employees")) {
      if (employee.get("employeeId").asText().equals(f.ana().employee().toString())) {
        anaRow = employee;
      }
    }
    assertThat(anaRow).isNotNull();
    assertThat(anaRow.get("counts").get("rows").asInt()).isEqualTo(4);
    assertThat(anaRow.get("counts").get("timesheets").asInt()).isEqualTo(2);
    assertThat(anaRow.get("firstWorkDate").asText()).isEqualTo(monday.toString());
    assertThat(anaRow.get("lastWorkDate").asText()).isEqualTo(monday.plusDays(8).toString());
    assertThat(detail.toString()).doesNotContain("Kalala").doesNotContain(f.ana().number());
    JsonNode page = f.expect(list(admin, null), 200);
    assertThat(page.get("items")).hasSize(1);
    assertThat(page.get("items").get(0).get("runId").asText()).isEqualTo(runId);
    assertThat(page.get("nextCursor").isNull()).isTrue();
    JsonNode filtered = f.expect(list(admin, "legalEntityId=" + f.legalEntity()), 200);
    assertThat(filtered.get("items")).hasSize(1);
    assertThat(f.expect(list(admin, "legalEntityId=" + UUID.randomUUID()), 200).get("items"))
        .isEmpty();
    f.problem(list(admin, "legalEntityId=nope"), 400, "VALIDATION_FAILED");
    f.problem(list(admin, "limit=0"), 400, "VALIDATION_FAILED");
  }

  // ------------------------------------------------------------------------------------------
  // 3: missing, open, reopened or stale sources block the whole generation
  // ------------------------------------------------------------------------------------------

  @Test
  void anyMissingOpenReopenedOrStaleSourceBlocksTheWholeGeneration() throws Exception {
    LockedWeeks f = weeks();
    LocalDate monday = f.nextMonday();
    Week first = f.locked(monday);
    Week second = f.approved(f.generated(monday.plusDays(7)));
    // A shift of a third week, scheduled but never generated; the clock moves past that week.
    f.scheduledOnly(f.site(), monday.plusDays(14));
    f.sundayNoon(monday.plusDays(14));
    String admin = f.admin();
    Map<String, Object> whole = scope(f.legalEntity(), monday, monday.plusDays(20));

    // An approved but unlocked week and a week with scheduled work that was never generated both
    // block; the generation is refused with its own preview; nothing partial is written.
    JsonNode open = f.expect(preview(admin, whole), 200);
    assertThat(open.get("ready").asBoolean()).isFalse();
    assertThat(codes(open.get("blockers")))
        .containsExactly("WEEK_NOT_GENERATED=1", "WEEK_NOT_LOCKED=1");
    assertThat(open.get("weeks").findValuesAsText("status"))
        .containsExactly("LOCKED", "NOT_LOCKED", "NOT_GENERATED");
    assertThat(open.get("eligible").get("rows").asInt()).isEqualTo(4);
    f.problem(generate(admin, key(), generating(whole, open)), 409, "PAYROLL_INPUT_NOT_READY");
    f.lock(second);
    JsonNode missing = f.expect(preview(admin, whole), 200);
    assertThat(codes(missing.get("blockers"))).containsExactly("WEEK_NOT_GENERATED=1");
    assertThat(missing.get("weeks")).hasSize(3);
    assertThat(missing.get("weeks").findValuesAsText("status"))
        .containsExactly("LOCKED", "LOCKED", "NOT_GENERATED");
    f.problem(generate(admin, key(), generating(whole, missing)), 409, "PAYROLL_INPUT_NOT_READY");
    // A period that stops before that week is ready; a preview of it is taken now.
    Map<String, Object> firstWeek = scope(f.legalEntity(), monday, monday.plusDays(6));
    JsonNode ready = f.expect(preview(admin, firstWeek), 200);
    assertThat(ready.get("ready").asBoolean()).isTrue();
    // An unlock makes the week open: the earlier preview is stale, the new one blocked.
    f.unlock(first);
    f.problem(
        generate(admin, key(), generating(firstWeek, ready)), 409, "PAYROLL_INPUT_PREVIEW_STALE");
    JsonNode unlocked = f.expect(preview(admin, firstWeek), 200);
    assertThat(codes(unlocked.get("blockers"))).containsExactly("WEEK_NOT_LOCKED=1");
    assertThat(unlocked.get("weeks").get(0).get("status").asText()).isEqualTo("NOT_LOCKED");
    // The correction reopens Ana's week; the refresh, resubmission and reapproval allow a relock;
    // the relock is version 3 and the period is ready again with the new evidence.
    assertThat(f.correctAna(first, 20).getStatus()).isEqualTo(201);
    f.refresh(first);
    f.approveCycle(first, f.ana());
    f.lock(first);
    JsonNode relocked = f.expect(preview(admin, firstWeek), 200);
    assertThat(relocked.get("ready").asBoolean()).isTrue();
    assertThat(relocked.get("weeks").get(0).get("lockVersion").asInt()).isEqualTo(3);
    assertThat(relocked.get("weeks").get(0).get("generationVersion").asInt()).isEqualTo(2);
    assertThat(relocked.get("eligible").get("workedMinutes").asInt())
        .isLessThan(ready.get("eligible").get("workedMinutes").asInt());
    assertThat(relocked.get("previewDigest")).isNotEqualTo(ready.get("previewDigest"));
    assertThat(relocked.get("sourceVersionsDigest"))
        .isNotEqualTo(ready.get("sourceVersionsDigest"));
    // The stale preview is still refused; the fresh one generates.
    f.problem(
        generate(admin, key(), generating(firstWeek, ready)), 409, "PAYROLL_INPUT_PREVIEW_STALE");
    JsonNode generated = f.expect(generate(admin, key(), generating(firstWeek, relocked)), 200);
    assertThat(generated.get("outcome").asText()).isEqualTo("GENERATED");
    assertThat(generated.get("run").get("counts").get("workedMinutes").asInt())
        .isEqualTo(approvedMinutes(first.generationId()));
    // Nothing partial was ever written: exactly one run.
    assertThat(runRows(f.tenant())).hasSize(1);
    assertThat(payrollAudits(f.tenant(), "payroll-input.generate")).isEqualTo(1);
    assertThat(events(f.tenant())).hasSize(1);

    // A period without any week that matters has no approved time; an expired preview is stale;
    // periods outside the pilot boundaries are validation errors.
    JsonNode nothing =
        f.expect(
            preview(admin, scope(f.legalEntity(), monday.minusDays(28), monday.minusDays(22))),
            200);
    assertThat(codes(nothing.get("blockers"))).containsExactly("NO_APPROVED_TIME=1");
    assertThat(nothing.get("weeks")).isEmpty();
    JsonNode fresh = f.expect(preview(admin, firstWeek), 200);
    Instant now = clock.instant();
    clock.set(Instant.parse(fresh.get("expiresAt").asText()));
    f.problem(
        generate(admin, key(), generating(firstWeek, fresh)), 409, "PAYROLL_INPUT_PREVIEW_STALE");
    clock.set(now);
    f.problem(
        preview(admin, scope(f.legalEntity(), monday.plusDays(6), monday)),
        400,
        "VALIDATION_FAILED");
    f.problem(
        preview(admin, scope(f.legalEntity(), monday, monday.plusDays(31))),
        400,
        "VALIDATION_FAILED");
    f.problem(
        preview(admin, scope(f.legalEntity(), "2025-12-29", "2026-01-04")),
        400,
        "VALIDATION_FAILED");
    // A period ending after today (the clock stands at the third week's Sunday noon).
    f.problem(
        preview(admin, scope(f.legalEntity(), monday.plusDays(14), monday.plusDays(21))),
        400,
        "VALIDATION_FAILED");
  }

  // ------------------------------------------------------------------------------------------
  // 6, 8: a stale source is one conflict with no partial row, audit or event; evidence survives
  // ------------------------------------------------------------------------------------------

  @Test
  void aSourceChangedAfterPreviewIsOneConflictAndGeneratedEvidenceSurvivesLaterChanges()
      throws Exception {
    LockedWeeks f = weeks();
    LocalDate monday = f.nextMonday();
    Week week = f.locked(monday);
    String admin = f.admin();
    Map<String, Object> period = scope(f.legalEntity(), monday, monday.plusDays(6));
    JsonNode preview = f.expect(preview(admin, period), 200);
    // 6: the source changes after the preview (unlock, correction, refresh, reapproval, relock).
    f.unlock(week);
    assertThat(f.correctAna(week, 20).getStatus()).isEqualTo(201);
    f.refresh(week);
    f.approveCycle(week, f.ana());
    f.lock(week);
    f.problem(
        generate(admin, key(), generating(period, preview)), 409, "PAYROLL_INPUT_PREVIEW_STALE");
    // The right digests but the versions of the stale preview: still stale.
    JsonNode current = f.expect(preview(admin, period), 200);
    f.problem(
        generate(
            admin,
            key(),
            generating(
                period,
                current.get("previewDigest").asText(),
                preview.get("sourceVersionsDigest").asText())),
        409,
        "PAYROLL_INPUT_PREVIEW_STALE");
    assertThat(runRows(f.tenant())).isEmpty();
    assertThat(payrollAudits(f.tenant(), "payroll-input.generate")).isZero();
    assertThat(events(f.tenant())).isEmpty();
    assertThat(
            f.count(
                "SELECT count(*) FROM payroll.input_source_row WHERE tenant_id = ?", f.tenant()))
        .isZero();
    // The fresh preview generates.
    JsonNode result = f.expect(generate(admin, key(), generating(period, current)), 200);
    String runId = result.get("run").get("runId").asText();
    List<Map<String, Object>> before = runRows(f.tenant());
    List<Map<String, Object>> rowsBefore =
        jdbc.queryForList(
            "SELECT * FROM payroll.input_source_row WHERE run_id = ?::uuid ORDER BY line", runId);
    String evidence = result.get("run").get("evidenceDigest").asText();

    // 8: later upstream changes (unlock, correction, refresh, reapproval, relock) leave the run
    // as it was; the period cannot be generated again with different evidence, and the preview
    // says the existing run no longer matches.
    f.unlock(week);
    assertThat(f.correctAna(week, 30).getStatus()).isEqualTo(201);
    f.refresh(week);
    f.approveCycle(week, f.ana());
    f.lock(week);
    JsonNode changed = f.expect(preview(admin, period), 200);
    assertThat(changed.get("ready").asBoolean()).isTrue();
    assertThat(changed.get("existingRunId").asText()).isEqualTo(runId);
    assertThat(changed.get("existingRunUnchanged").asBoolean()).isFalse();
    f.problem(generate(admin, key(), generating(period, changed)), 409, "PAYROLL_INPUT_RUN_EXISTS");
    assertThat(runRows(f.tenant())).isEqualTo(before);
    assertThat(
            jdbc.queryForList(
                "SELECT * FROM payroll.input_source_row WHERE run_id = ?::uuid ORDER BY line",
                runId))
        .isEqualTo(rowsBefore);
    assertThat(f.expect(run(admin, runId), 200).get("run").get("evidenceDigest").asText())
        .isEqualTo(evidence);
    // 8: the database refuses every edit and deletion of generated evidence.
    for (String sql :
        List.of(
            "UPDATE payroll.input_run SET worked_minutes = worked_minutes + 1 WHERE id = ?::uuid",
            "DELETE FROM payroll.input_run WHERE id = ?::uuid",
            "UPDATE payroll.input_source_row SET worked_minutes = 0 WHERE run_id = ?::uuid",
            "DELETE FROM payroll.input_source_row WHERE run_id = ?::uuid",
            "UPDATE payroll.input_employee SET worked_minutes = 0 WHERE run_id = ?::uuid",
            "DELETE FROM payroll.input_employee WHERE run_id = ?::uuid",
            "UPDATE payroll.input_source_week SET lock_version = 5 WHERE run_id = ?::uuid",
            "DELETE FROM payroll.input_source_week WHERE run_id = ?::uuid")) {
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(sql, runId))
          .as(sql)
          .isInstanceOf(DataIntegrityViolationException.class)
          .hasMessageContaining("payroll-input evidence is never");
    }
    assertThat(runRows(f.tenant())).isEqualTo(before);
    assertThat(payrollAudits(f.tenant(), "payroll-input.generate")).isEqualTo(1);
    assertThat(events(f.tenant())).hasSize(1);
  }

  // ------------------------------------------------------------------------------------------
  // 4: authority and isolation
  // ------------------------------------------------------------------------------------------

  @Test
  void aForeignTenantOrLegalEntityIsNeverDiscoveredAndOnlyExactMfaAdministratorsAct()
      throws Exception {
    LockedWeeks other = weeks();
    LockedWeeks f = weeks();
    LocalDate monday = f.nextMonday();
    f.locked(monday);
    String admin = f.admin();
    Map<String, Object> period = scope(f.legalEntity(), monday, monday.plusDays(6));
    JsonNode preview = f.expect(preview(admin, period), 200);
    String foreign = other.admin();
    // Another tenant's administrator gets the one safe 404 everywhere; the foreign legal entity,
    // a malformed one and an unknown one are the same 404 for the tenant's own administrator.
    f.problem(preview(foreign, period), 404, "PAYROLL_INPUT_TARGET_NOT_FOUND");
    f.problem(
        generate(foreign, key(), generating(period, preview)),
        404,
        "PAYROLL_INPUT_TARGET_NOT_FOUND");
    for (Object entity : List.of(other.legalEntity(), UUID.randomUUID(), "nope")) {
      f.problem(
          preview(admin, scope(entity, monday, monday.plusDays(6))),
          404,
          "PAYROLL_INPUT_TARGET_NOT_FOUND");
    }
    // A second legal entity of the same tenant has no site and no time: not an error, no evidence.
    UUID otherEntity =
        Hierarchy.newLegalEntity(mvc, f.tenant(), Hierarchy.code("LY"), "2026-01-01", null);
    JsonNode empty = f.expect(preview(admin, scope(otherEntity, monday, monday.plusDays(6))), 200);
    assertThat(codes(empty.get("blockers"))).containsExactly("NO_APPROVED_TIME=1");
    assertThat(empty.get("weeks")).isEmpty();
    // Employees, the manager and a platform administrator have no access to any of it.
    String platform =
        Hierarchy.bearer(f.tenant(), "sub-platform-" + UUID.randomUUID(), "platform-admin");
    for (String bearer : List.of(f.ana().bearer(), f.mia().bearer(), platform)) {
      assertThat(f.call(preview(bearer, period)).getStatus()).isEqualTo(403);
      assertThat(f.call(generate(bearer, key(), generating(period, preview))).getStatus())
          .isEqualTo(403);
      assertThat(f.call(list(bearer, null)).getStatus()).isEqualTo(403);
      assertThat(f.call(run(bearer, UUID.randomUUID().toString())).getStatus()).isEqualTo(403);
    }
    // A tenant administrator who signed in with a password only must step up first.
    String subject = "sub-pay90-pwd-" + UUID.randomUUID();
    Memberships.grant(f.tenant(), subject, "tenant-admin");
    String passwordOnly =
        "Bearer "
            + TestTokens.token()
                .tenant(f.tenant())
                .subject(subject)
                .roles(List.of("tenant-admin"))
                .acr(TestTokens.PASSWORD_ACR)
                .build();
    f.problem(preview(passwordOnly, period), 403, "MFA_REQUIRED");
    f.problem(generate(passwordOnly, key(), generating(period, preview)), 403, "MFA_REQUIRED");
    f.problem(list(passwordOnly, null), 403, "MFA_REQUIRED");
    // Shape errors: a tenant in the body, no confirmation, no digest, no key, bad dates.
    Map<String, Object> extra = new LinkedHashMap<>(period);
    extra.put("tenantId", other.tenant().toString());
    f.problem(preview(admin, extra), 400, "VALIDATION_FAILED");
    Map<String, Object> unconfirmed = new LinkedHashMap<>(generating(period, preview));
    unconfirmed.remove("confirm");
    f.problem(generate(admin, key(), unconfirmed), 400, "VALIDATION_FAILED");
    f.problem(generate(admin, null, generating(period, preview)), 400, "VALIDATION_FAILED");
    f.problem(
        generate(
            admin, key(), generating(period, "ABC", preview.get("sourceVersionsDigest").asText())),
        400,
        "VALIDATION_FAILED");
    f.problem(
        preview(admin, scope(f.legalEntity(), "2026-13-01", monday)), 400, "VALIDATION_FAILED");
    f.problem(preview(admin, scope(f.legalEntity(), null, monday)), 400, "VALIDATION_FAILED");
    assertThat(runRows(f.tenant())).isEmpty();
    // The real administrator generates; the foreign one still sees nothing of it, and a run of
    // another tenant is the same 404 as an unknown one.
    String runId =
        f.expect(generate(admin, key(), generating(period, preview)), 200)
            .get("run")
            .get("runId")
            .asText();
    f.problem(run(foreign, runId), 404, "PAYROLL_INPUT_TARGET_NOT_FOUND");
    f.problem(run(admin, UUID.randomUUID().toString()), 404, "PAYROLL_INPUT_TARGET_NOT_FOUND");
    f.problem(run(admin, "not-a-uuid"), 404, "PAYROLL_INPUT_TARGET_NOT_FOUND");
    assertThat(f.expect(list(foreign, null), 200).get("items")).isEmpty();
    assertThat(f.expect(list(admin, null), 200).get("items")).hasSize(1);
    // The refused calls left privileged-denial evidence (the role stage for Ana, the MFA stage for
    // the password-only administrator).
    assertThat(
            f.count(
                "SELECT count(*) FROM platform.authorization_denial WHERE actor_subject = ? AND"
                    + " operation LIKE 'payroll-input.%' AND stage = 'role'",
                f.ana().subject()))
        .isEqualTo(4);
    assertThat(
            f.count(
                "SELECT count(*) FROM platform.authorization_denial WHERE actor_subject = ? AND"
                    + " operation LIKE 'payroll-input.%'",
                subject))
        .isEqualTo(3);
  }

  // ------------------------------------------------------------------------------------------
  // 9: audit, events, logs and errors carry no name, number, subject, key or preview digest
  // ------------------------------------------------------------------------------------------

  @Test
  void auditEventsLogsAndErrorsCarryNoNameNumberSubjectKeyOrPreviewDigest(CapturedOutput output)
      throws Exception {
    LockedWeeks f = weeks();
    LocalDate monday = f.nextMonday();
    Week week = f.locked(monday);
    String admin = f.admin();
    Map<String, Object> period = scope(f.legalEntity(), monday, monday.plusDays(6));
    JsonNode preview = f.expect(preview(admin, period), 200);
    String key = key();
    JsonNode result = f.expect(generate(admin, key, generating(period, preview)), 200);
    String runId = result.get("run").get("runId").asText();
    f.expect(run(admin, runId), 200);
    f.expect(list(admin, null), 200);
    f.unlock(week);
    String stale = f.call(generate(admin, key(), generating(period, preview))).getContentAsString();
    assertThat(stale).contains("PAYROLL_INPUT_PREVIEW_STALE");
    JsonNode blocked = f.expect(preview(admin, period), 200);
    String notReady =
        f.call(generate(admin, key(), generating(period, blocked))).getContentAsString();
    assertThat(notReady).contains("PAYROLL_INPUT_NOT_READY");

    List<String> actions =
        jdbc.queryForList(
            "SELECT DISTINCT action FROM platform.audit_event WHERE tenant_id = ? AND action LIKE"
                + " 'payroll-input.%' ORDER BY action",
            String.class, f.tenant());
    assertThat(actions)
        .containsExactly(
            "payroll-input.generate",
            "payroll-input.list",
            "payroll-input.preview",
            "payroll-input.read");
    JsonNode generateAudit =
        JSON.readTree(
            jdbc.queryForObject(
                "SELECT metadata::text FROM platform.audit_event WHERE tenant_id = ? AND action ="
                    + " 'payroll-input.generate'",
                String.class,
                f.tenant()));
    assertThat(fields(generateAudit))
        .containsExactly(
            "employeeCount",
            "periodEnd",
            "periodStart",
            "rowCount",
            "runVersion",
            "schemaVersion",
            "timesheetCount",
            "weekCount",
            "workedMinuteBand");
    assertThat(generateAudit.get("workedMinuteBand").asText()).isEqualTo("1-2400");
    assertThat(
            jdbc.queryForObject(
                "SELECT resource_id::text FROM platform.audit_event WHERE tenant_id = ? AND action"
                    + " = 'payroll-input.generate'",
                String.class,
                f.tenant()))
        .isEqualTo(runId);
    List<String> events = events(f.tenant());
    assertThat(events).hasSize(1);
    JsonNode event = JSON.readTree(events.get(0));
    assertThat(event.get("eventType").asText()).isEqualTo("payroll.payroll-input.generated.v1");
    assertThat(event.get("source").asText()).isEqualTo("core-api/payroll");
    assertThat(event.get("subject").asText()).isEqualTo(runId);
    assertThat(fields(event.get("data")))
        .containsExactly(
            "employeeCount",
            "legalEntityId",
            "occurredAt",
            "periodEnd",
            "periodStart",
            "rowCount",
            "runId",
            "runVersion",
            "schemaVersion",
            "sources",
            "status",
            "timesheetCount",
            "weekCount",
            "workedMinuteBand");
    assertThat(event.get("data").get("sources")).hasSize(1);
    assertThat(fields(event.get("data").get("sources").get(0)))
        .containsExactly(
            "generationId",
            "generationVersion",
            "lockActionId",
            "lockVersion",
            "siteId",
            "weekStart");
    assertThat(event.get("data").get("sources").get(0).get("generationId").asText())
        .isEqualTo(week.generationId());
    String audit =
        String.join(
            "\n",
            jdbc.queryForList(
                "SELECT metadata::text || ' ' || coalesce(after_state_sha256, '') FROM"
                    + " platform.audit_event WHERE tenant_id = ? AND action LIKE 'payroll-input.%'",
                String.class, f.tenant()));
    String evidence = result.get("run").get("evidenceDigest").asText();
    for (String text : List.of(audit, events.get(0), stale, notReady)) {
      assertThat(text)
          .doesNotContain("Kalala")
          .doesNotContain("Ngoy")
          .doesNotContain("Tshala")
          .doesNotContain(preview.get("previewDigest").asText())
          .doesNotContain(key)
          .doesNotContain("sub-pay90-")
          .doesNotContain(evidence);
      for (Person person : List.of(f.ana(), f.eve(), f.zoe(), f.mia())) {
        assertThat(text)
            .doesNotContain(person.number())
            .doesNotContain(person.subject())
            .doesNotContain(person.employee().toString());
      }
    }
    // The audit metadata holds no identifier and no exact total; the state digest is a digest.
    assertThat(generateAudit.toString())
        .doesNotContain(runId)
        .doesNotContain(week.generationId())
        .doesNotContain(String.valueOf(result.get("run").get("counts").get("workedMinutes")));
    assertThat(
            jdbc.queryForList(
                "SELECT after_state_sha256 FROM platform.audit_event WHERE tenant_id = ? AND action"
                    + " = 'payroll-input.generate'",
                String.class,
                f.tenant()))
        .allMatch(digest -> digest.matches("[0-9a-f]{64}"));
    // Logs: operation, outcome and bands; no identifier, date, subject, key, digest or minutes.
    String application =
        String.join(
            "\n",
            output
                .getAll()
                .lines()
                .filter(line -> line.contains("\"logger_name\":\"divalhr.payroll-input\""))
                .toList());
    assertThat(application)
        .contains("payroll_input_generated")
        .contains("\"employees\":\"1-10\"")
        .contains("\"weeks\":\"1-10\"")
        .contains("payroll_input_command_rejected")
        .contains("payroll_input_read");
    assertThat(application)
        .doesNotContain(runId)
        .doesNotContain(week.generationId())
        .doesNotContain(monday.toString())
        .doesNotContain(evidence)
        .doesNotContain(key)
        .doesNotContain("sub-pay90-")
        .doesNotContain(f.ana().number())
        .doesNotContain("Kalala")
        .doesNotContain("\"workedMinutes\"")
        .doesNotContain(preview.get("previewDigest").asText());
  }

  /**
   * A digest that is certainly different (a plain replace is a no-op when the letter is absent).
   */
  private static String differentDigest(String digest) {
    return (digest.charAt(0) == 'a' ? "b" : "a") + digest.substring(1);
  }
}
