package com.divalhr.core.people.domain.separation;

import com.divalhr.core.people.domain.history.Assignment;
import com.divalhr.core.people.domain.history.AssignmentKind;
import com.divalhr.core.people.domain.history.AssignmentValue;
import com.divalhr.core.people.domain.history.NewAssignment;
import com.divalhr.core.people.domain.history.TimelinePlan;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The temporal engine of a separation (MVP-022), without side effects. The V15 triggers enforce the
 * same rules at commit (exact SEPARATION shape, separation-bound changes, exact reversal, rows
 * inside the employment, manager employed), so a plan that breaks them never persists.
 *
 * <p>With last day D:
 *
 * <ul>
 *   <li>the separated employee's active rows that end on or before D are untouched; a row covering
 *       D and continuing after it is replaced by a copy over [its start, D] with its value and
 *       origin; a row starting after D that is already in force (retroactive separation) is ended;
 *       a row starting after both D and today is a future effect that blocks the separation until
 *       it is cancelled (A22-3: read from the active timeline, whatever command wrote it);
 *   <li>every direct-report MANAGER row naming the separated employee after D is rewritten by one
 *       separation-bound change per row (A22-2): from D+1 for a row covering D (its earlier part
 *       copied), from its own start otherwise, with the plan's replacement or no manager;
 *   <li>a cancellation reverses each change exactly: the rows it wrote are replaced and the rows it
 *       replaced are restored with their own dates, values and origins.
 * </ul>
 */
public final class SeparationPlanner {

  private SeparationPlanner() {}

  /**
   * The separated employee's own effects.
   *
   * @param plan rows superseded and the copies created
   * @param blockers active rows starting after the last day and after today (must be cancelled)
   */
  public record Own(TimelinePlan plan, List<Assignment> blockers) {

    /** Copies the blockers. */
    public Own {
      blockers = List.copyOf(blockers);
    }
  }

  /**
   * Plans the employee's own closure.
   *
   * @param rows every row of the employment (active and superseded)
   * @param lastDay inclusive last day D
   * @param today business date
   * @param ids new row IDs
   * @return the plan and the blockers
   */
  public static Own own(
      List<Assignment> rows, LocalDate lastDay, LocalDate today, Supplier<UUID> ids) {
    List<Assignment> superseded = new ArrayList<>();
    List<NewAssignment> created = new ArrayList<>();
    List<Assignment> blockers = new ArrayList<>();
    for (Assignment row : sorted(rows)) {
      if (!row.active() || (row.to() != null && !row.to().isAfter(lastDay))) {
        continue;
      }
      if (!row.from().isAfter(lastDay)) {
        superseded.add(row);
        created.add(
            new NewAssignment(
                ids.get(), row.kind(), row.from(), lastDay, row.value(), row.origin(), null));
      } else if (row.from().isAfter(today)) {
        blockers.add(row);
      } else {
        superseded.add(row);
      }
    }
    return new Own(new TimelinePlan(superseded, created, false), blockers);
  }

  /**
   * One active MANAGER row of a direct report naming the separated employee after the last day.
   *
   * @param employeeId the report
   * @param employmentId the report's employment
   * @param row the row
   */
  public record ReportRow(UUID employeeId, UUID employmentId, Assignment row) {

    /** Requires a MANAGER row. */
    public ReportRow {
      Objects.requireNonNull(employeeId, "employeeId");
      Objects.requireNonNull(employmentId, "employmentId");
      if (row.kind() != AssignmentKind.MANAGER) {
        throw new IllegalArgumentException("not a manager row");
      }
    }
  }

  /**
   * One report's affected rows as maximal contiguous intervals (A22-2).
   *
   * @param employeeId the report
   * @param employmentId the report's employment
   * @param intervals the intervals, each the ordered rows it covers
   */
  public record ReportIntervals(
      UUID employeeId, UUID employmentId, List<List<Assignment>> intervals) {

    /** Copies the intervals. */
    public ReportIntervals {
      intervals = List.copyOf(intervals.stream().map(List::copyOf).toList());
    }

    /**
     * Every affected row, in date order.
     *
     * @return rows
     */
    public List<Assignment> rows() {
      return intervals.stream().flatMap(List::stream).toList();
    }
  }

