package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.payroll.domain.CompensationCurrency;
import com.divalhr.core.payroll.domain.PayrollBlocker;
import com.divalhr.core.payroll.domain.PayrollCalculator;
import com.divalhr.core.payroll.domain.PayrollCalculator.Context;
import com.divalhr.core.payroll.domain.PayrollCalculator.Employee;
import com.divalhr.core.payroll.domain.PayrollCalculator.Employment;
import com.divalhr.core.payroll.domain.PayrollCalculator.Fact;
import com.divalhr.core.payroll.domain.PayrollCalculator.Line;
import com.divalhr.core.payroll.domain.PayrollCalculator.Placement;
import com.divalhr.core.payroll.domain.PayrollCalculator.Range;
import com.divalhr.core.payroll.domain.PayrollCalculator.SourceKind;
import com.divalhr.core.payroll.domain.PayrollExplanation;
import com.divalhr.core.payroll.domain.PayrollMoney;
import com.divalhr.core.payroll.domain.PayrollPeriod;
import com.divalhr.core.payroll.domain.PayrollProration;
import java.lang.reflect.RecordComponent;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * MVP-094A (D94A-4, ADR 0033 section 4): the pure contractual-gross calculation. Reference
 * vectors, exact boundaries, hire, separation and rehire without double counting, every blocker,
 * and the proof that no hours input exists.
 */
class PayrollCalculatorTest {

  private static final UUID ENTITY = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID OTHER = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID EMPLOYEE = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
  private static final String LONG_AGO = "2020-01-01";

  private static LocalDate d(String text) {
    return text == null ? null : LocalDate.parse(text);
  }

  private static Context context(
      String period, CompensationCurrency currency, PayrollProration method) {
    return new Context(ENTITY, PayrollPeriod.parse(period).orElseThrow(), currency, method);
  }

  private static Employment employed(String from, String to) {
    return new Employment(UUID.randomUUID(), 3, d(from), d(to));
  }

  private static Placement placed(UUID entity, String from, String to) {
    return new Placement(UUID.randomUUID(), entity, d(from), d(to));
  }

  private static Fact paid(CompensationCurrency currency, long minor, String from, String to) {
    return new Fact(UUID.randomUUID(), UUID.randomUUID(), 7, currency, minor, d(from), d(to));
  }

  private static Employee employee(
      List<Employment> employments, List<Placement> placements, List<Fact> facts) {
    return new Employee(EMPLOYEE, "E0001", employments, placements, facts);
  }

  /** Employed from a date, placed here and paid in one amount, all open-ended. */
  private static Employee hiredOn(String hired, CompensationCurrency currency, long minor) {
    return employee(
        List.of(employed(hired, null)),
        List.of(placed(ENTITY, LONG_AGO, null)),
        List.of(paid(currency, minor, LONG_AGO, null)));
  }

  private static Line calculate(Context context, Employee employee) {
    return PayrollCalculator.calculate(context, employee);
  }

  // ------------------------------------------------------------------------------------------
  // Reference vectors
  // ------------------------------------------------------------------------------------------

  @Test
  void aFullMonthIsTheExactMonthlyAmountWithoutDivision() {
    for (String period : List.of("2026-03", "2026-04", "2026-02", "2024-02")) {
      Line usd =
          calculate(
              context(period, CompensationCurrency.USD, PayrollProration.CALENDAR_DAYS),
              hiredOn(LONG_AGO, CompensationCurrency.USD, 125_000));
      assertThat(usd.fullMonth()).as(period).isTrue();
      assertThat(usd.denominator()).as(period).isNull();
      assertThat(usd.grossMinor()).as(period).isEqualTo(125_000L);
      assertThat(usd.monthlyMinor()).isEqualTo(125_000L);
      assertThat(usd.explanations()).containsExactly(PayrollExplanation.FULL_MONTH);
      assertThat(usd.ranges()).hasSize(1);
      assertThat(PayrollMoney.text(CompensationCurrency.USD, usd.grossMinor())).isEqualTo("1250.00");
    }
    Line cdf =
        calculate(
            context("2026-04", CompensationCurrency.CDF, PayrollProration.FIXED_30_DAYS),
            hiredOn(LONG_AGO, CompensationCurrency.CDF, 450_000));
    assertThat(cdf.grossMinor()).isEqualTo(450_000L);
    assertThat(cdf.denominator()).isNull();
    assertThat(cdf.explanations()).containsExactly(PayrollExplanation.FULL_MONTH);
  }

