package com.divalhr.core.people.schedule.domain;

import com.divalhr.core.platform.csv.CsvCells;
import com.divalhr.core.platform.idempotency.Fingerprints;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * MVP-070E approved-time export snapshot and format (Issue #134, T70E-3 to T70E-5; ADR 0021).
 *
 * <p>An export holds one line per READY row of every eligible timesheet: a timesheet whose current
 * state is APPROVED and whose current action is that approval. Lines are ordered by employee
 * number, local work date and shift ID (the shift ID compared as its lowercase canonical text,
 * which is the database's byte order), numbered 1..n. The CSV is UTF-8 without a byte-order mark,
 * comma-delimited, every cell quoted (RFC 4180) and neutralized against formulas ({@link
 * CsvCells}), with CRLF line ends and the fixed machine-facing English header {@link #COLUMNS}. The
 * manifest is canonical JSON with a fixed key order. Both are pure functions of the stored export,
 * so every download has the same bytes and the same SHA-256.
 */
public final class TimesheetExport {

  /** The export schema version (CSV columns and manifest shape). */
  public static final int SCHEMA_VERSION = 1;

  /** The fixed CSV header, never translated. */
  public static final List<String> COLUMNS =
      List.of(
          "schema_version",
          "export_id",
          "legal_entity_id",
          "site_id",
          "site_timezone",
          "week_start",
          "week_end",
          "employee_id",
          "employee_number",
          "employment_id",
          "cost_center_id",
          "work_date",
          "shift_id",
          "timesheet_id",
          "timesheet_revision",
          "approval_action_id",
          "approval_cycle",
          "worked_minutes");

  /** The deterministic line order: employee number, local work date, shift ID. */
  public static final Comparator<Line> ORDER =
      Comparator.comparing(Line::employeeNumber)
          .thenComparing(Line::workDate)
          .thenComparing(line -> line.shiftId().toString());

  private TimesheetExport() {}

  /**
   * The exported scope: the tenant's legal entity, its site and the generated week as generated.
   *
   * @param legalEntityId legal entity
   * @param period the site, its zone and the exact week boundaries
   * @param generationId the generation
   * @param generationVersion its current version
   */
  public record Scope(
      UUID legalEntityId, Timesheet.Period period, UUID generationId, int generationVersion) {

    /** Requires every component. */
    public Scope {
      Objects.requireNonNull(legalEntityId, "legalEntityId");
      Objects.requireNonNull(period, "period");
      Objects.requireNonNull(generationId, "generationId");
    }
  }

  /**
   * One exported line: a READY row of an approved timesheet's current revision.
   *
   * @param timesheetId timesheet
   * @param revision its current revision
   * @param approvalActionId its current APPROVED action
   * @param approvalCycle that approval's cycle
   * @param employeeId employee
   * @param employeeNumber the employee number (allow-listed: never a formula prefix)
   * @param employmentId the row's employment
   * @param costCenterId the row's cost center, or {@code null}
   * @param workDate the shift's site-local start date
   * @param shiftId the shift (slot)
   * @param workedMinutes worked minutes
   */
  public record Line(
      UUID timesheetId,
      int revision,
      UUID approvalActionId,
      int approvalCycle,
      UUID employeeId,
      String employeeNumber,
      UUID employmentId,
      UUID costCenterId,
      LocalDate workDate,
      UUID shiftId,
      int workedMinutes) {

    /** Requires every identity and non-negative minutes. */
    public Line {
      Objects.requireNonNull(timesheetId, "timesheetId");
      Objects.requireNonNull(approvalActionId, "approvalActionId");
      Objects.requireNonNull(employeeId, "employeeId");
      Objects.requireNonNull(employeeNumber, "employeeNumber");
      Objects.requireNonNull(employmentId, "employmentId");
      Objects.requireNonNull(workDate, "workDate");
      Objects.requireNonNull(shiftId, "shiftId");
      if (workedMinutes < 0 || revision < 1 || approvalCycle < 1) {
        throw new IllegalArgumentException("not an approved line");
      }
    }

    /** Identifiers only. */
    @Override
    public String toString() {
      return "TimesheetExport.Line[timesheet=" + timesheetId + ", shift=" + shiftId + "]";
    }
  }

  /**
   * Counts and the total of a set of lines.
   *
   * @param timesheets distinct timesheets
   * @param employees distinct employees
   * @param rows lines
   * @param workedMinutes total worked minutes
   */
  public record Totals(int timesheets, int employees, int rows, int workedMinutes) {

    /**
     * The totals of lines.
     *
     * @param lines lines
     * @return the totals
     */
    public static Totals of(List<Line> lines) {
      int minutes = 0;
      for (Line line : lines) {
        minutes = Math.addExact(minutes, line.workedMinutes());
      }
      return new Totals(
          (int) lines.stream().map(Line::timesheetId).distinct().count(),
          (int) lines.stream().map(Line::employeeId).distinct().count(),
          lines.size(),
          minutes);
    }
  }

  /**
   * A stored export header.
   *
   * @param id export
   * @param scope scope and generation version
   * @param totals counts and total
   * @param evidenceDigest SHA-256 of the eligible evidence
   * @param csvSha256 SHA-256 of the CSV bytes
   * @param createdAt creation instant
   */
  public record Header(
      UUID id,
      Scope scope,
      Totals totals,
      String evidenceDigest,
      String csvSha256,
      Instant createdAt) {

    /** Identifiers only. */
    @Override
    public String toString() {
      return "TimesheetExport.Header[id=" + id + "]";
    }
  }

  /**
   * The lines in the deterministic CSV order.
   *
   * @param lines lines in any order
   * @return a new ordered list
   */
  public static List<Line> ordered(List<Line> lines) {
    List<Line> sorted = new ArrayList<>(lines);
    sorted.sort(ORDER);
    return List.copyOf(sorted);
  }

  /**
   * The SHA-256 of the eligible evidence: the scope and every line with its timesheet, revision,
   * approval and cycle. Two requests with identical evidence produce the same digest; any changed
   * approval, revision, row or employee number changes it. A refresh that leaves every approval as
   * it is (only the generation version moved) does not: the existing export is still exactly the
   * approved time.
   *
   * @param scope scope
   * @param lines ordered lines
   * @return lowercase hex SHA-256
   */
  public static String evidenceDigest(Scope scope, List<Line> lines) {
    StringBuilder text =
        new StringBuilder("DIVALHR-TIMESHEET-EXPORT-EVIDENCE\nschema=")
            .append(SCHEMA_VERSION)
            .append("\nlegalEntity=")
            .append(scope.legalEntityId())
            .append("\nsite=")
            .append(scope.period().siteId())
            .append("\nweek=")
            .append(scope.period().weekStart())
            .append("\nzone=")
            .append(scope.period().timezone())
            .append("\ngeneration=")
            .append(scope.generationId())
            .append('\n');
    for (Line line : lines) {
      text.append(line.timesheetId())
          .append('|')
          .append(line.revision())
          .append('|')
          .append(line.approvalActionId())
          .append('|')
          .append(line.approvalCycle())
          .append('|')
          .append(line.employeeId())
          .append('|')
          .append(line.employeeNumber())
          .append('|')
          .append(line.employmentId())
          .append('|')
          .append(line.costCenterId() == null ? "-" : line.costCenterId())
          .append('|')
          .append(line.workDate())
          .append('|')
          .append(line.shiftId())
          .append('|')
          .append(line.workedMinutes())
          .append('\n');
    }
    return Fingerprints.sha256(text.toString());
  }

  /**
   * The CSV bytes of an export.
   *
   * @param exportId export
   * @param scope scope
   * @param lines ordered lines
   * @return UTF-8 bytes
   */
  public static byte[] csv(UUID exportId, Scope scope, List<Line> lines) {
    StringBuilder csv = new StringBuilder(CsvCells.line(COLUMNS, ','));
    Timesheet.Period period = scope.period();
    for (Line line : lines) {
      csv.append(
          CsvCells.line(
              List.of(
                  Integer.toString(SCHEMA_VERSION),
                  exportId.toString(),
                  scope.legalEntityId().toString(),
                  period.siteId().toString(),
                  period.timezone(),
                  period.weekStart().toString(),
                  period.weekEnd().toString(),
                  line.employeeId().toString(),
                  line.employeeNumber(),
                  line.employmentId().toString(),
                  line.costCenterId() == null ? "" : line.costCenterId().toString(),
                  line.workDate().toString(),
                  line.shiftId().toString(),
                  line.timesheetId().toString(),
                  Integer.toString(line.revision()),
                  line.approvalActionId().toString(),
                  Integer.toString(line.approvalCycle()),
                  Integer.toString(line.workedMinutes())),
              ','));
    }
    return csv.toString().getBytes(StandardCharsets.UTF_8);
  }

  /**
   * The canonical manifest JSON of a stored export (fixed key order; no creator, name or address).
   *
   * @param header the stored export
   * @return UTF-8 bytes
   */
  public static byte[] manifest(Header header) {
    Scope scope = header.scope();
    Timesheet.Period period = scope.period();
    Totals totals = header.totals();
    String json =
        "{\"schemaVersion\":"
            + SCHEMA_VERSION
            + ",\"exportId\":\""
            + header.id()
            + "\",\"status\":\"READY\""
            + ",\"format\":\"text/csv; charset=utf-8\""
            + ",\"columns\":["
            + String.join(",", COLUMNS.stream().map(c -> "\"" + c + "\"").toList())
            + "],\"scope\":{\"legalEntityId\":\""
            + scope.legalEntityId()
            + "\",\"siteId\":\""
            + period.siteId()
            + "\",\"siteTimezone\":\""
            + period.timezone()
            + "\",\"weekStart\":\""
            + period.weekStart()
            + "\",\"weekEnd\":\""
            + period.weekEnd()
            + "\",\"lastDay\":\""
            + period.lastDay()
            + "\",\"periodStartAt\":\""
            + period.startAt()
            + "\",\"periodEndAt\":\""
            + period.endAt()
            + "\"},\"generationId\":\""
            + scope.generationId()
            + "\",\"generationVersion\":"
            + scope.generationVersion()
            + ",\"counts\":{\"timesheets\":"
            + totals.timesheets()
            + ",\"employees\":"
            + totals.employees()
            + ",\"rows\":"
            + totals.rows()
            + "},\"totalWorkedMinutes\":"
            + totals.workedMinutes()
            + ",\"csvSha256\":\""
            + header.csvSha256()
            + "\",\"createdAt\":\""
            + header.createdAt()
            + "\"}\n";
    return json.getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Lowercase hex SHA-256 of bytes.
   *
   * @param bytes bytes
   * @return the digest
   */
  public static String sha256(byte[] bytes) {
    return Fingerprints.sha256(new String(bytes, StandardCharsets.UTF_8));
  }

  /**
   * A bounded band of total worked minutes for events: never the exact total.
   *
   * @param minutes total worked minutes
   * @return the band
   */
  public static String minuteBand(int minutes) {
    if (minutes == 0) {
      return "0";
    }
    if (minutes <= 2_400) {
      return "1-2400";
    }
    if (minutes <= 24_000) {
      return "2401-24000";
    }
    return minutes <= 240_000 ? "24001-240000" : "240001+";
  }
}
