package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.payroll.domain.PayrollInputs.EmployeeInput;
import com.divalhr.core.payroll.domain.PayrollInputs.Run;
import com.divalhr.core.payroll.domain.PayrollInputs.Scope;
import com.divalhr.core.payroll.domain.PayrollInputs.Totals;
import com.divalhr.core.payroll.domain.PayrollVariance;
import com.divalhr.core.payroll.domain.PayrollVariance.Classification;
import com.divalhr.core.payroll.domain.PayrollVariance.Comparison;
import com.divalhr.core.payroll.domain.PayrollVariance.Line;
import com.divalhr.core.payroll.domain.PayrollVariance.Thresholds;
import com.divalhr.core.payroll.domain.PayrollVariance.View;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * MVP-091 domain rules: matching by employee ID (employments of one run added and listed, never
 * merged across runs), the four classifications, signed deltas, basis-point percentages,
 * materiality at its exact boundaries (zero comparison minutes, zero thresholds), review order,
 * filters, eligibility of a comparison run and the exact reconciliation of the totals.
 */
class PayrollVarianceTest {

  private static final UUID ENTITY = UUID.fromString("00000000-0000-4000-8000-00000000000e");
  private static final UUID OTHER_ENTITY = UUID.fromString("00000000-0000-4000-8000-00000000000f");
  private static final Thresholds DEFAULTS = Thresholds.DEFAULT;

  private static UUID id(int n) {
    return UUID.fromString(String.format("00000000-0000-4000-8000-%012d", n));
  }

  private static EmployeeInput input(int employee, int employment, int minutes) {
    return new EmployeeInput(
        id(employee),
        id(1000 + employment),
        LocalDate.of(2026, 10, 12),
        LocalDate.of(2026, 10, 18),
        new Totals(2, 1, 3, minutes));
  }

  private static Run run(UUID entity, LocalDate start, LocalDate end) {
    return new Run(
        UUID.randomUUID(),
        new Scope(entity, start, end, "Africa/Kinshasa"),
        1,
        1,
        new Totals(0, 0, 0, 0),
        Map.of(),
        "e".repeat(64),
        Instant.parse("2026-10-19T10:00:00Z"));
  }