  @Test
  void february2024With15DaysOfCdf450000Is232759() {
    // 15 to 29 February 2024: 15 days of 29; 450000 x 15 / 29 = 232758.62.
    Line line =
        calculate(
            context("2024-02", CompensationCurrency.CDF, PayrollProration.CALENDAR_DAYS),
            hiredOn("2024-02-15", CompensationCurrency.CDF, 450_000));
    assertThat(line.eligibleDays()).isEqualTo(15);
    assertThat(line.denominator()).isEqualTo(29);
    assertThat(line.fullMonth()).isFalse();
    assertThat(line.grossMinor()).isEqualTo(232_759L);
    assertThat(PayrollMoney.text(CompensationCurrency.CDF, line.grossMinor())).isEqualTo("232759");
    assertThat(line.explanations())
        .containsExactly(
            PayrollExplanation.PRORATED_CALENDAR_DAYS, PayrollExplanation.STARTED_IN_MONTH);
  }

  @Test
  void february2025With14DaysOfUsd1250Is625() {
    Line line =
        calculate(
            context("2025-02", CompensationCurrency.USD, PayrollProration.CALENDAR_DAYS),
            hiredOn("2025-02-15", CompensationCurrency.USD, 125_000));
    assertThat(line.eligibleDays()).isEqualTo(14);
    assertThat(line.denominator()).isEqualTo(28);
    assertThat(line.grossMinor()).isEqualTo(62_500L);
    assertThat(PayrollMoney.text(CompensationCurrency.USD, line.grossMinor())).isEqualTo("625.00");
  }

  @Test
  void march2026With10DaysOfUsd1250Is403Point23() {
    Line line =
        calculate(
            context("2026-03", CompensationCurrency.USD, PayrollProration.CALENDAR_DAYS),
            hiredOn("2026-03-22", CompensationCurrency.USD, 125_000));
    assertThat(line.eligibleDays()).isEqualTo(10);
    assertThat(line.denominator()).isEqualTo(31);
    // 125000 x 10 / 31 = 40322.58 => 40323 minor.
    assertThat(line.grossMinor()).isEqualTo(40_323L);
    assertThat(PayrollMoney.text(CompensationCurrency.USD, line.grossMinor())).isEqualTo("403.23");
  }

  @Test
  void fixed30DaysWith10DaysOfUsd1250Is416Point67() {
    Line line =
        calculate(
            context("2026-03", CompensationCurrency.USD, PayrollProration.FIXED_30_DAYS),
            hiredOn("2026-03-22", CompensationCurrency.USD, 125_000));
    assertThat(line.eligibleDays()).isEqualTo(10);
    assertThat(line.denominator()).isEqualTo(30);
    // 125000 x 10 / 30 = 41666.67 => 41667 minor.
    assertThat(line.grossMinor()).isEqualTo(41_667L);
    assertThat(PayrollMoney.text(CompensationCurrency.USD, line.grossMinor())).isEqualTo("416.67");
    assertThat(line.explanations())
        .containsExactly(
            PayrollExplanation.PRORATED_FIXED_30_DAYS, PayrollExplanation.STARTED_IN_MONTH);
  }