  /**
   * Groups the affected rows of each report employment into maximal contiguous intervals, ordered
   * by report then employment then date. Rows ending on or before the last day are ignored.
   *
   * @param rows active MANAGER rows naming the separated employee
   * @param lastDay inclusive last day D
   * @return per report employment, its intervals
   */
  public static List<ReportIntervals> intervals(List<ReportRow> rows, LocalDate lastDay) {
    List<ReportRow> affected =
        rows.stream()
            .filter(r -> r.row().active())
            .filter(r -> r.row().to() == null || r.row().to().isAfter(lastDay))
            .sorted(
                Comparator.comparing(ReportRow::employeeId)
                    .thenComparing(ReportRow::employmentId)
                    .thenComparing(r -> r.row().from()))
            .toList();
    List<ReportIntervals> grouped = new ArrayList<>();
    List<List<Assignment>> intervals = new ArrayList<>();
    List<Assignment> current = new ArrayList<>();
    ReportRow previous = null;
    for (ReportRow next : affected) {
      boolean sameEmployment =
          previous != null && previous.employmentId().equals(next.employmentId());
      if (!sameEmployment && previous != null) {
        intervals.add(current);
        grouped.add(new ReportIntervals(previous.employeeId(), previous.employmentId(), intervals));
        intervals = new ArrayList<>();
        current = new ArrayList<>();
      } else if (sameEmployment
          && (previous.row().to() == null
              || !previous.row().to().plusDays(1).equals(next.row().from()))) {
        intervals.add(current);
        current = new ArrayList<>();
      }
      current.add(next.row());
      previous = next;
    }
    if (previous != null) {
      intervals.add(current);
      grouped.add(new ReportIntervals(previous.employeeId(), previous.employmentId(), intervals));
    }
    return grouped;
  }

  /**
   * The effective date of the change that rewrites one affected row: D+1 for a row covering D, else
   * the row's own start.
   *
   * @param row affected row
   * @param lastDay inclusive last day D
   * @return the change's effective date
   */
  public static LocalDate reportChangeDate(Assignment row, LocalDate lastDay) {
    return row.from().isAfter(lastDay) ? row.from() : lastDay.plusDays(1);
  }

  /**
   * Plans the separation-bound change of one affected row.
   *
   * @param changeId the bound change
   * @param row affected row
   * @param lastDay inclusive last day D
   * @param replacement the replacement manager, or {@code null} to clear
   * @param ids new row IDs
   * @return the plan
   */
  public static TimelinePlan reportRow(
      UUID changeId, Assignment row, LocalDate lastDay, UUID replacement, Supplier<UUID> ids) {
    List<NewAssignment> created = new ArrayList<>();
    LocalDate start = reportChangeDate(row, lastDay);
    if (row.from().isBefore(start)) {
      created.add(
          new NewAssignment(
              ids.get(),
              AssignmentKind.MANAGER,
              row.from(),
              start.minusDays(1),
              row.value(),
              row.origin(),
              null));
    }
    if (replacement != null) {
      created.add(
          new NewAssignment(
              ids.get(),
              AssignmentKind.MANAGER,
              start,
              row.to(),
              new AssignmentValue.Manager(replacement),
              changeId,
              null));
    }
    return new TimelinePlan(List.of(row), created, false);
  }

  /**
   * Plans the exact reversal of a change (A22-2): every row it wrote is replaced and every row it
   * replaced is restored with its own dates, value and origin.
   *
   * @param changeId the change to reverse
   * @param rows every row of the change's employment
   * @param ids new row IDs
   * @return the plan
   * @throws SeparationRuleException when a row the change wrote was replaced since
   */
  public static TimelinePlan reversal(UUID changeId, List<Assignment> rows, Supplier<UUID> ids) {
    List<Assignment> written = rows.stream().filter(r -> r.createdBy().equals(changeId)).toList();
    if (written.stream().anyMatch(r -> !r.active())) {
      throw new SeparationRuleException(SeparationRuleException.Rule.HISTORY_CHANGED_SINCE);
    }
    List<NewAssignment> restored = new ArrayList<>();
    for (Assignment replaced : sorted(rows)) {
      if (changeId.equals(replaced.supersededBy())) {
        restored.add(
            new NewAssignment(
                ids.get(),
                replaced.kind(),
                replaced.from(),
                replaced.to(),
                replaced.value(),
                replaced.origin(),
                replaced.id()));
      }
    }
    return new TimelinePlan(written, restored, false);
  }

  private static List<Assignment> sorted(List<Assignment> rows) {
    return rows.stream()
        .sorted(
            Comparator.comparing(Assignment::kind)
                .thenComparing(Assignment::from)
                .thenComparing(Assignment::id))
        .toList();
  }
}
