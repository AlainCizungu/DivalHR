package com.divalhr.core.payroll.domain;

import com.divalhr.core.payroll.domain.PayrollInputs.EmployeeInput;
import com.divalhr.core.payroll.domain.PayrollInputs.Run;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Review of payroll-input variance (MVP-091, Issue #143; ADR 0024): the worked-time difference of
 * two immutable payroll-input runs (MVP-090A) of one legal entity, employee by employee. Pure
 * functions of the two runs' employee inputs; nothing is stored. No pay is calculated here or
 * anywhere in this increment: minutes, counts and identifiers only, with integer arithmetic only
 * (no floating point anywhere in a comparison).
 */
public final class PayrollVariance {

  /** The materiality threshold bounds and defaults. */
  public static final int DEFAULT_ABSOLUTE_MINUTES = 120;

  /** Default percentage threshold. */
  public static final int DEFAULT_PERCENTAGE = 20;

  /** One week of minutes: the largest absolute threshold. */
  public static final int MAX_ABSOLUTE_MINUTES = 10_080;

  /** Largest percentage threshold. */
  public static final int MAX_PERCENTAGE = 100;

  /** The number of eligible comparison runs offered for a current run. */
  public static final int MAX_CANDIDATES = 50;

  /** The response schema version. */
  public static final int SCHEMA_VERSION = 1;

  private PayrollVariance() {}

  /** How an employee compares across the two runs. */
  public enum Classification {
    /** Present only in the current run. */
    NEW,
    /** Present only in the comparison run. */
    MISSING,
    /** Present in both with a non-zero worked-minute delta. */
    CHANGED,
    /** Present in both with no worked-minute delta. */
    UNCHANGED
  }

  /** The review filter: all rows, the material ones, or one classification. */
  public enum View {
    /** Every employee. */
    ALL,
    /** Material employees only. */
    MATERIAL,
    /** New employees. */
    NEW,
    /** Missing employees. */
    MISSING,
    /** Changed employees. */
    CHANGED,
    /** Unchanged employees. */
    UNCHANGED;

    boolean admits(Line line) {
      return switch (this) {
        case ALL -> true;
        case MATERIAL -> line.material();
        case NEW -> line.classification() == Classification.NEW;
        case MISSING -> line.classification() == Classification.MISSING;
        case CHANGED -> line.classification() == Classification.CHANGED;
        case UNCHANGED -> line.classification() == Classification.UNCHANGED;
      };
    }
  }

  /**
   * The review thresholds (never persisted).
   *
   * @param absoluteMinutes absolute minute threshold, 0 to 10,080
   * @param percentage percentage threshold, 0 to 100
   */
  public record Thresholds(int absoluteMinutes, int percentage) {

    /** The documented defaults. */
    public static final Thresholds DEFAULT =
        new Thresholds(DEFAULT_ABSOLUTE_MINUTES, DEFAULT_PERCENTAGE);

    /** Validates the bounds. */
    public Thresholds {
      if (absoluteMinutes < 0 || absoluteMinutes > MAX_ABSOLUTE_MINUTES) {
        throw new IllegalArgumentException("absolute threshold out of range");
      }
      if (percentage < 0 || percentage > MAX_PERCENTAGE) {
        throw new IllegalArgumentException("percentage threshold out of range");
      }
    }
  }

  /**
   * What one run holds for one employee, all the employee's employments of that run together.
   *
   * @param employeeId employee
   * @param employmentIds the run's employments of the employee, ordered
   * @param timesheets timesheets
   * @param rows input rows
   * @param workedMinutes approved worked minutes
   */
  public record Figures(
      UUID employeeId, List<UUID> employmentIds, int timesheets, int rows, int workedMinutes) {

    /** Copies the employments. */
    public Figures {
      employmentIds = List.copyOf(employmentIds);
    }

    /** Identifiers only. */
    @Override
    public String toString() {
      return "PayrollVariance.Figures[employee=" + employeeId + "]";
    }
  }

  /**
   * One employee's comparison.
   *
   * @param employeeId employee (the immutable identity both runs share)
   * @param current figures of the current run, or {@code null}
   * @param comparison figures of the comparison run, or {@code null}
   * @param classification the classification
   * @param minuteDelta current minutes minus comparison minutes
   * @param percentageDeltaBasisPoints signed delta over comparison minutes in basis points (rounded
   *     half up on the absolute value), or {@code null} when the comparison minutes are zero
   * @param material whether the review thresholds make it material
   */
  public record Line(
      UUID employeeId,
      Figures current,
      Figures comparison,
      Classification classification,
      int minuteDelta,
      Integer percentageDeltaBasisPoints,
      boolean material) {

    /** Identifiers only. */
    @Override
    public String toString() {
      return "PayrollVariance.Line[employee=" + employeeId + "]";
    }
  }

  /**
   * The totals, each exactly the sum or count of the lines.
   *
   * @param currentEmployees employees of the current run
   * @param comparisonEmployees employees of the comparison run
   * @param currentMinutes current approved worked minutes
   * @param comparisonMinutes comparison approved worked minutes
   * @param minuteDelta signed total delta
   * @param counts lines per classification
   * @param materialCount material lines
   */
  public record Totals(
      int currentEmployees,
      int comparisonEmployees,
      long currentMinutes,
      long comparisonMinutes,
      long minuteDelta,
      Map<Classification, Integer> counts,
      int materialCount) {

    /** Copies the counts. */
    public Totals {
      counts = Map.copyOf(counts);
    }
  }

  /**
   * The whole comparison, in review order.
   *
   * @param lines every line: material first, then the largest absolute delta, then employee ID
   * @param totals the reconciled totals
   */
  public record Comparison(List<Line> lines, Totals totals) {

    /** Copies the lines. */
    public Comparison {
      lines = List.copyOf(lines);
    }
  }

  /**
   * Two runs can be compared when they belong to the same legal entity, the comparison run ends
   * before the current run begins and both cover the same inclusive number of calendar days. (The
   * tenant is enforced by the queries.)
   *
   * @param current the current run
   * @param candidate the possible comparison run
   * @return whether the candidate is eligible
   */
  public static boolean eligible(Run current, Run candidate) {
    return !candidate.id().equals(current.id())
        && candidate.scope().legalEntityId().equals(current.scope().legalEntityId())
        && candidate.scope().periodEnd().isBefore(current.scope().periodStart())
        && days(candidate) == days(current);
  }

  /**
   * The inclusive number of calendar days of a run's period.
   *
   * @param run run
   * @return the days
   */
  public static long days(Run run) {
    return run.scope().periodEnd().toEpochDay() - run.scope().periodStart().toEpochDay() + 1;
  }

  /**
   * Compares two runs' employee inputs. Employees are matched by their immutable employee ID; an
   * employee's employments within one run are added together and listed, never merged across runs.
   *
   * @param current the current run's inputs
   * @param comparison the comparison run's inputs
   * @param thresholds the review thresholds
   * @return the comparison
   */
  public static Comparison compare(
      List<EmployeeInput> current, List<EmployeeInput> comparison, Thresholds thresholds) {
    Map<UUID, Figures> now = figures(current);
    Map<UUID, Figures> before = figures(comparison);
    List<Line> lines = new ArrayList<>();
    Map<UUID, Boolean> seen = new TreeMap<>();
    now.keySet().forEach(id -> seen.put(id, Boolean.TRUE));
    before.keySet().forEach(id -> seen.put(id, Boolean.TRUE));
    Map<Classification, Integer> counts = new EnumMap<>(Classification.class);
    for (Classification kind : Classification.values()) {
      counts.put(kind, 0);
    }
    long currentMinutes = 0;
    long comparisonMinutes = 0;
    long totalDelta = 0;
    int material = 0;
    for (UUID id : seen.keySet()) {
      Line line = line(id, now.get(id), before.get(id), thresholds);
      lines.add(line);
      counts.merge(line.classification(), 1, Integer::sum);
      currentMinutes += line.current() == null ? 0 : line.current().workedMinutes();
      comparisonMinutes += line.comparison() == null ? 0 : line.comparison().workedMinutes();
      totalDelta += line.minuteDelta();
      if (line.material()) {
        material++;
      }
    }
    lines.sort(REVIEW_ORDER);
    return new Comparison(
        lines,
        new Totals(
            now.size(),
            before.size(),
            currentMinutes,
            comparisonMinutes,
            totalDelta,
            counts,
            material));
  }

  /** Material first, then the largest absolute delta, then the employee ID (text order). */
  public static final Comparator<Line> REVIEW_ORDER =
      Comparator.comparing((Line line) -> !line.material())
          .thenComparing(line -> -(long) Math.abs(line.minuteDelta()))
          .thenComparing(line -> line.employeeId().toString());

  /**
   * Whether a line follows a position in review order.
   *
   * @param line the line
   * @param material the position's materiality
   * @param absoluteDelta the position's absolute delta
   * @param employeeId the position's employee
   * @return whether the line comes after the position
   */
  public static boolean after(Line line, boolean material, int absoluteDelta, UUID employeeId) {
    int byMaterial = Boolean.compare(!line.material(), !material);
    if (byMaterial != 0) {
      return byMaterial > 0;
    }
    int byDelta = Integer.compare(absoluteDelta, Math.abs(line.minuteDelta()));
    if (byDelta != 0) {
      return byDelta > 0;
    }
    return line.employeeId().toString().compareTo(employeeId.toString()) > 0;
  }

  /**
   * The lines a view admits, in review order.
   *
   * @param comparison the comparison
   * @param view the filter
   * @return the lines
   */
  public static List<Line> filtered(Comparison comparison, View view) {
    return comparison.lines().stream().filter(view::admits).toList();
  }

  // ------------------------------------------------------------------------------------------

  private static Map<UUID, Figures> figures(List<EmployeeInput> inputs) {
    Map<UUID, List<EmployeeInput>> byEmployee = new TreeMap<>();
    for (EmployeeInput input : inputs) {
      byEmployee.computeIfAbsent(input.employeeId(), k -> new ArrayList<>()).add(input);
    }
    Map<UUID, Figures> result = new TreeMap<>();
    byEmployee.forEach(
        (employee, list) -> {
          TreeSet<UUID> employments = new TreeSet<>(Comparator.comparing(UUID::toString));
          int timesheets = 0;
          int rows = 0;
          int minutes = 0;
          for (EmployeeInput input : list) {
            employments.add(input.employmentId());
            timesheets += input.totals().timesheets();
            rows += input.totals().rows();
            minutes += input.totals().workedMinutes();
          }
          result.put(
              employee, new Figures(employee, List.copyOf(employments), timesheets, rows, minutes));
        });
    return result;
  }

  private static Line line(UUID id, Figures current, Figures comparison, Thresholds thresholds) {
    int now = current == null ? 0 : current.workedMinutes();
    int before = comparison == null ? 0 : comparison.workedMinutes();
    int delta = now - before;
    Classification kind =
        comparison == null
            ? Classification.NEW
            : current == null
                ? Classification.MISSING
                : delta != 0 ? Classification.CHANGED : Classification.UNCHANGED;
    return new Line(
        id,
        current,
        comparison,
        kind,
        delta,
        basisPoints(delta, before),
        material(kind, delta, before, thresholds));
  }

  /**
   * Whether a difference is material: any new or missing employee, or a changed one whose absolute
   * delta reaches the absolute threshold or, when the comparison minutes are positive, whose
   * absolute percentage reaches the percentage threshold. Exact integer arithmetic: {@code |delta|
   * × 100 ≥ percentage × comparison}. An unchanged employee has no difference to be material, so a
   * zero threshold makes every <em>difference</em> material and nothing else.
   *
   * @param kind classification
   * @param delta signed minute delta
   * @param comparisonMinutes comparison minutes
   * @param thresholds thresholds
   * @return whether it is material
   */
  public static boolean material(
      Classification kind, int delta, int comparisonMinutes, Thresholds thresholds) {
    if (kind == Classification.NEW || kind == Classification.MISSING) {
      return true;
    }
    if (kind == Classification.UNCHANGED) {
      return false;
    }
    long absolute = Math.abs((long) delta);
    return absolute >= thresholds.absoluteMinutes()
        || (comparisonMinutes > 0
            && absolute * 100 >= (long) thresholds.percentage() * comparisonMinutes);
  }

  /**
   * The signed percentage of the comparison minutes in basis points, rounded half up on the
   * absolute value; {@code null} when the comparison minutes are not greater than zero.
   *
   * @param delta signed delta
   * @param comparisonMinutes comparison minutes
   * @return basis points, or {@code null}
   */
  public static Integer basisPoints(int delta, int comparisonMinutes) {
    if (comparisonMinutes <= 0) {
      return null;
    }
    long absolute = Math.abs((long) delta);
    long scaled = (absolute * 10_000 * 2 + comparisonMinutes) / (2L * comparisonMinutes);
    return (int) (delta < 0 ? -scaled : scaled);
  }
}