  @Test
  void fixed30DaysWith30DaysInA31DayMonthIsCappedAtTheFullAmount() {
    Line thirty =
        calculate(
            context("2026-03", CompensationCurrency.CDF, PayrollProration.FIXED_30_DAYS),
            hiredOn("2026-03-02", CompensationCurrency.CDF, 450_000));
    assertThat(thirty.eligibleDays()).isEqualTo(30);
    assertThat(thirty.fullMonth()).isFalse();
    assertThat(thirty.denominator()).isEqualTo(30);
    assertThat(thirty.grossMinor()).isEqualTo(450_000L);
    assertThat(thirty.explanations())
        .containsExactly(
            PayrollExplanation.PRORATED_FIXED_30_DAYS,
            PayrollExplanation.CAPPED_AT_FULL_MONTH,
            PayrollExplanation.STARTED_IN_MONTH);
    Line thirtyOne =
        calculate(
            context("2026-03", CompensationCurrency.CDF, PayrollProration.FIXED_30_DAYS),
            hiredOn("2026-03-01", CompensationCurrency.CDF, 450_000));
    assertThat(thirtyOne.eligibleDays()).isEqualTo(31);
    assertThat(thirtyOne.fullMonth()).isTrue();
    assertThat(thirtyOne.denominator()).isNull();
    assertThat(thirtyOne.grossMinor()).isEqualTo(450_000L);
    assertThat(thirtyOne.explanations()).containsExactly(PayrollExplanation.FULL_MONTH);
  }

  @Test
  void aHalfUpTieRoundsUp() {
    // April has 30 days; one day of 15 minor units is 0.5 => 1 (USD 0.01).
    Line line =
        calculate(
            context("2026-04", CompensationCurrency.USD, PayrollProration.CALENDAR_DAYS),
            hiredOn("2026-04-30", CompensationCurrency.USD, 15));
    assertThat(line.eligibleDays()).isEqualTo(1);
    assertThat(line.grossMinor()).isEqualTo(1L);
    assertThat(PayrollMoney.text(CompensationCurrency.USD, line.grossMinor())).isEqualTo("0.01");
    // Just below the tie rounds down; a tie of 1.5 rounds to 2.
    assertThat(PayrollCalculator.gross(PayrollProration.CALENDAR_DAYS, 14, 1, 30)).isZero();
    assertThat(PayrollCalculator.gross(PayrollProration.CALENDAR_DAYS, 45, 1, 30)).isEqualTo(2L);
  }

  @Test
  void roundHalfUpIsFloorOfTwoNPlusDOverTwoD() {
    assertThat(PayrollCalculator.roundHalfUp(BigInteger.valueOf(15), BigInteger.valueOf(30)))
        .isEqualTo(BigInteger.ONE);
    assertThat(PayrollCalculator.roundHalfUp(BigInteger.ZERO, BigInteger.valueOf(31)))
        .isEqualTo(BigInteger.ZERO);
    assertThat(PayrollCalculator.roundHalfUp(BigInteger.ONE, BigInteger.valueOf(3)))
        .isEqualTo(BigInteger.ZERO);
    assertThat(PayrollCalculator.roundHalfUp(BigInteger.TWO, BigInteger.valueOf(3)))
        .isEqualTo(BigInteger.ONE);
    BigInteger big = new BigInteger("1000000000000000000000000001");
    assertThat(PayrollCalculator.roundHalfUp(big, BigInteger.TWO))
        .isEqualTo(new BigInteger("500000000000000000000000001"));
    assertThatThrownBy(() -> PayrollCalculator.roundHalfUp(BigInteger.valueOf(-1), BigInteger.TEN))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PayrollCalculator.roundHalfUp(BigInteger.ONE, BigInteger.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theLargestMonthlyAmountsStayExact() {
    // 1,000,000,000 francs x 30 days, and 10,000,000 cents x 1 day, never overflow or round off.
    assertThat(PayrollCalculator.gross(PayrollProration.CALENDAR_DAYS, 1_000_000_000L, 30, 31))
        .isEqualTo(967_741_935L);
    assertThat(PayrollCalculator.gross(PayrollProration.FIXED_30_DAYS, 10_000_000L, 1, 31))
        .isEqualTo(333_333L);
    assertThat(PayrollCalculator.gross(PayrollProration.FIXED_30_DAYS, 10_000_000L, 31, 31))
        .isEqualTo(10_000_000L);
  }

  @Test
  void grossRefusesDaysOutsideTheMonthAndNoneWithAPartialMonth() {
    assertThatThrownBy(() -> PayrollCalculator.gross(PayrollProration.CALENDAR_DAYS, 100, 0, 31))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PayrollCalculator.gross(PayrollProration.CALENDAR_DAYS, 100, 32, 31))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PayrollCalculator.gross(PayrollProration.NONE, 100, 30, 31))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(PayrollCalculator.gross(PayrollProration.NONE, 100, 31, 31)).isEqualTo(100L);
  }

