package com.divalhr.core.payroll.domain;

import com.divalhr.core.platform.access.LockedApprovedTime.ApprovedRow;
import com.divalhr.core.platform.access.LockedApprovedTime.Week;
import com.divalhr.core.platform.idempotency.Fingerprints;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Time-based payroll inputs (MVP-090A, Issue #141; ADR 0023): what a legal entity's inclusive
 * period holds in locked approved time, how it reconciles and when it is blocked. Pure functions of
 * the weeks and rows the people module's port returns. No pay is calculated here or anywhere in
 * this increment: minutes, counts, dates and identifiers only.
 */
public final class PayrollInputs {

  /** The payroll-input schema version (the run, its rows and the event). */
  public static final int SCHEMA_VERSION = 1;

  /** The longest supported period (inclusive days): one calendar month at most. */
  public static final int MAX_DAYS = 31;

  /** The first supported period start. */
  public static final LocalDate EARLIEST = LocalDate.of(2026, 1, 1);

  /**
   * What blocks a preview or generation; every one is fail-closed and none permits a partial run.
   */
  public enum Blocker {
    /** A week with scheduled work in the period was never generated. */
    WEEK_NOT_GENERATED,
    /** A generated week in the period is not locked (never locked, or unlocked). */
    WEEK_NOT_LOCKED,
    /** A locked week's current timesheets or approvals no longer match its lock evidence. */
    EVIDENCE_CHANGED,
    /** A row's employment is not placed in the selected legal entity on its work date. */
    ATTRIBUTION_AMBIGUOUS,
    /** No approved time lies in the period. */
    NO_APPROVED_TIME
  }

  /** Why approved time of the covered weeks is left out; stable codes with counts. */
  public enum Exclusion {
    /** An approved row whose work date lies outside the period (a week cut by the period). */
    ROW_OUTSIDE_PERIOD,
    /** An approved timesheet without any READY row in the period. */
    TIMESHEET_WITHOUT_TIME
  }

  private PayrollInputs() {}

  /**
   * The selected scope.
   *
   * @param legalEntityId legal entity
   * @param periodStart first date (inclusive)
   * @param periodEnd last date (inclusive)
   * @param businessTimezone the organization's business time zone
   */
  public record Scope(
      UUID legalEntityId, LocalDate periodStart, LocalDate periodEnd, String businessTimezone) {

    /** Requires every component and a period within the pilot boundaries. */
    public Scope {
      Objects.requireNonNull(legalEntityId, "legalEntityId");
      Objects.requireNonNull(periodStart, "periodStart");
      Objects.requireNonNull(periodEnd, "periodEnd");
      Objects.requireNonNull(businessTimezone, "businessTimezone");
      if (!supported(periodStart, periodEnd)) {
        throw new IllegalArgumentException("period outside the supported boundaries");
      }
    }

    /** Whether a date lies in the period. */
    public boolean covers(LocalDate day) {
      return !day.isBefore(periodStart) && !day.isAfter(periodEnd);
    }
  }

  /**
   * Whether an inclusive range is a supported period: ordered, at most {@value #MAX_DAYS} days, not
   * before {@link #EARLIEST}.
   *
   * @param start first date
   * @param end last date
   * @return whether the period is supported
   */
  public static boolean supported(LocalDate start, LocalDate end) {
    return start != null
        && end != null
        && !end.isBefore(start)
        && end.toEpochDay() - start.toEpochDay() < MAX_DAYS
        && !start.isBefore(EARLIEST);
  }

  /**
   * One locked week the inputs are built from.
   *
   * @param siteId site
   * @param weekStart site-local Monday
   * @param timezone the site's zone
   * @param generationId generation
   * @param generationVersion its version
   * @param lockActionId the LOCKED action
   * @param lockVersion the lock version
   * @param lockEvidenceDigest the evidence digest the lock bound
   * @param totals the week's rows within the period
   */
  public record SourceWeek(
      UUID siteId,
      LocalDate weekStart,
      String timezone,
      UUID generationId,
      int generationVersion,
      UUID lockActionId,
      int lockVersion,
      String lockEvidenceDigest,
      Totals totals) {

    /** Identifiers only. */
    @Override
    public String toString() {
      return "PayrollInputs.SourceWeek[generation=" + generationId + "]";
    }
  }

  /**
   * One employee and employment's inputs.
   *
   * @param employeeId employee
   * @param employmentId employment
   * @param firstWorkDate first work date in the period
   * @param lastWorkDate last work date in the period
   * @param totals the rows
   */
  public record EmployeeInput(
      UUID employeeId,
      UUID employmentId,
      LocalDate firstWorkDate,
      LocalDate lastWorkDate,
      Totals totals) {

    /** Identifiers only. */
    @Override
    public String toString() {
      return "PayrollInputs.EmployeeInput[employee=" + employeeId + "]";
    }
  }

  /**
   * One source row: an approved timesheet row (shift) within the period, bound to its lock.
   *
   * @param employeeId employee
   * @param employmentId employment
   * @param generationId locked generation
   * @param siteId site
   * @param costCenterId cost center, or {@code null}
   * @param workDate site-local date
   * @param shiftId shift
   * @param timesheetId timesheet
   * @param timesheetRevision its revision
   * @param approvalActionId its current APPROVED action
   * @param approvalCycle that action's cycle
   * @param lockActionId the LOCKED action of the generation
   * @param workedMinutes approved worked minutes
   */
  public record SourceRow(
      UUID employeeId,
      UUID employmentId,
      UUID generationId,
      UUID siteId,
      UUID costCenterId,
      LocalDate workDate,
      UUID shiftId,
      UUID timesheetId,
      int timesheetRevision,
      UUID approvalActionId,
      int approvalCycle,
      UUID lockActionId,
      int workedMinutes) {

    /** Identifiers only. */
    @Override
    public String toString() {
      return "PayrollInputs.SourceRow[timesheet=" + timesheetId + ", shift=" + shiftId + "]";
    }
  }

  /**
   * Counts and the total of a set of rows.
   *
   * @param timesheets distinct timesheets
   * @param employees distinct employee and employment pairs
   * @param rows rows
   * @param workedMinutes total worked minutes
   */
  public record Totals(int timesheets, int employees, int rows, int workedMinutes) {

    /** The totals of rows. */
    public static Totals of(List<SourceRow> rows) {
      return new Totals(
          (int) rows.stream().map(SourceRow::timesheetId).distinct().count(),
          (int)
              rows.stream()
                  .map(r -> r.employeeId().toString() + "|" + r.employmentId())
                  .distinct()
                  .count(),
          rows.size(),
          rows.stream().mapToInt(SourceRow::workedMinutes).sum());
    }
  }

  /**
   * A stored run.
   *
   * @param id run
   * @param scope scope
   * @param version run version (1)
   * @param weekCount locked weeks
   * @param totals the rows
   * @param exclusions the exclusion counts
   * @param evidenceDigest the source evidence digest
   * @param generatedAt instant
   */
  public record Run(
      UUID id,
      Scope scope,
      int version,
      int weekCount,
      Totals totals,
      Map<Exclusion, Integer> exclusions,
      String evidenceDigest,
      Instant generatedAt) {

    /** Copies the exclusions. */
    public Run {
      exclusions = Map.copyOf(exclusions);
    }

    /** Identifiers only. */
    @Override
    public String toString() {
      return "PayrollInputs.Run[" + id + "]";
    }
  }

  /**
   * The evaluation of a scope now: the weeks that matter, the rows within the period, the employee
   * inputs, the exclusions, the blockers and the evidence digest.
   *
   * @param scope scope
   * @param weeks every week that matters, in site and week order (locked or not)
   * @param sourceWeeks the locked weeks with rows in the period, in site and week order
   * @param rows the rows within the period in canonical order
   * @param employees the employee inputs in canonical order
   * @param totals the rows' totals
   * @param exclusions exclusion to count (every exclusion present, possibly 0)
   * @param blockers blocker to count (only those present)
   * @param evidenceDigest the source evidence digest (the lock evidence, the versions and the rows)
   */
  public record Evaluation(
      Scope scope,
      List<Week> weeks,
      List<SourceWeek> sourceWeeks,
      List<SourceRow> rows,
      List<EmployeeInput> employees,
      Totals totals,
      Map<Exclusion, Integer> exclusions,
      Map<Blocker, Integer> blockers,
      String evidenceDigest) {

    /** Copies the lists and maps. */
    public Evaluation {
      weeks = List.copyOf(weeks);
      sourceWeeks = List.copyOf(sourceWeeks);
      rows = List.copyOf(rows);
      employees = List.copyOf(employees);
      exclusions = Map.copyOf(exclusions);
      blockers = Map.copyOf(blockers);
    }

    /** Whether the period can be generated now. */
    public boolean ready() {
      return blockers.isEmpty();
    }

    /** The source versions the preview binds: each locked week's generation and lock versions. */
    public String sourceVersions() {
      StringBuilder text = new StringBuilder();
      for (SourceWeek week : sourceWeeks) {
        text.append(week.generationId())
            .append(':')
            .append(week.generationVersion())
            .append(':')
            .append(week.lockActionId())
            .append(':')
            .append(week.lockVersion())
            .append('\n');
      }
      return text.toString();
    }
  }

  /**
   * Evaluates a scope from the weeks that matter and, for every locked week, its approved rows.
   *
   * @param scope scope
   * @param weeks the weeks the port returned (site and week order)
   * @param approved the approved rows of every locked generation (generation to rows)
   * @return the evaluation
   */
  public static Evaluation evaluate(
      Scope scope, List<Week> weeks, Map<UUID, List<ApprovedRow>> approved) {
    Map<Blocker, Integer> blockers = new TreeMap<>();
    Map<Exclusion, Integer> exclusions = new TreeMap<>();
    for (Exclusion exclusion : Exclusion.values()) {
      exclusions.put(exclusion, 0);
    }
    List<SourceWeek> sourceWeeks = new ArrayList<>();
    List<SourceRow> rows = new ArrayList<>();
    for (Week week : weeks) {
      if (week.generation() == null) {
        if (week.scheduled()) {
          blockers.merge(Blocker.WEEK_NOT_GENERATED, 1, Integer::sum);
        }
        continue;
      }
      var generation = week.generation();
      if (generation.timesheets() == 0) {
        // A generated week without any timesheet (every employee left) holds no time and cannot be
        // locked; nothing is missing from payroll.
        continue;
      }
      if (!generation.locked()) {
        blockers.merge(Blocker.WEEK_NOT_LOCKED, 1, Integer::sum);
        continue;
      }
      var lock = generation.lock();
      if (!lock.evidenceDigest().equals(generation.evidenceDigest())
          || lock.generationVersion() != generation.version()) {
        blockers.merge(Blocker.EVIDENCE_CHANGED, 1, Integer::sum);
        continue;
      }
      List<SourceRow> weekRows = new ArrayList<>();
      Map<UUID, Boolean> timesheetsWithTime = new LinkedHashMap<>();
      for (ApprovedRow row : approved.getOrDefault(generation.id(), List.of())) {
        timesheetsWithTime.putIfAbsent(row.timesheetId(), false);
        if (!scope.covers(row.workDate())) {
          exclusions.merge(Exclusion.ROW_OUTSIDE_PERIOD, 1, Integer::sum);
          continue;
        }
        if (!scope.legalEntityId().equals(row.employmentLegalEntityId())) {
          blockers.merge(Blocker.ATTRIBUTION_AMBIGUOUS, 1, Integer::sum);
          continue;
        }
        timesheetsWithTime.put(row.timesheetId(), true);
        weekRows.add(
            new SourceRow(
                row.employeeId(),
                row.employmentId(),
                generation.id(),
                week.siteId(),
                row.costCenterId(),
                row.workDate(),
                row.shiftId(),
                row.timesheetId(),
                row.revision(),
                row.approvalActionId(),
                row.approvalCycle(),
                lock.actionId(),
                row.workedMinutes()));
      }
      exclusions.merge(
          Exclusion.TIMESHEET_WITHOUT_TIME,
          (int) timesheetsWithTime.values().stream().filter(with -> !with).count(),
          Integer::sum);
      if (!weekRows.isEmpty()) {
        sourceWeeks.add(
            new SourceWeek(
                week.siteId(),
                week.weekStart(),
                week.timezone(),
                generation.id(),
                generation.version(),
                lock.actionId(),
                lock.version(),
                lock.evidenceDigest(),
                Totals.of(weekRows)));
        rows.addAll(weekRows);
      }
    }
    rows.sort(ROW_ORDER);
    if (rows.isEmpty() && blockers.isEmpty()) {
      blockers.put(Blocker.NO_APPROVED_TIME, 1);
    }
    List<EmployeeInput> employees = employees(rows);
    Totals totals = Totals.of(rows);
    return new Evaluation(
        scope,
        weeks,
        sourceWeeks,
        rows,
        employees,
        totals,
        exclusions,
        blockers,
        evidenceDigest(scope, sourceWeeks, rows));
  }

  /** Employee and employment, then work date, then shift: the stored line order. */
  static final Comparator<SourceRow> ROW_ORDER =
      Comparator.comparing((SourceRow r) -> r.employeeId().toString())
          .thenComparing(r -> r.employmentId().toString())
          .thenComparing(SourceRow::workDate)
          .thenComparing(r -> r.shiftId().toString());

  /** The employee inputs of ordered rows, in the rows' order. */
  static List<EmployeeInput> employees(List<SourceRow> ordered) {
    Map<String, List<SourceRow>> grouped = new LinkedHashMap<>();
    for (SourceRow row : ordered) {
      grouped
          .computeIfAbsent(row.employeeId() + "|" + row.employmentId(), k -> new ArrayList<>())
          .add(row);
    }
    List<EmployeeInput> employees = new ArrayList<>();
    for (List<SourceRow> rows : grouped.values()) {
      SourceRow first = rows.get(0);
      employees.add(
          new EmployeeInput(
              first.employeeId(),
              first.employmentId(),
              rows.stream().map(SourceRow::workDate).min(Comparator.naturalOrder()).orElseThrow(),
              rows.stream().map(SourceRow::workDate).max(Comparator.naturalOrder()).orElseThrow(),
              Totals.of(rows)));
    }
    return employees;
  }

  /**
   * The source evidence digest: the scope, every locked week's generation and lock versions and
   * evidence digest, and every row's identity, binding and minutes. Identical locked evidence gives
   * the same digest; any unlock, relock, refresh or row change gives another.
   *
   * @param scope scope
   * @param weeks the locked weeks in order
   * @param rows the rows in canonical order
   * @return lowercase hex SHA-256
   */
  public static String evidenceDigest(Scope scope, List<SourceWeek> weeks, List<SourceRow> rows) {
    StringBuilder text =
        new StringBuilder("DIVALHR-PAYROLL-INPUT-EVIDENCE\nversion=1\nlegalEntity=")
            .append(scope.legalEntityId())
            .append("\nperiodStart=")
            .append(scope.periodStart())
            .append("\nperiodEnd=")
            .append(scope.periodEnd())
            .append("\nzone=")
            .append(scope.businessTimezone())
            .append('\n');
    for (SourceWeek week : weeks) {
      text.append("week|")
          .append(week.siteId())
          .append('|')
          .append(week.weekStart())
          .append('|')
          .append(week.generationId())
          .append('|')
          .append(week.generationVersion())
          .append('|')
          .append(week.lockActionId())
          .append('|')
          .append(week.lockVersion())
          .append('|')
          .append(week.lockEvidenceDigest())
          .append('\n');
    }
    for (SourceRow row : rows) {
      text.append("row|")
          .append(row.employeeId())
          .append('|')
          .append(row.employmentId())
          .append('|')
          .append(row.generationId())
          .append('|')
          .append(row.siteId())
          .append('|')
          .append(row.costCenterId() == null ? "-" : row.costCenterId())
          .append('|')
          .append(row.workDate())
          .append('|')
          .append(row.shiftId())
          .append('|')
          .append(row.timesheetId())
          .append('|')
          .append(row.timesheetRevision())
          .append('|')
          .append(row.approvalActionId())
          .append('|')
          .append(row.approvalCycle())
          .append('|')
          .append(row.lockActionId())
          .append('|')
          .append(row.workedMinutes())
          .append('\n');
    }
    return Fingerprints.sha256(text.toString());
  }

  /**
   * A bounded band of worked minutes for logs, metrics and audit metadata: never the exact total.
   *
   * @param minutes minutes
   * @return the band
   */
  public static String minuteBand(int minutes) {
    if (minutes == 0) {
      return "0";
    }
    if (minutes <= 2400) {
      return "1-2400";
    }
    if (minutes <= 24000) {
      return "2401-24000";
    }
    if (minutes <= 240000) {
      return "24001-240000";
    }
    return "240001+";
  }

  /**
   * A bounded band of a count for logs and metrics: never the exact count.
   *
   * @param count a count
   * @return the band
   */
  public static String countBand(int count) {
    if (count == 0) {
      return "0";
    }
    if (count <= 10) {
      return "1-10";
    }
    if (count <= 100) {
      return "11-100";
    }
    if (count <= 1000) {
      return "101-1000";
    }
    return "1001+";
  }
}
