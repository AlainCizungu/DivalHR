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
import static com.divalhr.core.people.schedule.TimesheetExportCalls.export;
import static com.divalhr.core.people.schedule.TimesheetExportCalls.list;
import static com.divalhr.core.people.schedule.TimesheetExportCalls.manifest;
import static com.divalhr.core.people.schedule.TimesheetExportCalls.preview;
import static com.divalhr.core.people.schedule.TimesheetExportCalls.scope;
import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.people.schedule.ScheduleWorld.Person;
import com.divalhr.core.people.schedule.domain.Timesheet;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
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
 * MVP-070E (Issue #134, T70E-11 items 1 to 9 and 12) against real PostgreSQL: only the current
 * approval of each timesheet is exported; drafts, returned and submitted time, reopened timesheets
 * and superseded approvals never are; previews go stale on any change of the eligible evidence and
 * after their window; creation is idempotent and identical evidence returns the existing export;
 * the CSV and the manifest are deterministic and verifiable; history, authority, isolation and
 * privacy hold throughout.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class TimesheetExportIntegrationTest {

  private static final ZoneId KINSHASA = ZoneId.of("Africa/Kinshasa");
  private static final String HEADER =
      "\"schema_version\",\"export_id\",\"legal_entity_id\",\"site_id\",\"site_timezone\","
          + "\"week_start\",\"week_end\",\"employee_id\",\"employee_number\",\"employment_id\","
          + "\"cost_center_id\",\"work_date\",\"shift_id\",\"timesheet_id\",\"timesheet_revision\","
          + "\"approval_action_id\",\"approval_cycle\",\"worked_minutes\"";

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
   * A generated week whose clock is its Sunday noon at the site. Mia manages Ana, Eve, Ben and Zoe.
   *
   * <ul>
   *   <li>Ana: Monday and Tuesday checked in and out (two READY rows), submitted and approved.
   *   <li>Eve: Monday checked in and out, submitted and approved.
   *   <li>Ben: Monday checked in only (a DRAFT with an open row).
   *   <li>Zoe: Monday checked in and out, submitted and awaiting Mia's decision.
   * </ul>
   */
  private record Week(
      ScheduleWorld w,
      String admin,
      Person mia,
      Person ana,
      Person eve,
      Person ben,
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
      return Hierarchy.bearer(w.tenant(), "sub-tse-" + UUID.randomUUID(), "tenant-admin");
    }

    Map<String, Object> scope() {
      return TimesheetExportCalls.scope(w.org.legalEntityId(), w.site(), monday);
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
    Person zoe = w.person("Zoe", "Tshala");
    w.manageAll(mia, ana, eve, ben, zoe);
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
                        shift("z0", monday, 8, 16, zoe.employee()),
                        shift("a1", monday.plusDays(1), 8, 16, ana.employee())))));
    Map<String, Instant[]> shifts = new LinkedHashMap<>();
    shifts.put("a0", instants(jdbc, slots.get(0)));
    shifts.put("a1", instants(jdbc, slots.get(4)));
    Instant[] mon = shifts.get("a0");
    Map<String, String> records = new LinkedHashMap<>();
    clock.set(mon[0].plus(Duration.ofMinutes(5)));
    records.put("a0", record(w, checkIn(ana, key(), slots.get(0))));
    records.put("e0", record(w, checkIn(eve, key(), slots.get(1))));
    records.put("b0", record(w, checkIn(ben, key(), slots.get(2))));
    records.put("z0", record(w, checkIn(zoe, key(), slots.get(3))));
    clock.set(mon[1].minus(Duration.ofMinutes(1)));
    w.expect(checkOut(ana, key(), records.get("a0")), 200);
    w.expect(checkOut(eve, key(), records.get("e0")), 200);
    w.expect(checkOut(zoe, key(), records.get("z0")), 200);
    Instant[] tue = shifts.get("a1");
    clock.set(tue[0].plus(Duration.ofMinutes(5)));
    records.put("a1", record(w, checkIn(ana, key(), slots.get(4))));
    clock.set(tue[1].minus(Duration.ofMinutes(1)));
    w.expect(checkOut(ana, key(), records.get("a1")), 200);
    clock.set(monday.plusDays(6).atTime(LocalTime.NOON).atZone(KINSHASA).toInstant());
    String admin = Hierarchy.bearer(w.tenant(), "sub-tse-" + UUID.randomUUID(), "tenant-admin");
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
    Week t =
        new Week(w, admin, mia, ana, eve, ben, zoe, monday, generationId, sheets, records, shifts);
    approveCycle(t, ana);
    approveCycle(t, eve);
    submitOwn(t, zoe);
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

  private static JsonNode created(Week t, JsonNode preview) throws Exception {
    return t.w().expect(create(t.hr(), key(), creation(t.scope(), preview)), 200);
  }

  private static byte[] bytes(MockHttpServletResponse response) {
    return response.getContentAsByteArray();
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static Set<String> fields(JsonNode node) {
    Set<String> names = new TreeSet<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  /** The CSV's data lines, split into unquoted cells. */
  private static List<List<String>> cells(String csv) {
    List<List<String>> lines = new ArrayList<>();
    String[] raw = csv.split("\r\n");
    for (int i = 1; i < raw.length; i++) {
      List<String> cells = new ArrayList<>();
      for (String cell : raw[i].split(",", -1)) {
        cells.add(cell.substring(1, cell.length() - 1));
      }
      lines.add(cells);
    }
    return lines;
  }

  private String approvalOf(String timesheetId) {
    return jdbc.queryForObject(
        "SELECT current_action_id::text FROM people.timesheet WHERE id = ?::uuid",
        String.class,
        timesheetId);
  }

  private List<Map<String, Object>> exportRows(ScheduleWorld w) {
    return jdbc.queryForList(
        "SELECT * FROM people.timesheet_export_row WHERE tenant_id = ? ORDER BY export_id, line",
        w.tenant());
  }

  // ------------------------------------------------------------------------------------------
  // 1, 2, 5, 6, 8, 9: eligibility, creation, idempotency, reuse, CSV and manifest
  // ------------------------------------------------------------------------------------------

  @Test
  void onlyCurrentApprovalsAreExportedAsADeterministicVerifiablePackage() throws Exception {
    Week t = week();
    ScheduleWorld w = t.w();
    Instant now = clock.instant();

    // 1, 2: Ana and Eve are approved; Ben's draft and Zoe's submission are excluded by state.
    JsonNode preview = w.expect(preview(t.admin(), t.scope()), 200);
    assertThat(preview.get("eligible").toString())
        .isEqualTo("{\"timesheets\":2,\"employees\":2,\"rows\":3,\"workedMinutes\":1422}");
    assertThat(preview.get("excluded").toString())
        .isEqualTo("{\"draft\":1,\"submitted\":1,\"reopened\":0}");
    assertThat(preview.get("generationVersion").asInt()).isEqualTo(1);
    assertThat(preview.get("generationId").asText()).isEqualTo(t.generationId());
    assertThat(preview.get("existingExportId").isNull()).isTrue();
    Instant expires = Instant.parse(preview.get("expiresAt").asText());
    assertThat(expires).isAfter(now.plus(Duration.ofMinutes(14)));
    assertThat(expires).isBefore(now.plus(Duration.ofMinutes(31)));
    assertThat(preview.get("scope").get("timezone").asText()).isEqualTo("Africa/Kinshasa");
    assertThat(preview.get("scope").get("weekStart").asText()).isEqualTo(t.monday().toString());
    // No name or employee number in the preview.
    assertThat(preview.toString())
        .doesNotContain("Kalala")
        .doesNotContain(t.ana().number())
        .doesNotContain(t.ana().employee().toString());

    // 5: creation, an identical replay and a changed payload under the same key.
    String key = key();
    Map<String, Object> body = creation(t.scope(), preview);
    JsonNode result = w.expect(create(t.admin(), key, body), 200);
    assertThat(result.get("outcome").asText()).isEqualTo("CREATED");
    JsonNode export = result.get("export");
    String exportId = export.get("exportId").asText();
    assertThat(export.get("status").asText()).isEqualTo("READY");
    assertThat(export.get("schemaVersion").asInt()).isEqualTo(1);
    assertThat(export.get("counts")).isEqualTo(preview.get("eligible"));
    MockHttpServletResponse replayed = w.call(create(t.admin(), key, body));
    assertThat(replayed.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(JSON.readTree(replayed.getContentAsString())).isEqualTo(result);
    w.problem(
        create(t.admin(), key, creation(t.scope(), "0".repeat(64))), 409, "IDEMPOTENCY_KEY_REUSED");

    // 6: identical evidence under another key returns the existing export.
    JsonNode again = w.expect(preview(t.admin(), t.scope()), 200);
    assertThat(again.get("existingExportId").asText()).isEqualTo(exportId);
    JsonNode existing = created(t, again);
    assertThat(existing.get("outcome").asText()).isEqualTo("EXISTING");
    assertThat(existing.get("export")).isEqualTo(export);
    assertThat(
            w.count("SELECT count(*) FROM people.timesheet_export WHERE tenant_id = ?", w.tenant()))
        .isEqualTo(1);

    // 8: the CSV: headers, columns, order, values and digest.
    MockHttpServletResponse download = w.call(csv(t.admin(), exportId));
    assertThat(download.getStatus()).isEqualTo(200);
    assertThat(download.getContentType()).isEqualTo("text/csv;charset=UTF-8");
    assertThat(download.getHeader("Cache-Control")).isEqualTo("private, no-store");
    assertThat(download.getHeader("Content-Disposition"))
        .isEqualTo(
            "attachment; filename=\"divalhr-time-export-" + t.monday() + "-" + exportId + ".csv\"");
    byte[] first = bytes(download);
    assertThat(first[0]).isEqualTo((byte) '"'); // no byte-order mark
    String text = new String(first, StandardCharsets.UTF_8);
    assertThat(text).startsWith(HEADER + "\r\n").endsWith("\r\n");
    assertThat(sha256(first)).isEqualTo(export.get("csvSha256").asText());
    List<List<String>> lines = cells(text);
    assertThat(lines).hasSize(3);
    List<String> employeeNumbers = new ArrayList<>();
    for (List<String> line : lines) {
      assertThat(line).hasSize(18);
      assertThat(line.get(0)).isEqualTo("1");
      assertThat(line.get(1)).isEqualTo(exportId);
      assertThat(line.get(2)).isEqualTo(w.org.legalEntityId().toString());
      assertThat(line.get(3)).isEqualTo(w.site().toString());
      assertThat(line.get(4)).isEqualTo("Africa/Kinshasa");
      assertThat(line.get(5)).isEqualTo(t.monday().toString());
      assertThat(line.get(6)).isEqualTo(t.monday().plusDays(7).toString());
      assertThat(line.get(14)).isEqualTo("1");
      assertThat(line.get(16)).isEqualTo("1");
      assertThat(line.get(17)).isEqualTo("474");
      employeeNumbers.add(line.get(8));
    }
    assertThat(employeeNumbers).isSorted();
    Map<String, String> byEmployee = new LinkedHashMap<>();
    lines.forEach(l -> byEmployee.put(l.get(7), l.get(15)));
    assertThat(byEmployee)
        .containsEntry(t.ana().employee().toString(), approvalOf(t.sheet(t.ana())))
        .containsEntry(t.eve().employee().toString(), approvalOf(t.sheet(t.eve())))
        .doesNotContainKey(t.ben().employee().toString())
        .doesNotContainKey(t.zoe().employee().toString());
    assertThat(lines.stream().filter(l -> l.get(7).equals(t.ana().employee().toString())))
        .extracting(l -> l.get(11))
        .containsExactly(t.monday().toString(), t.monday().plusDays(1).toString());
    assertThat(text)
        .doesNotContain("Kalala")
        .doesNotContain("Ana")
        .doesNotContain("@")
        .doesNotContain(t.ana().subject())
        .doesNotContain("T08:");
    // 9: every download has the same bytes.
    assertThat(bytes(w.call(csv(t.admin(), exportId)))).isEqualTo(first);

    // 9: the manifest.
    MockHttpServletResponse manifest = w.call(manifest(t.admin(), exportId));
    assertThat(manifest.getStatus()).isEqualTo(200);
    assertThat(manifest.getContentType()).isEqualTo("application/json");
    assertThat(manifest.getHeader("Cache-Control")).isEqualTo("private, no-store");
    assertThat(manifest.getHeader("Content-Disposition"))
        .isEqualTo(
            "attachment; filename=\"divalhr-time-export-"
                + t.monday()
                + "-"
                + exportId
                + ".manifest.json\"");
    byte[] manifestBytes = bytes(manifest);
    JsonNode m = JSON.readTree(manifestBytes);
    assertThat(fields(m))
        .containsExactlyInAnyOrder(
            "schemaVersion",
            "exportId",
            "status",
            "format",
            "columns",
            "scope",
            "generationId",
            "generationVersion",
            "counts",
            "totalWorkedMinutes",
            "csvSha256",
            "createdAt");
    assertThat(m.get("status").asText()).isEqualTo("READY");
    assertThat(m.get("csvSha256").asText()).isEqualTo(sha256(first));
    assertThat(m.get("totalWorkedMinutes").asInt()).isEqualTo(1422);
    assertThat(m.get("counts").toString())
        .isEqualTo("{\"timesheets\":2,\"employees\":2,\"rows\":3}");
    assertThat(m.get("scope").get("periodStartAt").asText())
        .isEqualTo(t.monday().atStartOfDay(KINSHASA).toInstant().toString());
    assertThat(m.get("columns").size()).isEqualTo(18);
    assertThat(new String(manifestBytes, StandardCharsets.UTF_8))
        .doesNotContain("sub-tse-")
        .doesNotContain("Kalala");
    assertThat(bytes(w.call(manifest(t.admin(), exportId)))).isEqualTo(manifestBytes);

    // The detail is the manifest's facts.
    JsonNode detail = w.expect(export(t.admin(), exportId), 200);
    assertThat(detail).isEqualTo(export);
  }

  // ------------------------------------------------------------------------------------------
  // 2, 4, 7: reopening, refresh, reapproval, returned cycles and staleness
  // ------------------------------------------------------------------------------------------

  @Test
  void reopenedAndSupersededApprovalsAreNeverExportedAndChangedEvidenceAllowsANewExport()
      throws Exception {
    Week t = week();
    ScheduleWorld w = t.w();
    String ana = t.sheet(t.ana());
    String firstApproval = approvalOf(ana);
    JsonNode firstPreview = w.expect(preview(t.hr(), t.scope()), 200);
    JsonNode first = created(t, firstPreview).get("export");
    String firstId = first.get("exportId").asText();
    List<Map<String, Object>> firstRows = exportRows(w);
    byte[] firstCsv = bytes(w.call(csv(t.hr(), firstId)));

    // 4: a preview goes stale when Mia approves Zoe's submission.
    JsonNode beforeApproval = w.expect(preview(t.hr(), t.scope()), 200);
    String zoe = t.sheet(t.zoe());
    JsonNode zoeReviewed = w.expect(approval(t.mia().bearer(), zoe), 200);
    w.expect(approve(t.mia().bearer(), key(), zoe, decision(zoeReviewed)), 200);
    w.problem(
        create(t.hr(), key(), creation(t.scope(), beforeApproval)),
        409,
        "TIMESHEET_EXPORT_PREVIEW_STALE");

    // 2, 4: a correction reopens Ana's approved week; the earlier preview is stale; her
    // superseded approval is excluded from every later export.
    JsonNode beforeReopening = w.expect(preview(t.hr(), t.scope()), 200);
    Instant[] mon = t.shifts().get("a0");
    Map<String, Object> fix = values(mon[0].plus(Duration.ofMinutes(20)), mon[1]);
    String fixer = t.hr();
    JsonNode reviewed = w.expect(previewRecord(fixer, t.records().get("a0"), fix), 200);
    w.expect(commitRecord(fixer, key(), t.records().get("a0"), commit(fix, reviewed)), 201);
    w.problem(
        create(t.hr(), key(), creation(t.scope(), beforeReopening)),
        409,
        "TIMESHEET_EXPORT_PREVIEW_STALE");
    JsonNode reopened = w.expect(preview(t.hr(), t.scope()), 200);
    assertThat(reopened.get("eligible").get("timesheets").asInt()).isEqualTo(2); // Eve and Zoe
    assertThat(reopened.get("excluded").toString())
        .isEqualTo("{\"draft\":1,\"submitted\":0,\"reopened\":1}");
    JsonNode second = created(t, reopened);
    assertThat(second.get("outcome").asText()).isEqualTo("CREATED");
    String secondId = second.get("export").get("exportId").asText();
    String secondCsv = new String(bytes(w.call(csv(t.hr(), secondId))), StandardCharsets.UTF_8);
    assertThat(secondCsv).doesNotContain(ana).doesNotContain(firstApproval);

    // 7, T70E-3: the first export is never rewritten by the reopening.
    assertThat(
            exportRows(w).stream()
                .filter(r -> r.get("export_id").toString().equals(firstId))
                .toList())
        .isEqualTo(firstRows);
    assertThat(bytes(w.call(csv(t.hr(), firstId)))).isEqualTo(firstCsv);
    assertThat(w.expect(export(t.hr(), firstId), 200)).isEqualTo(first);

    // 4: the refresh makes a preview stale too; Ana's next revision is returned once (a returned
    // cycle is never exported), then approved in cycle 3.
    JsonNode beforeRefresh = w.expect(preview(t.hr(), t.scope()), 200);
    String refresher = t.hr();
    JsonNode refreshPreview =
        w.expect(previewRefresh(refresher, t.generationId(), t.period()), 200);
    w.expect(
        refresh(refresher, key(), t.generationId(), refreshBody(t.period(), refreshPreview)), 200);
    w.problem(
        create(t.hr(), key(), creation(t.scope(), beforeRefresh)),
        409,
        "TIMESHEET_EXPORT_PREVIEW_STALE");
    submitOwn(t, t.ana());
    JsonNode cycle2 = w.expect(approval(t.mia().bearer(), ana), 200);
    w.expect(
        giveBack(
            t.mia().bearer(),
            key(),
            ana,
            TimesheetApprovalCalls.giveBack(cycle2, "Le lundi doit être vérifié encore une fois.")),
        200);
    JsonNode returned = w.expect(preview(t.hr(), t.scope()), 200);
    assertThat(returned.get("excluded").get("draft").asInt()).isEqualTo(2); // Ana and Ben
    assertThat(returned.get("existingExportId").asText()).isEqualTo(secondId);
    JsonNode same = created(t, returned);
    assertThat(same.get("outcome").asText()).isEqualTo("EXISTING");
    JsonNode refreshAgain = w.expect(previewRefresh(refresher, t.generationId(), t.period()), 200);
    w.expect(
        refresh(refresher, key(), t.generationId(), refreshBody(t.period(), refreshAgain)), 200);
    approveCycle(t, t.ana());
    String thirdApproval = approvalOf(ana);
    JsonNode third = w.expect(preview(t.hr(), t.scope()), 200);
    assertThat(third.get("eligible").get("timesheets").asInt()).isEqualTo(3);
    assertThat(third.get("generationVersion").asInt()).isEqualTo(3);
    JsonNode thirdExport = created(t, third);
    assertThat(thirdExport.get("outcome").asText()).isEqualTo("CREATED");
    List<List<String>> lines =
        cells(
            new String(
                bytes(w.call(csv(t.hr(), thirdExport.get("export").get("exportId").asText()))),
                StandardCharsets.UTF_8));
    List<List<String>> anaLines = lines.stream().filter(l -> l.get(13).equals(ana)).toList();
    assertThat(anaLines).hasSize(2);
    for (List<String> line : anaLines) {
      assertThat(line.get(15)).isEqualTo(thirdApproval).isNotEqualTo(firstApproval);
      assertThat(line.get(16)).isEqualTo("3"); // cycle 3
      // Revision 2: the returned draft had no source change, so the second refresh kept it.
      assertThat(line.get(14)).isEqualTo("2");
    }
    assertThat(anaLines.get(0).get(17)).isEqualTo("460");
    assertThat(
            w.count("SELECT count(*) FROM people.timesheet_export WHERE tenant_id = ?", w.tenant()))
        .isEqualTo(3);
  }

  @Test
  void anExpiredPreviewIsStaleAndAnEmptyWeekCannotBeExported() throws Exception {
    Week t = week();
    ScheduleWorld w = t.w();
    Instant now = clock.instant();
    JsonNode preview = w.expect(preview(t.admin(), t.scope()), 200);
    Instant expires = Instant.parse(preview.get("expiresAt").asText());
    // Valid until its expiry, stale from it on.
    clock.set(expires);
    w.problem(
        create(t.admin(), key(), creation(t.scope(), preview)),
        409,
        "TIMESHEET_EXPORT_PREVIEW_STALE");
    clock.set(expires.minusMillis(1));
    assertThat(created(t, preview).get("outcome").asText()).isEqualTo("CREATED");
    clock.set(now);

    // An empty week: the preview shows only exclusions, the creation is refused.
    LocalDate next = t.monday().plusDays(7);
    String slot =
        slotIds(
                w,
                w.published(
                    proposal(
                        w.site(),
                        next,
                        next,
                        List.of(shift("b", next, 8, 16, t.ben().employee())))))
            .get(0);
    Instant[] at = instants(jdbc, slot);
    clock.set(at[0].plus(Duration.ofMinutes(2)));
    record(w, checkIn(t.ben(), key(), slot));
    clock.set(next.plusDays(6).atTime(LocalTime.NOON).atZone(KINSHASA).toInstant());
    w.expect(generate(t.admin(), key(), confirmed(TimesheetCalls.period(w.site(), next))), 200);
    Map<String, Object> empty = scope(w.org.legalEntityId(), w.site(), next);
    JsonNode nothing = w.expect(preview(t.admin(), empty), 200);
    assertThat(nothing.get("eligible").toString())
        .isEqualTo("{\"timesheets\":0,\"employees\":0,\"rows\":0,\"workedMinutes\":0}");
    assertThat(nothing.get("excluded").get("draft").asInt()).isEqualTo(1);
    w.problem(create(t.admin(), key(), creation(empty, nothing)), 409, "TIMESHEET_EXPORT_EMPTY");
    // A week that is not generated is the safe 404.
    w.problem(
        preview(t.admin(), scope(w.org.legalEntityId(), w.site(), next.plusDays(7))),
        404,
        "TIMESHEET_TARGET_NOT_FOUND");
  }

  // ------------------------------------------------------------------------------------------
  // T70E-6: history
  // ------------------------------------------------------------------------------------------

  @Test
  void historyIsNewestFirstWithSignedKeysetPagesAndFilters() throws Exception {
    Week t = week();
    ScheduleWorld w = t.w();
    String first =
        created(t, w.expect(preview(t.admin(), t.scope()), 200))
            .get("export")
            .get("exportId")
            .asText();
    String zoe = t.sheet(t.zoe());
    JsonNode reviewed = w.expect(approval(t.mia().bearer(), zoe), 200);
    w.expect(approve(t.mia().bearer(), key(), zoe, decision(reviewed)), 200);
    clock.set(clock.instant().plusSeconds(5));
    String second =
        created(t, w.expect(preview(t.admin(), t.scope()), 200))
            .get("export")
            .get("exportId")
            .asText();
    JsonNode all = w.expect(list(t.admin(), null), 200);
    assertThat(all.get("items").findValuesAsText("exportId")).containsExactly(second, first);
    assertThat(all.get("nextCursor").isNull()).isTrue();
    JsonNode page = w.expect(list(t.admin(), "limit=1"), 200);
    assertThat(page.get("items").findValuesAsText("exportId")).containsExactly(second);
    String cursor = page.get("nextCursor").asText();
    String encoded = java.net.URLEncoder.encode(cursor, StandardCharsets.UTF_8);
    JsonNode next = w.expect(list(t.admin(), "limit=1&cursor=" + encoded), 200);
    assertThat(next.get("items").findValuesAsText("exportId")).containsExactly(first);
    assertThat(next.get("nextCursor").isNull()).isTrue();
    // The cursor is bound to its filters and page size.
    w.problem(list(t.admin(), "limit=2&cursor=" + encoded), 400, "CURSOR_INVALID");
    String filters = "siteId=" + w.site() + "&weekStart=" + t.monday();
    assertThat(w.expect(list(t.admin(), filters), 200).get("items").size()).isEqualTo(2);
    assertThat(w.expect(list(t.admin(), "siteId=" + w.otherSite), 200).get("items").size())
        .isZero();
    assertThat(
            w.expect(list(t.admin(), "weekStart=" + t.monday().plusDays(7)), 200)
                .get("items")
                .size())
        .isZero();
    w.problem(list(t.admin(), "siteId=nope"), 400, "VALIDATION_FAILED");
    w.problem(list(t.admin(), "weekStart=" + t.monday().plusDays(1)), 400, "VALIDATION_FAILED");
    w.problem(list(t.admin(), "limit=0"), 400, "VALIDATION_FAILED");
    // Never the creator.
    assertThat(all.toString()).doesNotContain("sub-tse-");
  }

  // ------------------------------------------------------------------------------------------
  // 3, T70E-7: authority and isolation
  // ------------------------------------------------------------------------------------------

  @Test
  void onlyTheTenantsAdministratorsReachItsExports() throws Exception {
    Week t = week();
    ScheduleWorld w = t.w();
    String exportId =
        created(t, w.expect(preview(t.admin(), t.scope()), 200))
            .get("export")
            .get("exportId")
            .asText();
    ScheduleWorld other = new ScheduleWorld(mvc, jdbc, lookups);
    String foreign =
        Hierarchy.bearer(other.tenant(), "sub-tse-f-" + UUID.randomUUID(), "tenant-admin");
    // 3: another tenant's administrator gets the same safe 404 everywhere and an empty history.
    w.problem(preview(foreign, t.scope()), 404, "TIMESHEET_TARGET_NOT_FOUND");
    w.problem(export(foreign, exportId), 404, "TIMESHEET_TARGET_NOT_FOUND");
    w.problem(csv(foreign, exportId), 404, "TIMESHEET_TARGET_NOT_FOUND");
    w.problem(manifest(foreign, exportId), 404, "TIMESHEET_TARGET_NOT_FOUND");
    assertThat(w.expect(list(foreign, null), 200).get("items")).isEmpty();
    // A legal entity that does not own the site, a foreign legal entity, malformed and unknown
    // identifiers are the same 404.
    UUID otherEntity =
        Hierarchy.newLegalEntity(mvc, w.tenant(), Hierarchy.code("LX"), "2026-01-01", null);
    w.problem(
        preview(t.admin(), scope(otherEntity, w.site(), t.monday())),
        404,
        "TIMESHEET_TARGET_NOT_FOUND");
    w.problem(
        preview(t.admin(), scope(other.org.legalEntityId(), w.site(), t.monday())),
        404,
        "TIMESHEET_TARGET_NOT_FOUND");
    w.problem(
        preview(t.admin(), scope("nope", w.site(), t.monday())), 404, "TIMESHEET_TARGET_NOT_FOUND");
    w.problem(export(t.admin(), "not-a-uuid"), 404, "TIMESHEET_TARGET_NOT_FOUND");
    w.problem(csv(t.admin(), UUID.randomUUID().toString()), 404, "TIMESHEET_TARGET_NOT_FOUND");
    w.problem(
        preview(t.admin(), scope(w.org.legalEntityId(), w.site(), t.monday().plusDays(1))),
        400,
        "VALIDATION_FAILED");
    // Employees, the manager and a platform administrator have no access.
    String platform =
        Hierarchy.bearer(w.tenant(), "sub-platform-" + UUID.randomUUID(), "platform-admin");
    for (String bearer : List.of(t.ana().bearer(), t.mia().bearer(), platform)) {
      assertThat(w.call(preview(bearer, t.scope())).getStatus()).isEqualTo(403);
      assertThat(w.call(create(bearer, key(), creation(t.scope(), "a".repeat(64)))).getStatus())
          .isEqualTo(403);
      assertThat(w.call(list(bearer, null)).getStatus()).isEqualTo(403);
      assertThat(w.call(export(bearer, exportId)).getStatus()).isEqualTo(403);
      assertThat(w.call(csv(bearer, exportId)).getStatus()).isEqualTo(403);
      assertThat(w.call(manifest(bearer, exportId)).getStatus()).isEqualTo(403);
    }
    // Shape errors: an unknown property, a missing confirmation or digest, no key.
    Map<String, Object> extra = new LinkedHashMap<>(t.scope());
    extra.put("tenantId", other.tenant().toString());
    w.problem(preview(t.admin(), extra), 400, "VALIDATION_FAILED");
    Map<String, Object> unconfirmed = new LinkedHashMap<>(creation(t.scope(), "a".repeat(64)));
    unconfirmed.remove("confirm");
    w.problem(create(t.admin(), key(), unconfirmed), 400, "VALIDATION_FAILED");
    w.problem(
        create(t.admin(), null, creation(t.scope(), "a".repeat(64))), 400, "VALIDATION_FAILED");
    w.problem(create(t.admin(), key(), creation(t.scope(), "ABC")), 400, "VALIDATION_FAILED");
    assertThat(
            w.count("SELECT count(*) FROM people.timesheet_export WHERE tenant_id = ?", w.tenant()))
        .isEqualTo(1);
  }

  // ------------------------------------------------------------------------------------------
  // 12, T70E-10: audit, events, logs and errors
  // ------------------------------------------------------------------------------------------

  @Test
  void auditEventsLogsAndErrorsCarryNoNameNumberSubjectOrRow(CapturedOutput output)
      throws Exception {
    Week t = week();
    ScheduleWorld w = t.w();
    JsonNode preview = w.expect(preview(t.admin(), t.scope()), 200);
    JsonNode export = created(t, preview).get("export");
    String exportId = export.get("exportId").asText();
    w.call(list(t.admin(), null));
    w.call(export(t.admin(), exportId));
    w.call(csv(t.admin(), exportId));
    w.call(manifest(t.admin(), exportId));
    String stale =
        w.call(create(t.admin(), key(), creation(t.scope(), "b".repeat(64)))).getContentAsString();
    assertThat(stale).contains("TIMESHEET_EXPORT_PREVIEW_STALE");

    List<String> actions =
        jdbc.queryForList(
            "SELECT action FROM platform.audit_event WHERE tenant_id = ? AND action LIKE"
                + " 'timesheet-export.%' ORDER BY occurred_at",
            String.class, w.tenant());
    assertThat(actions)
        .containsExactlyInAnyOrder(
            "timesheet-export.preview",
            "timesheet-export.create",
            "timesheet-export.list",
            "timesheet-export.read",
            "timesheet-export.download",
            "timesheet-export.download");
    JsonNode created =
        JSON.readTree(
            jdbc.queryForObject(
                "SELECT metadata::text FROM platform.audit_event WHERE tenant_id = ? AND action"
                    + " = 'timesheet-export.create'",
                String.class,
                w.tenant()));
    assertThat(fields(created))
        .containsExactly(
            "employeeCount",
            "exportSchemaVersion",
            "generationVersion",
            "rowCount",
            "schemaVersion",
            "timesheetCount",
            "week",
            "workedMinuteBand");
    assertThat(created.get("workedMinuteBand").asText()).isEqualTo("1-2400");
    List<String> downloads =
        jdbc.queryForList(
            "SELECT metadata ->> 'format' FROM platform.audit_event WHERE tenant_id = ? AND action"
                + " = 'timesheet-export.download' ORDER BY occurred_at",
            String.class,
            w.tenant());
    assertThat(downloads).containsExactlyInAnyOrder("csv", "manifest");
    String audit =
        String.join(
            "\n",
            jdbc.queryForList(
                "SELECT metadata::text FROM platform.audit_event WHERE tenant_id = ? AND action"
                    + " LIKE 'timesheet-export.%'",
                String.class, w.tenant()));
    String event =
        jdbc.queryForObject(
            "SELECT envelope::text FROM platform.outbox_event WHERE envelope ->> 'tenantId' = ?"
                + " AND envelope ->> 'eventType' = 'people.timesheet-export.created.v1'",
            String.class,
            w.tenant().toString());
    JsonNode data = JSON.readTree(event).get("data");
    assertThat(fields(data))
        .containsExactly(
            "csvSha256",
            "employeeCount",
            "exportId",
            "exportSchemaVersion",
            "generationId",
            "generationVersion",
            "legalEntityId",
            "occurredAt",
            "periodEndAt",
            "periodStartAt",
            "rowCount",
            "siteId",
            "status",
            "timesheetCount",
            "weekStart",
            "workedMinuteBand");
    assertThat(data.get("csvSha256").asText()).isEqualTo(export.get("csvSha256").asText());
    assertThat(audit).doesNotContain(exportId).doesNotContain(t.sheet(t.ana()));
    for (Person person : List.of(t.ana(), t.eve(), t.mia())) {
      for (String text : List.of(audit, event, stale)) {
        assertThat(text)
            .doesNotContain(person.number())
            .doesNotContain(person.subject())
            .doesNotContain(person.employee().toString())
            .doesNotContain("Kalala")
            .doesNotContain("Ngoy");
      }
    }
    assertThat(event).doesNotContain(t.sheet(t.ana())).doesNotContain("sub-tse-");
    String application =
        String.join(
            "\n",
            output
                .getAll()
                .lines()
                .filter(line -> line.contains("\"logger_name\":\"divalhr.timesheet-export"))
                .toList());
    assertThat(application)
        .contains("timesheet_export_created")
        .contains("timesheet_export_downloaded");
    assertThat(application)
        .doesNotContain(exportId)
        .doesNotContain(t.sheet(t.ana()))
        .doesNotContain(t.ana().number())
        .doesNotContain(export.get("csvSha256").asText())
        .doesNotContain("Kalala");
  }
}