  // ------------------------------------------------------------------------------------------
  // Leap February and month boundaries
  // ------------------------------------------------------------------------------------------

  @Test
  void leapDayIsADayOfFebruary2024ButNotOf2025() {
    Line oneDay =
        calculate(
            context("2024-02", CompensationCurrency.CDF, PayrollProration.CALENDAR_DAYS),
            hiredOn("2024-02-29", CompensationCurrency.CDF, 450_000));
    assertThat(oneDay.eligibleDays()).isEqualTo(1);
    assertThat(oneDay.denominator()).isEqualTo(29);
    // 450000 / 29 = 15517.24 => 15517.
    assertThat(oneDay.grossMinor()).isEqualTo(15_517L);
    Line fullLeap =
        calculate(
            context("2024-02", CompensationCurrency.CDF, PayrollProration.CALENDAR_DAYS),
            hiredOn(LONG_AGO, CompensationCurrency.CDF, 450_000));
    assertThat(fullLeap.eligibleDays()).isEqualTo(29);
    assertThat(fullLeap.fullMonth()).isTrue();
    Line fullCommon =
        calculate(
            context("2025-02", CompensationCurrency.CDF, PayrollProration.CALENDAR_DAYS),
            hiredOn(LONG_AGO, CompensationCurrency.CDF, 450_000));
    assertThat(fullCommon.eligibleDays()).isEqualTo(28);
    assertThat(fullCommon.fullMonth()).isTrue();
  }

  @Test
  void anEmploymentStartingAfterTheMonthOrEndingBeforeItContributesNothing() {
    Employee later =
        employee(
            List.of(employed("2026-04-01", null)),
            List.of(placed(ENTITY, LONG_AGO, null)),
            List.of(paid(CompensationCurrency.USD, 125_000, LONG_AGO, null)));
    Line none =
        calculate(
            context("2026-03", CompensationCurrency.USD, PayrollProration.CALENDAR_DAYS), later);
    assertThat(none.eligibleDays()).isZero();
    assertThat(none.blockers()).isEmpty();
    assertThat(none.hasGross()).isFalse();
    assertThat(none.ranges()).isEmpty();
    Employee earlier =
        employee(
            List.of(employed(LONG_AGO, "2026-02-28")),
            List.of(placed(ENTITY, LONG_AGO, null)),
            List.of(paid(CompensationCurrency.USD, 125_000, LONG_AGO, null)));
    Line gone =
        calculate(
            context("2026-03", CompensationCurrency.USD, PayrollProration.CALENDAR_DAYS), earlier);
    assertThat(gone.eligibleDays()).isZero();
    assertThat(gone.hasGross()).isFalse();
  }

  // ------------------------------------------------------------------------------------------
  // Hire, separation, rehire
  // ------------------------------------------------------------------------------------------

  @Test
  void aSeparationInsideTheMonthEndsTheEligibleDays() {
    Employee leaver =
        employee(
            List.of(employed(LONG_AGO, "2026-03-10")),
            List.of(placed(ENTITY, LONG_AGO, null)),
            List.of(paid(CompensationCurrency.USD, 125_000, LONG_AGO, null)));
    Line line =
        calculate(
            context("2026-03", CompensationCurrency.USD, PayrollProration.CALENDAR_DAYS), leaver);
    assertThat(line.eligibleDays()).isEqualTo(10);
    assertThat(line.ranges()).containsExactly(new Range(d("2026-03-01"), d("2026-03-10")));
    assertThat(line.grossMinor()).isEqualTo(40_323L);
    assertThat(line.explanations())
        .containsExactly(
            PayrollExplanation.PRORATED_CALENDAR_DAYS, PayrollExplanation.ENDED_IN_MONTH);
  }

