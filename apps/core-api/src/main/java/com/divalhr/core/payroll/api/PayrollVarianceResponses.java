package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.api.PayrollInputResponses.Summary;
import com.divalhr.core.payroll.domain.PayrollVariance;
import com.divalhr.core.payroll.domain.PayrollVariance.Classification;
import com.divalhr.core.payroll.domain.PayrollVariance.Figures;
import com.divalhr.core.payroll.domain.PayrollVariance.Line;
import com.divalhr.core.payroll.domain.PayrollVariance.Totals;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * Response shapes of MVP-091 (payroll-input variance review). Identifiers, codes, counts and worked
 * minutes only: no name of a person, no employee number, no subject, no amount. The percentage is
 * in basis points (an integer): nothing here is floating point.
 */
public final class PayrollVarianceResponses {

  private PayrollVarianceResponses() {}

  /**
   * The eligible comparison runs of a current run.
   *
   * @param current the current run
   * @param candidates the eligible runs, newest period first (at most 50)
   * @param defaultRunId the newest eligible run, or {@code null} when there is none
   */
  @Schema(name = "PayrollVarianceCandidates")
  public record Candidates(
      Summary current, List<Summary> candidates, @Schema(nullable = true) UUID defaultRunId) {

    /** Copies the list. */
    public Candidates {
      candidates = List.copyOf(candidates);
    }
  }

  /**
   * The review thresholds in force (never stored).
   *
   * @param absoluteMinuteThreshold 0 to 10,080
   * @param percentageThreshold 0 to 100
   */
  @Schema(name = "PayrollVarianceThresholds")
  public record ThresholdsView(int absoluteMinuteThreshold, int percentageThreshold) {}

  /**
   * What one run holds for one employee.
   *
   * @param employmentIds the run's employments of the employee
   * @param timesheets timesheets
   * @param rows input rows
   * @param workedMinutes approved worked minutes
   */
  @Schema(name = "PayrollVarianceFigures")
  public record FiguresView(List<UUID> employmentIds, int timesheets, int rows, int workedMinutes) {

    /** Copies the list. */
    public FiguresView {
      employmentIds = List.copyOf(employmentIds);
    }

    /** The view of figures. @param figures figures or null @return the view */
    public static FiguresView of(Figures figures) {
      return figures == null
          ? null
          : new FiguresView(
              figures.employmentIds(),
              figures.timesheets(),
              figures.rows(),
              figures.workedMinutes());
    }
  }

  /**
   * One employee's comparison.
   *
   * @param employeeId the employee both runs share
   * @param classification NEW, MISSING, CHANGED or UNCHANGED
   * @param current current run's figures, or {@code null} for MISSING
   * @param comparison comparison run's figures, or {@code null} for NEW
   * @param currentMinutes current minutes
   * @param comparisonMinutes comparison minutes
   * @param minuteDelta current minus comparison
   * @param percentageDeltaBasisPoints signed percentage in basis points, only when the comparison
   *     minutes are greater than zero
   * @param material whether the thresholds make it material
   */
  @Schema(name = "PayrollVarianceLine")
  public record LineView(
      UUID employeeId,
      @Schema(allowableValues = {"NEW", "MISSING", "CHANGED", "UNCHANGED"}) String classification,
      @Schema(nullable = true) FiguresView current,
      @Schema(nullable = true) FiguresView comparison,
      int currentMinutes,
      int comparisonMinutes,
      int minuteDelta,
      @Schema(nullable = true) Integer percentageDeltaBasisPoints,
      boolean material) {

    /** The view of a line. @param line line @return the view */
    public static LineView of(Line line) {
      return new LineView(
          line.employeeId(),
          line.classification().name(),
          FiguresView.of(line.current()),
          FiguresView.of(line.comparison()),
          line.current() == null ? 0 : line.current().workedMinutes(),
          line.comparison() == null ? 0 : line.comparison().workedMinutes(),
          line.minuteDelta(),
          line.percentageDeltaBasisPoints(),
          line.material());
    }
  }

  /**
   * Totals that reconcile exactly to every line (not only the page).
   *
   * @param currentEmployees employees of the current run
   * @param comparisonEmployees employees of the comparison run
   * @param currentMinutes current minutes
   * @param comparisonMinutes comparison minutes
   * @param minuteDelta signed total delta
   * @param newCount NEW lines
   * @param missingCount MISSING lines
   * @param changedCount CHANGED lines
   * @param unchangedCount UNCHANGED lines
   * @param materialCount material lines
   */
  @Schema(name = "PayrollVarianceTotals")
  public record TotalsView(
      int currentEmployees,
      int comparisonEmployees,
      long currentMinutes,
      long comparisonMinutes,
      long minuteDelta,
      int newCount,
      int missingCount,
      int changedCount,
      int unchangedCount,
      int materialCount) {

    /** The view of totals. @param totals totals @return the view */
    public static TotalsView of(Totals totals) {
      return new TotalsView(
          totals.currentEmployees(),
          totals.comparisonEmployees(),
          totals.currentMinutes(),
          totals.comparisonMinutes(),
          totals.minuteDelta(),
          totals.counts().get(Classification.NEW),
          totals.counts().get(Classification.MISSING),
          totals.counts().get(Classification.CHANGED),
          totals.counts().get(Classification.UNCHANGED),
          totals.materialCount());
    }
  }

  /**
   * One page of a variance review.
   *
   * @param current the current run
   * @param comparison the comparison run
   * @param comparisonIsDefault whether the server chose the newest eligible run
   * @param thresholds the thresholds applied
   * @param view the filter applied
   * @param totals totals over every employee, whatever the filter
   * @param matchingCount lines the filter admits (all pages)
   * @param items this page, material first, then the largest absolute delta, then employee ID
   * @param nextCursor opaque cursor bound to tenant, both runs, thresholds, filter and page size
   */
  @Schema(name = "PayrollVarianceReview")
  public record Review(
      Summary current,
      Summary comparison,
      boolean comparisonIsDefault,
      ThresholdsView thresholds,
      @Schema(allowableValues = {"ALL", "MATERIAL", "NEW", "MISSING", "CHANGED", "UNCHANGED"})
          String view,
      TotalsView totals,
      int matchingCount,
      List<LineView> items,
      @Schema(nullable = true) String nextCursor) {

    /** Copies the list. */
    public Review {
      items = List.copyOf(items);
    }

    /** The view of thresholds. @param thresholds thresholds @return the view */
    public static ThresholdsView thresholds(PayrollVariance.Thresholds thresholds) {
      return new ThresholdsView(thresholds.absoluteMinutes(), thresholds.percentage());
    }
  }
}