  private static Line line(Comparison comparison, int employee) {
    return comparison.lines().stream()
        .filter(l -> l.employeeId().equals(id(employee)))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void employeesAreMatchedByEmployeeIdAndClassifiedAsNewMissingChangedOrUnchanged() {
    Comparison compared =
        PayrollVariance.compare(
            List.of(input(1, 1, 600), input(2, 2, 480), input(3, 3, 300)),
            List.of(input(1, 11, 600), input(2, 12, 400), input(4, 14, 200)),
            DEFAULTS);

    assertThat(line(compared, 1).classification()).isEqualTo(Classification.UNCHANGED);
    assertThat(line(compared, 2).classification()).isEqualTo(Classification.CHANGED);
    assertThat(line(compared, 3).classification()).isEqualTo(Classification.NEW);
    assertThat(line(compared, 4).classification()).isEqualTo(Classification.MISSING);
    // The employment changed between the runs: one identity, both employments returned.
    assertThat(line(compared, 1).current().employmentIds()).containsExactly(id(1001));
    assertThat(line(compared, 1).comparison().employmentIds()).containsExactly(id(1011));
    assertThat(line(compared, 3).comparison()).isNull();
    assertThat(line(compared, 4).current()).isNull();
  }

  @Test
  void deltasAreSignedAndPercentagesAreIntegerBasisPointsOnlyWhenTheComparisonIsPositive() {
    Comparison compared =
        PayrollVariance.compare(
            List.of(input(1, 1, 480), input(2, 2, 300), input(3, 3, 333), input(5, 5, 100)),
            List.of(input(1, 1, 400), input(2, 2, 400), input(3, 3, 300), input(4, 4, 90)),
            DEFAULTS);

    assertThat(line(compared, 1).minuteDelta()).isEqualTo(80);
    assertThat(line(compared, 1).percentageDeltaBasisPoints()).isEqualTo(2000);
    assertThat(line(compared, 2).minuteDelta()).isEqualTo(-100);
    assertThat(line(compared, 2).percentageDeltaBasisPoints()).isEqualTo(-2500);
    // 33 / 300 = 11 % exactly; rounding is half up on the absolute value.
    assertThat(line(compared, 3).percentageDeltaBasisPoints()).isEqualTo(1100);
    assertThat(PayrollVariance.basisPoints(1, 3)).isEqualTo(3333);
    assertThat(PayrollVariance.basisPoints(2, 3)).isEqualTo(6667);
    assertThat(PayrollVariance.basisPoints(-2, 3)).isEqualTo(-6667);
    assertThat(PayrollVariance.basisPoints(1, 8)).isEqualTo(1250);
    assertThat(PayrollVariance.basisPoints(1, 16)).isEqualTo(625);
    assertThat(PayrollVariance.basisPoints(1, 32)).isEqualTo(313);
    // NEW: no comparison minutes, so no percentage; MISSING: minus 100 %.
    assertThat(line(compared, 5).minuteDelta()).isEqualTo(100);
    assertThat(line(compared, 5).percentageDeltaBasisPoints()).isNull();
    assertThat(line(compared, 4).minuteDelta()).isEqualTo(-90);
    assertThat(line(compared, 4).percentageDeltaBasisPoints()).isEqualTo(-10000);
    assertThat(PayrollVariance.basisPoints(10, 0)).isNull();
  }

  @Test
  void anEmployeesEmploymentsInOneRunAreAddedAndListedInOrderButNeverMergedAcrossRuns() {
    Comparison compared =
        PayrollVariance.compare(
            List.of(input(1, 9, 120), input(1, 2, 180)), List.of(input(1, 5, 250)), DEFAULTS);

    Line one = line(compared, 1);
    assertThat(compared.lines()).hasSize(1);
    assertThat(one.current().workedMinutes()).isEqualTo(300);
    assertThat(one.current().timesheets()).isEqualTo(4);
    assertThat(one.current().rows()).isEqualTo(6);
    assertThat(one.current().employmentIds()).containsExactly(id(1002), id(1009));
    assertThat(one.comparison().employmentIds()).containsExactly(id(1005));
    assertThat(one.minuteDelta()).isEqualTo(50);
  }

  @Test
  void materialityIsDecidedInExactIntegersAtItsBoundaries() {
    // Absolute boundary: 119 below the 120-minute threshold, 120 at it. 119 of 1000 is 11.9 %.
    assertThat(material(1000 + 119, 1000, DEFAULTS)).isFalse();
    assertThat(material(1000 + 120, 1000, DEFAULTS)).isTrue();
    // Percentage boundary: 20 % of 100 is 20 minutes exactly; 19 is below, 20 is at it.
    assertThat(material(100 + 19, 100, DEFAULTS)).isFalse();
    assertThat(material(100 + 20, 100, DEFAULTS)).isTrue();
    assertThat(material(100 - 20, 100, DEFAULTS)).isTrue();
    assertThat(material(100 - 19, 100, DEFAULTS)).isFalse();
    // 20 % of 7 minutes is 1.4: one minute is 14.28 %, two minutes is 28.57 % (no rounding tricks).
    assertThat(material(8, 7, DEFAULTS)).isFalse();
    assertThat(material(9, 7, DEFAULTS)).isTrue();
    // The percentage threshold of 100 needs the comparison to at least double or vanish.
    Thresholds hundred = new Thresholds(10_080, 100);
    assertThat(material(199, 100, hundred)).isFalse();
    assertThat(material(200, 100, hundred)).isTrue();
    // Unchanged is never material, whatever the thresholds.
    assertThat(material(100, 100, new Thresholds(0, 0))).isFalse();
  }

  @Test
  void zeroThresholdsMakeEveryDifferenceMaterialAndNothingElse() {
    Thresholds zero = new Thresholds(0, 0);
    assertThat(material(101, 100, zero)).isTrue();
    assertThat(material(99, 100, zero)).isTrue();
    assertThat(material(101, 100, new Thresholds(0, 100))).isTrue();
    assertThat(material(101, 100, new Thresholds(10_080, 0))).isTrue();
    assertThat(material(100, 100, zero)).isFalse();
  }

  @Test
  void newAndMissingEmployeesAreAlwaysMaterialEvenWithTheLargestThresholds() {
    Thresholds largest = new Thresholds(10_080, 100);
    Comparison compared =
        PayrollVariance.compare(
            List.of(input(1, 1, 1), input(2, 2, 5)),
            List.of(input(2, 2, 5), input(3, 3, 1)),
            largest);
    assertThat(line(compared, 1).material()).isTrue();
    assertThat(line(compared, 2).material()).isFalse();
    assertThat(line(compared, 3).material()).isTrue();
    assertThat(compared.totals().materialCount()).isEqualTo(2);
  }

  @Test
  void aChangedEmployeeWithZeroComparisonMinutesIsMaterialOnlyThroughTheAbsoluteThreshold() {
    // A comparison employee row with no minutes: the percentage rule does not apply.
    Comparison compared =
        PayrollVariance.compare(
            List.of(input(1, 1, 60)), List.of(input(1, 1, 0)), new Thresholds(61, 0));
    assertThat(line(compared, 1).classification()).isEqualTo(Classification.CHANGED);
    assertThat(line(compared, 1).percentageDeltaBasisPoints()).isNull();
    assertThat(line(compared, 1).material()).isFalse();
    Comparison reached =
        PayrollVariance.compare(
            List.of(input(1, 1, 60)), List.of(input(1, 1, 0)), new Thresholds(60, 100));
    assertThat(line(reached, 1).material()).isTrue();
  }

  @Test
  void linesAreOrderedMaterialFirstThenLargestAbsoluteDeltaThenEmployeeId() {
    Comparison compared =
        PayrollVariance.compare(
            List.of(
                input(7, 7, 100), // unchanged
                input(6, 6, 110), // +10 not material
                input(5, 5, 400), // +300 material
                input(3, 3, 100), // -200 material
                input(4, 4, 100), // -200 material (same delta, later id)
                input(9, 9, 15) // new, +15 material
                ),
            List.of(
                input(7, 7, 100),
                input(6, 6, 100),
                input(5, 5, 100),
                input(3, 3, 300),
                input(4, 4, 300),
                input(8, 8, 50)), // missing, -50 material
            DEFAULTS);

    assertThat(compared.lines().stream().map(Line::employeeId).toList())
        .containsExactly(id(5), id(3), id(4), id(8), id(9), id(6), id(7));
    assertThat(compared.lines().stream().map(Line::material).toList())
        .containsExactly(true, true, true, true, true, false, false);
  }

  @Test
  void afterFollowsReviewOrderExactlyFromAnyPosition() {
    Comparison compared =
        PayrollVariance.compare(
            List.of(input(5, 5, 400), input(3, 3, 100), input(4, 4, 100), input(6, 6, 110)),
            List.of(input(5, 5, 100), input(3, 3, 300), input(4, 4, 300), input(6, 6, 100)),
            DEFAULTS);
    List<Line> lines = compared.lines();
    for (int position = 0; position < lines.size(); position++) {
      Line at = lines.get(position);
      List<Line> following = new ArrayList<>();
      for (Line candidate : lines) {
        if (PayrollVariance.after(
            candidate, at.material(), Math.abs(at.minuteDelta()), at.employeeId())) {
          following.add(candidate);
        }
      }
      assertThat(following).isEqualTo(lines.subList(position + 1, lines.size()));
    }
  }

  @Test
  void totalsReconcileExactlyToTheLinesAndTheFiltersPartitionThem() {
    Comparison compared =
        PayrollVariance.compare(
            List.of(input(1, 1, 600), input(2, 2, 480), input(3, 3, 300), input(2, 22, 20)),
            List.of(input(1, 1, 600), input(2, 2, 400), input(4, 4, 200)),
            DEFAULTS);

    long current =
        compared.lines().stream()
            .mapToLong(l -> l.current() == null ? 0 : l.current().workedMinutes())
            .sum();
    long before =
        compared.lines().stream()
            .mapToLong(l -> l.comparison() == null ? 0 : l.comparison().workedMinutes())
            .sum();
    assertThat(compared.totals().currentMinutes()).isEqualTo(current).isEqualTo(1400);
    assertThat(compared.totals().comparisonMinutes()).isEqualTo(before).isEqualTo(1200);
    assertThat(compared.totals().minuteDelta())
        .isEqualTo(current - before)
        .isEqualTo(compared.lines().stream().mapToLong(Line::minuteDelta).sum());
    assertThat(compared.totals().currentEmployees()).isEqualTo(3);
    assertThat(compared.totals().comparisonEmployees()).isEqualTo(3);
    assertThat(compared.totals().counts())
        .containsEntry(Classification.NEW, 1)
        .containsEntry(Classification.MISSING, 1)
        .containsEntry(Classification.CHANGED, 1)
        .containsEntry(Classification.UNCHANGED, 1);
    assertThat(compared.totals().counts().values().stream().mapToInt(Integer::intValue).sum())
        .isEqualTo(compared.lines().size());
    assertThat(compared.totals().materialCount())
        .isEqualTo((int) compared.lines().stream().filter(Line::material).count());
    int partition = 0;
    for (View view : List.of(View.NEW, View.MISSING, View.CHANGED, View.UNCHANGED)) {
      partition += PayrollVariance.filtered(compared, view).size();
    }
    assertThat(partition).isEqualTo(compared.lines().size());
    assertThat(PayrollVariance.filtered(compared, View.ALL)).hasSameSizeAs(compared.lines());
    assertThat(PayrollVariance.filtered(compared, View.MATERIAL))
        .hasSize(compared.totals().materialCount());
  }

  @Test
  void anEmptyComparisonIsEmptyAndReconcilesToZero() {
    Comparison compared = PayrollVariance.compare(List.of(), List.of(), DEFAULTS);
    assertThat(compared.lines()).isEmpty();
    assertThat(compared.totals().minuteDelta()).isZero();
    assertThat(compared.totals().materialCount()).isZero();
  }

  @Test
  void aComparisonRunMustBeAnotherRunOfTheSameEntityEndingBeforeWithTheSameLength() {
    Run current = run(ENTITY, LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 18));
    Run previous = run(ENTITY, LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11));
    assertThat(PayrollVariance.days(current)).isEqualTo(7);
    assertThat(PayrollVariance.eligible(current, previous)).isTrue();
    assertThat(PayrollVariance.eligible(current, current)).isFalse();
    // Adjacent or earlier runs only: ending on the first day of the current period is refused.
    assertThat(
            PayrollVariance.eligible(
                current, run(ENTITY, LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 12))))
        .isFalse();
    assertThat(
            PayrollVariance.eligible(
                current, run(ENTITY, LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 19))))
        .isFalse();
    // Another length, even by one day.
    assertThat(
            PayrollVariance.eligible(
                current, run(ENTITY, LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 11))))
        .isFalse();
    // Another legal entity.
    assertThat(
            PayrollVariance.eligible(
                current, run(OTHER_ENTITY, LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11))))
        .isFalse();
    // A far earlier run of the same length is still eligible (the administrator may choose it).
    assertThat(
            PayrollVariance.eligible(
                current, run(ENTITY, LocalDate.of(2026, 2, 2), LocalDate.of(2026, 2, 8))))
        .isTrue();
  }

  @Test
  void thresholdsOutsideTheirBoundsAreRefused() {
    assertThat(new Thresholds(0, 0)).isNotNull();
    assertThat(new Thresholds(10_080, 100)).isNotNull();
    assertThatThrownBy(() -> new Thresholds(-1, 20)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Thresholds(10_081, 20))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Thresholds(120, -1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Thresholds(120, 101)).isInstanceOf(IllegalArgumentException.class);
  }

  /** One changed employee: {@code current} against {@code comparison} minutes. */
  private static boolean material(int current, int comparison, Thresholds thresholds) {
    Comparison compared =
        PayrollVariance.compare(
            List.of(input(1, 1, current)), List.of(input(1, 1, comparison)), thresholds);
    return line(compared, 1).material();
  }
}