  @Test
  void aSeparationOnTheLastDayOfTheMonthIsAFullMonth() {
    Employee leaver =
        employee(
            List.of(employed(LONG_AGO, "2026-03-31")),
            List.of(placed(ENTITY, LONG_AGO, null)),
            List.of(paid(CompensationCurrency.USD, 125_000, LONG_AGO, null)));
    Line line =
        calculate(
            context("2026-03", CompensationCurrency.USD, PayrollProration.CALENDAR_DAYS), leaver);
    assertThat(line.fullMonth()).isTrue();
    assertThat(line.explanations()).containsExactly(PayrollExplanation.FULL_MONTH);
  }

  @Test
  void aRehireHasTwoRangesAndCountsEachDayOnce() {
    Employee rehired =
        employee(
            List.of(employed("2026-01-01", "2026-03-05"), employed("2026-03-20", null)),
            List.of(placed(ENTITY, LONG_AGO, null)),
            List.of(paid(CompensationCurrency.USD, 125_000, LONG_AGO, null)));
    Line line =
        calculate(
            context("2026-03", CompensationCurrency.USD, PayrollProration.CALENDAR_DAYS),
            rehired);
    // 1 to 5 March (5 days) and 20 to 31 March (12 days).
    assertThat(line.eligibleDays()).isEqualTo(17);
    assertThat(line.ranges())
        .containsExactly(
            new Range(d("2026-03-01"), d("2026-03-05")),
            new Range(d("2026-03-20"), d("2026-03-31")));
    assertThat(line.blockers()).isEmpty();
    // 125000 x 17 / 31 = 68548.39 => 68548.
    assertThat(line.grossMinor()).isEqualTo(68_548L);
    assertThat(line.explanations())
        .containsExactly(
            PayrollExplanation.PRORATED_CALENDAR_DAYS,
            PayrollExplanation.STARTED_IN_MONTH,
            PayrollExplanation.ENDED_IN_MONTH,
            PayrollExplanation.MULTIPLE_PERIODS);
    assertThat(line.sources())
        .extracting(PayrollCalculator.Source::kind)
        .containsExactly(
            SourceKind.EMPLOYMENT,
            SourceKind.EMPLOYMENT,
            SourceKind.PLACEMENT,
            SourceKind.COMPENSATION);
  }

  @Test
  void overlappingEmploymentsNeverCountADayTwiceAndAreABlocker() {
    Employee overlapping =
        employee(
            List.of(employed(LONG_AGO, "2026-03-20"), employed("2026-03-10", null)),
            List.of(placed(ENTITY, LONG_AGO, null)),
            List.of(paid(CompensationCurrency.USD, 125_000, LONG_AGO, null)));
    Line line =
        calculate(
            context("2026-03", CompensationCurrency.USD, PayrollProration.CALENDAR_DAYS),
            overlapping);
    assertThat(line.eligibleDays()).isEqualTo(31);
    assertThat(line.blockers()).containsExactly(PayrollBlocker.OVERLAPPING_EVIDENCE);
    assertThat(line.hasGross()).isFalse();
    assertThat(line.grossMinor()).isNull();
    assertThat(line.monthlyMinor()).isNull();
    assertThat(line.denominator()).isNull();
  }

  @Test
  void aCompensationChangeOnTheSameAmountIsNotAnInconsistency() {
    Employee split =
        employee(
            List.of(employed(LONG_AGO, null)),
            List.of(placed(ENTITY, LONG_AGO, null)),
            List.of(
                paid(CompensationCurrency.USD, 125_000, LONG_AGO, "2026-03-15"),
                paid(CompensationCurrency.USD, 125_000, "2026-03-16", null)));
    Line line =
        calculate(
            context("2026-03", CompensationCurrency.USD, PayrollProration.CALENDAR_DAYS), split);
    assertThat(line.blockers()).isEmpty();
    assertThat(line.fullMonth()).isTrue();
    assertThat(line.grossMinor()).isEqualTo(125_000L);
  }

  // ------------------------------------------------------------------------------------------
  // Blockers
  // ------------------------------------------------------------------------------------------

