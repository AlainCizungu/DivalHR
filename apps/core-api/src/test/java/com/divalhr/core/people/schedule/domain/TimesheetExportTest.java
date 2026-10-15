package com.divalhr.core.people.schedule.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.people.schedule.domain.TimesheetExport.Header;
import com.divalhr.core.people.schedule.domain.TimesheetExport.Line;
import com.divalhr.core.people.schedule.domain.TimesheetExport.Scope;
import com.divalhr.core.people.schedule.domain.TimesheetExport.Totals;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * MVP-070E (T70E-4, T70E-5, T70E-11 item 8): the CSV's fixed header, quoting, formula escaping,
 * deterministic order and bytes; the manifest's canonical shape; the evidence digest.
 */
class TimesheetExportTest {

  private static final UUID EXPORT = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID ENTITY = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID SITE = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID GENERATION = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);
  private static final Scope SCOPE =
      new Scope(ENTITY, Timesheet.Period.of(SITE, "Africa/Kinshasa", MONDAY), GENERATION, 2);

  private static Line line(String number, LocalDate day, String shift, int minutes) {
    return new Line(
        UUID.fromString("55555555-5555-4555-8555-555555555555"),
        2,
        UUID.fromString("66666666-6666-4666-8666-666666666666"),
        3,
        UUID.fromString("77777777-7777-4777-8777-777777777777"),
        number,
        UUID.fromString("88888888-8888-4888-8888-888888888888"),
        null,
        day,
        UUID.fromString(shift),
        minutes);
  }

  @Test
  void theCsvHasTheFixedHeaderQuotedCellsAndCrlfLines() {
    byte[] csv =
        TimesheetExport.csv(
            EXPORT,
            SCOPE,
            List.of(line("E-1", MONDAY, "99999999-9999-4999-8999-999999999999", 470)));
    String text = new String(csv, StandardCharsets.UTF_8);
    assertThat(text)
        .isEqualTo(
            "\"schema_version\",\"export_id\",\"legal_entity_id\",\"site_id\",\"site_timezone\","
                + "\"week_start\",\"week_end\",\"employee_id\",\"employee_number\","
                + "\"employment_id\",\"cost_center_id\",\"work_date\",\"shift_id\","
                + "\"timesheet_id\",\"timesheet_revision\",\"approval_action_id\","
                + "\"approval_cycle\",\"worked_minutes\"\r\n"
                + "\"1\",\"11111111-1111-4111-8111-111111111111\","
                + "\"22222222-2222-4222-8222-222222222222\","
                + "\"33333333-3333-4333-8333-333333333333\",\"Africa/Kinshasa\",\"2026-11-02\","
                + "\"2026-11-09\",\"77777777-7777-4777-8777-777777777777\",\"E-1\","
                + "\"88888888-8888-4888-8888-888888888888\",\"\",\"2026-11-02\","
                + "\"99999999-9999-4999-8999-999999999999\","
                + "\"55555555-5555-4555-8555-555555555555\",\"2\","
                + "\"66666666-6666-4666-8666-666666666666\",\"3\",\"470\"\r\n");
    // No byte-order mark; the same input always gives the same bytes and digest.
    assertThat(csv[0]).isEqualTo((byte) '"');
    byte[] again =
        TimesheetExport.csv(
            EXPORT,
            SCOPE,
            List.of(line("E-1", MONDAY, "99999999-9999-4999-8999-999999999999", 470)));
    assertThat(again).isEqualTo(csv);
    assertThat(TimesheetExport.sha256(again)).isEqualTo(TimesheetExport.sha256(csv)).hasSize(64);
  }

  @Test
  void aTextCellThatCouldRunAsAFormulaIsNeutralized() {
    for (String hostile : List.of("=SUM(A1)", "+1", "-2", "@cmd", "a\"b")) {
      String text =
          new String(
              TimesheetExport.csv(
                  EXPORT,
                  SCOPE,
                  List.of(line(hostile, MONDAY, "99999999-9999-4999-8999-999999999999", 1))),
              StandardCharsets.UTF_8);
      String expected =
          hostile.equals("a\"b") ? "\"a\"\"b\"" : "\"'" + hostile.replace("\"", "\"\"") + "\"";
      assertThat(text).contains("," + expected + ",");
    }
  }

  @Test
  void linesAreOrderedByEmployeeNumberWorkDateAndShiftIdAsTheDatabaseOrdersThem() {
    // A shift ID whose first hex digit is 8 to f sorts after 0 to 7 as text and as the database's
    // bytes, but before them as Java's signed UUID comparison would.
    Line high = line("E1", MONDAY, "f0000000-0000-4000-8000-000000000000", 1);
    Line low = line("E1", MONDAY, "00000000-0000-4000-8000-000000000000", 1);
    Line tuesday = line("E1", MONDAY.plusDays(1), "00000000-0000-4000-8000-000000000001", 1);
    Line ten = line("E10", MONDAY, "00000000-0000-4000-8000-000000000002", 1);
    Line two = line("E2", MONDAY, "00000000-0000-4000-8000-000000000003", 1);
    assertThat(UUID.fromString("f0000000-0000-4000-8000-000000000000"))
        .isLessThan(UUID.fromString("00000000-0000-4000-8000-000000000000"));
    assertThat(TimesheetExport.ordered(List.of(two, tuesday, high, ten, low)))
        .containsExactly(low, high, tuesday, ten, two);
  }

  @Test
  void totalsTheManifestAndTheEvidenceDigestAreExactAndCanonical() {
    List<Line> lines =
        TimesheetExport.ordered(
            List.of(
                line("E1", MONDAY, "00000000-0000-4000-8000-000000000001", 470),
                line("E1", MONDAY.plusDays(1), "00000000-0000-4000-8000-000000000002", 30)));
    Totals totals = Totals.of(lines);
    assertThat(totals).isEqualTo(new Totals(1, 1, 2, 500));
    String evidence = TimesheetExport.evidenceDigest(SCOPE, lines);
    assertThat(TimesheetExport.evidenceDigest(SCOPE, lines)).isEqualTo(evidence);
    // Any changed row changes the evidence; the generation version alone does not.
    assertThat(TimesheetExport.evidenceDigest(SCOPE, lines.subList(0, 1))).isNotEqualTo(evidence);
    Scope refreshed = new Scope(ENTITY, SCOPE.period(), GENERATION, 3);
    assertThat(TimesheetExport.evidenceDigest(refreshed, lines)).isEqualTo(evidence);
    String csv = TimesheetExport.sha256(TimesheetExport.csv(EXPORT, SCOPE, lines));
    Header header =
        new Header(EXPORT, SCOPE, totals, evidence, csv, Instant.parse("2026-11-09T10:00:00Z"));
    String manifest = new String(TimesheetExport.manifest(header), StandardCharsets.UTF_8);
    assertThat(manifest)
        .startsWith("{\"schemaVersion\":1,\"exportId\":\"" + EXPORT + "\",\"status\":\"READY\"")
        .contains(
            "\"scope\":{\"legalEntityId\":\""
                + ENTITY
                + "\",\"siteId\":\""
                + SITE
                + "\",\"siteTimezone\":\"Africa/Kinshasa\",\"weekStart\":\"2026-11-02\","
                + "\"weekEnd\":\"2026-11-09\",\"lastDay\":\"2026-11-08\","
                + "\"periodStartAt\":\"2026-11-01T23:00:00Z\","
                + "\"periodEndAt\":\"2026-11-08T23:00:00Z\"}")
        .contains("\"counts\":{\"timesheets\":1,\"employees\":1,\"rows\":2}")
        .contains("\"totalWorkedMinutes\":500")
        .contains("\"csvSha256\":\"" + csv + "\"")
        .endsWith("\"createdAt\":\"2026-11-09T10:00:00Z\"}\n");
    assertThat(TimesheetExport.manifest(header)).isEqualTo(TimesheetExport.manifest(header));
    assertThat(TimesheetExport.minuteBand(0)).isEqualTo("0");
    assertThat(TimesheetExport.minuteBand(500)).isEqualTo("1-2400");
    assertThat(TimesheetExport.minuteBand(300_000)).isEqualTo("240001+");
  }

  @Test
  void aLineIsAnApprovedReadyRow() {
    assertThatThrownBy(() -> line("E1", MONDAY, "00000000-0000-4000-8000-000000000001", -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(line("E1", MONDAY, "00000000-0000-4000-8000-000000000001", 0).toString())
        .doesNotContain("E1");
  }
}