  @Test
  void aGapOrNoCompensationIsMissing() {
    Employee gap =
        employee(
            List.of(employed(LONG_AGO, null)),
            List.of(placed(ENTITY, LONG_AGO, null)),
            List.of(paid(CompensationCurrency.USD, 125_000, "2026-03-11", null)));
    Line gapped =
        calculate(
            context("2026-03", CompensationCurrency.USD, PayrollProration.CALENDAR_DAYS), gap);
    assertThat(gapped.blockers()).containsExactly(PayrollBlocker.COMPENSATION_MISSING);
    assertThat(gapped.eligibleDays()).isEqualTo(21);
    assertThat(gapped.hasGross()).isFalse();
    Employee none =
        employee(
            List.of(employed(LONG_AGO, null)), List.of(placed(ENTITY, LONG_AGO, null)), List.of());
    Line missing =
        calculate(
            context("2026-03", CompensationCurrency.USD, PayrollProration.CALENDAR_DAYS), none);
    assertThat(missing.blockers()).containsExactly(PayrollBlocker.COMPENSATION_MISSING);
    assertThat(missing.eligibleDays()).isZero();
  }

  @Test
  void compensationInAnotherCurrencyIsAMismatchNotMissing() {
    Line line =
        calculate(
            context("2026-03", CompensationCurrency.USD, PayrollProration.CALENDAR_DAYS),
            hiredOn(LONG_AGO, CompensationCurrency.CDF, 450_000));
    assertThat(line.blockers()).containsExactly(PayrollBlocker.CURRENCY_MISMATCH);
    assertThat(line.hasGross()).isFalse();
    // A CDF run on the same employee is fine.
    Line cdf =
        calculate(
            context("2026-03", CompensationCurrency.CDF, PayrollProration.CALENDAR_DAYS),
            hiredOn(LONG_AGO, CompensationCurrency.CDF, 450_000));
    assertThat(cdf.blockers()).isEmpty();
  }

  @Test
  void aMixedCurrencyMonthReportsMismatchForTheOtherCurrencyDays() {
    Employee mixed =
        employee(
            List.of(employed(LONG_AGO, null)),
            List.of(placed(ENTITY, LONG_AGO, null)),
            List.of(
                paid(CompensationCurrency.CDF, 450_000, LONG_AGO, "2026-03-15"),
                paid(CompensationCurrency.USD, 125_000, "2026-03-16", null)));
    Line usd =
        calculate(
            context("2026-03", CompensationCurrency.USD, PayrollProration.CALENDAR_DAYS), mixed);
    assertThat(usd.blockers()).containsExactly(PayrollBlocker.CURRENCY_MISMATCH);
    assertThat(usd.eligibleDays()).isEqualTo(16);
  }

  @Test
  void aPlacementInAnotherLegalEntityInsideTheMonthIsForeign() {
    Employee transferred =
        employee(
            List.of(employed(LONG_AGO, null)),
            List.of(placed(ENTITY, LONG_AGO, "2026-03-15"), placed(OTHER, "2026-03-16", null)),
            List.of(paid(CompensationCurrency.USD, 125_000, LONG_AGO, null)));
    Line line =
        calculate(
            context("2026-03", CompensationCurrency.USD, PayrollProration.CALENDAR_DAYS),
            transferred);
    assertThat(line.blockers()).containsExactly(PayrollBlocker.FOREIGN_PLACEMENT);
    assertThat(line.eligibleDays()).isEqualTo(15);
    assertThat(line.hasGross()).isFalse();
  }

  @Test
  void overlappingPlacementsAndOverlappingFactsAreInconsistentEvidence() {
    Employee placements =
        employee(
            List.of(employed(LONG_AGO, null)),
            List.of(placed(ENTITY, LONG_AGO, null), placed(ENTITY, "2026-03-10", null)),
            List.of(paid(CompensationCurrency.USD, 125_000, LONG_AGO, null)));
    assertThat(
            calculate(
                    context("2026-03", CompensationCurrency.USD, PayrollProration.CALENDAR_DAYS),
                    placements)
                .blockers())
        .containsExactly(PayrollBlocker.OVERLAPPING_EVIDENCE);
    Employee facts =
        employee(
            List.of(employed(LONG_AGO, null)),
            List.of(placed(ENTITY, LONG_AGO, null)),
            List.of(
                paid(CompensationCurrency.USD, 125_000, LONG_AGO, null),
                paid(CompensationCurrency.USD, 125_000, "2026-03-10", "2026-03-20")));
    assertThat(
            calculate(
                    context("2026-03", CompensationCurrency.USD, PayrollProration.CALENDAR_DAYS),
                    facts)
                .blockers())
        .containsExactly(PayrollBlocker.OVERLAPPING_EVIDENCE);
  }

  @Test
  void aMonthlyAmountThatChangesInsideTheMonthIsABlocker() {
    Employee raised =
        employee(
            List.of(employed(LONG_AGO, null)),
            List.of(placed(ENTITY, LONG_AGO, null)),
            List.of(
                paid(CompensationCurrency.USD, 125_000, LONG_AGO, "2026-03-15"),
                paid(CompensationCurrency.USD, 150_000, "2026-03-16", null)));
    Line line =
        calculate(
            context("2026-03", CompensationCurrency.USD, PayrollProration.CALENDAR_DAYS), raised);
    assertThat(line.blockers()).containsExactly(PayrollBlocker.MULTIPLE_MONTHLY_AMOUNTS);
    assertThat(line.hasGross()).isFalse();
    // The same change effective on the first of the next month is no blocker for March.
    Employee aligned =
        employee(
            List.of(employed(LONG_AGO, null)),
            List.of(placed(ENTITY, LONG_AGO, null)),
            List.of(
                paid(CompensationCurrency.USD, 125_000, LONG_AGO, "2026-03-31"),
                paid(CompensationCurrency.USD, 150_000, "2026-04-01", null)));
    Line march =
        calculate(
            context("2026-03", CompensationCurrency.USD, PayrollProration.CALENDAR_DAYS),
            aligned);
    assertThat(march.blockers()).isEmpty();
    assertThat(march.grossMinor()).isEqualTo(125_000L);
    Line april =
        calculate(
            context("2026-04", CompensationCurrency.USD, PayrollProration.CALENDAR_DAYS),
            aligned);
    assertThat(april.grossMinor()).isEqualTo(150_000L);
  }

  @Test
  void methodNoneRefusesAPartialMonthButPaysAFullOne() {
    Line partial =
        calculate(
            context("2026-03", CompensationCurrency.USD, PayrollProration.NONE),
            hiredOn("2026-03-22", CompensationCurrency.USD, 125_000));
    assertThat(partial.blockers()).containsExactly(PayrollBlocker.PARTIAL_MONTH_NOT_ALLOWED);
    assertThat(partial.hasGross()).isFalse();
    assertThat(partial.eligibleDays()).isEqualTo(10);
    Line full =
        calculate(
            context("2026-03", CompensationCurrency.USD, PayrollProration.NONE),
            hiredOn(LONG_AGO, CompensationCurrency.USD, 125_000));
    assertThat(full.blockers()).isEmpty();
    assertThat(full.grossMinor()).isEqualTo(125_000L);
    assertThat(full.explanations()).containsExactly(PayrollExplanation.FULL_MONTH);
  }

  @Test
  void everyBlockerIsReportedTogetherInADeclaredOrder() {
    Employee tangled =
        employee(
            List.of(employed(LONG_AGO, null)),
            List.of(placed(ENTITY, LONG_AGO, "2026-03-20"), placed(OTHER, "2026-03-15", null)),
            List.of(paid(CompensationCurrency.CDF, 450_000, "2026-03-05", "2026-03-10")));
    Line line =
        calculate(
            context("2026-03", CompensationCurrency.USD, PayrollProration.NONE), tangled);
    assertThat(line.blockers())
        .containsExactly(
            PayrollBlocker.COMPENSATION_MISSING,
            PayrollBlocker.CURRENCY_MISMATCH,
            PayrollBlocker.FOREIGN_PLACEMENT,
            PayrollBlocker.OVERLAPPING_EVIDENCE);
  }

  // ------------------------------------------------------------------------------------------
  // Hours never scale a monthly salary
  // ------------------------------------------------------------------------------------------

  @Test
  void theCalculationHasNoHoursInputAtAll() {
    for (Class<?> input :
        List.of(Employee.class, Employment.class, Placement.class, Fact.class, Context.class)) {
      for (RecordComponent component : input.getRecordComponents()) {
        String name = component.getName().toLowerCase(Locale.ROOT);
        assertThat(name).as(input.getSimpleName()).doesNotContain("hour", "minute", "worked");
      }
    }
    assertThat(
            java.util.Arrays.stream(PayrollCalculator.class.getDeclaredMethods())
                .flatMap(method -> java.util.Arrays.stream(method.getParameters()))
                .map(parameter -> parameter.getName().toLowerCase(Locale.ROOT)))
        .noneMatch(name -> name.contains("hour") || name.contains("worked"));
  }

  @Test
  void theSameSourcesGiveTheSameGrossWhateverElseHappened() {
    Employee employee = hiredOn("2026-03-22", CompensationCurrency.USD, 125_000);
    Context context = context("2026-03", CompensationCurrency.USD, PayrollProration.CALENDAR_DAYS);
    Line first = calculate(context, employee);
    Line second = calculate(context, employee);
    assertThat(second.grossMinor()).isEqualTo(first.grossMinor());
    assertThat(second.ranges()).isEqualTo(first.ranges());
    assertThat(second.explanations()).isEqualTo(first.explanations());
  }

  // ------------------------------------------------------------------------------------------
  // Evidence and privacy
  // ------------------------------------------------------------------------------------------

  @Test
  void aLineRecordsTheSourcesThatFedItAndNothingElse() {
    Employment current = employed("2026-03-22", null);
    Employment old = employed("2020-01-01", "2026-01-31");
    Placement here = placed(ENTITY, LONG_AGO, null);
    Fact fact = paid(CompensationCurrency.USD, 125_000, LONG_AGO, null);
    Fact other = paid(CompensationCurrency.CDF, 450_000, "2019-01-01", "2019-12-31");
    Line line =
        calculate(
            context("2026-03", CompensationCurrency.USD, PayrollProration.CALENDAR_DAYS),
            employee(List.of(old, current), List.of(here), List.of(fact, other)));
    assertThat(line.sources()).hasSize(3);
    assertThat(line.sources())
        .extracting(PayrollCalculator.Source::id)
        .containsExactly(current.id(), here.id(), fact.id());
    assertThat(line.sources().get(0).version()).isEqualTo(3L);
    assertThat(line.sources().get(2).version()).isEqualTo(7L);
  }

  @Test
  void noToStringPrintsAnAmountOrANumber() {
    Line line =
        calculate(
            context("2026-03", CompensationCurrency.USD, PayrollProration.CALENDAR_DAYS),
            hiredOn(LONG_AGO, CompensationCurrency.USD, 987_654));
    assertThat(line.toString()).doesNotContain("987654").doesNotContain("E0001");
    assertThat(employee(List.of(), List.of(), List.of()).toString()).doesNotContain("E0001");
    assertThat(paid(CompensationCurrency.USD, 987_654, LONG_AGO, null).toString())
        .doesNotContain("987654");
  }

  @Test
  void moneyTextIsCanonicalAndMayBeZero() {
    assertThat(PayrollMoney.text(CompensationCurrency.USD, 0)).isEqualTo("0.00");
    assertThat(PayrollMoney.text(CompensationCurrency.USD, 5)).isEqualTo("0.05");
    assertThat(PayrollMoney.text(CompensationCurrency.USD, 10_000_000)).isEqualTo("100000.00");
    assertThat(PayrollMoney.text(CompensationCurrency.CDF, 0)).isEqualTo("0");
    assertThat(PayrollMoney.text(CompensationCurrency.CDF, 5_000_000_000_000L))
        .isEqualTo("5000000000000");
    assertThatThrownBy(() -> PayrollMoney.text(CompensationCurrency.CDF, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
