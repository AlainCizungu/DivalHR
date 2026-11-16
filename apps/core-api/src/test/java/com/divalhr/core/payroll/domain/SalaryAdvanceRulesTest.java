package com.divalhr.core.payroll.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.payroll.domain.SalaryAdvanceRules.Facts;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** MVP-120A: the closed employer-policy rules, with exact minor-unit arithmetic. */
class SalaryAdvanceRulesTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 11, 16);

  private static Facts ok() {
    return new Facts(true, true, true, 18, 6, true, true, 0, 2, 0, null, TODAY);
  }

  @Test
  void ceilingIsRoundedDownInWholeMinorUnits() {
    assertThat(SalaryAdvanceRules.ceiling(1_000_000L, 30, null)).isEqualTo(300_000L);
    assertThat(SalaryAdvanceRules.ceiling(101L, 33, null)).isEqualTo(33L); // 33.33 -> 33
    assertThat(SalaryAdvanceRules.ceiling(99L, 1, null)).isZero(); // 0.99 -> 0, never rounded up
    assertThat(SalaryAdvanceRules.ceiling(1_000_000_000L, 50, null)).isEqualTo(500_000_000L);
  }

  @Test
  void theAbsoluteMaximumCapsTheCeilingAndNeverRaisesIt() {
    assertThat(SalaryAdvanceRules.ceiling(1_000_000L, 30, 250_000L)).isEqualTo(250_000L);
    assertThat(SalaryAdvanceRules.ceiling(1_000_000L, 30, 900_000L)).isEqualTo(300_000L);
  }

  @Test
  void outOfBoundsInputsAreRefused() {
    assertThatThrownBy(() -> SalaryAdvanceRules.ceiling(0L, 30, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SalaryAdvanceRules.ceiling(1_000L, 0, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SalaryAdvanceRules.ceiling(1_000L, 51, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void tenureCountsCompletedCalendarMonths() {
    LocalDate start = LocalDate.of(2025, 5, 10);
    assertThat(SalaryAdvanceRules.tenureMonths(start, LocalDate.of(2026, 11, 9))).isEqualTo(17);
    assertThat(SalaryAdvanceRules.tenureMonths(start, LocalDate.of(2026, 11, 10))).isEqualTo(18);
    assertThat(SalaryAdvanceRules.tenureMonths(start, start)).isZero();
    assertThat(SalaryAdvanceRules.tenureMonths(start, start.minusDays(1))).isZero();
    // Month-end: started on the 31st, one month is not complete on 28 February.
    assertThat(
            SalaryAdvanceRules.tenureMonths(LocalDate.of(2025, 1, 31), LocalDate.of(2025, 2, 28)))
        .isZero();
  }

  @Test
  void amountBoundsAreInclusive() {
    assertThat(SalaryAdvanceRules.amountReason(10_000L, 10_000L, 250_000L)).isEmpty();
    assertThat(SalaryAdvanceRules.amountReason(250_000L, 10_000L, 250_000L)).isEmpty();
    assertThat(SalaryAdvanceRules.amountReason(9_999L, 10_000L, 250_000L))
        .contains(SalaryAdvanceReason.AMOUNT_OUT_OF_RANGE);
    assertThat(SalaryAdvanceRules.amountReason(250_001L, 10_000L, 250_000L))
        .contains(SalaryAdvanceReason.AMOUNT_OUT_OF_RANGE);
  }

  @Test
  void anEmployeeWhoFitsThePolicyHasNoReason() {
    assertThat(SalaryAdvanceRules.reasons(ok())).isEmpty();
  }

  @Test
  void tenureBoundary() {
    Facts exact = new Facts(true, true, true, 6, 6, true, true, 0, 2, 0, null, TODAY);
    Facts short1 = new Facts(true, true, true, 5, 6, true, true, 0, 2, 0, null, TODAY);
    assertThat(SalaryAdvanceRules.reasons(exact)).isEmpty();
    assertThat(SalaryAdvanceRules.reasons(short1))
        .containsExactly(SalaryAdvanceReason.TENURE_TOO_SHORT);
  }

  @Test
  void openLimitIsTheLesserOfThePolicyAndThree() {
    Facts atPolicy = new Facts(true, true, true, 18, 6, true, true, 2, 2, 0, null, TODAY);
    Facts belowPolicy = new Facts(true, true, true, 18, 6, true, true, 1, 2, 0, null, TODAY);
    assertThat(SalaryAdvanceRules.reasons(atPolicy))
        .containsExactly(SalaryAdvanceReason.OPEN_LIMIT_REACHED);
    assertThat(SalaryAdvanceRules.reasons(belowPolicy)).isEmpty();
  }

  @Test
  void waitingPeriodEndsOnItsLastDay() {
    Facts waiting =
        new Facts(true, true, true, 18, 6, true, true, 0, 2, 0, TODAY.plusDays(1), TODAY);
    Facts over = new Facts(true, true, true, 18, 6, true, true, 0, 2, 0, TODAY, TODAY);
    assertThat(SalaryAdvanceRules.reasons(waiting))
        .containsExactly(SalaryAdvanceReason.WAITING_PERIOD);
    assertThat(SalaryAdvanceRules.reasons(over)).isEmpty();
  }

  @Test
  void rollingLimitIsTen() {
    Facts nine = new Facts(true, true, true, 18, 6, true, true, 0, 2, 9, null, TODAY);
    Facts ten = new Facts(true, true, true, 18, 6, true, true, 0, 2, 10, null, TODAY);
    assertThat(SalaryAdvanceRules.reasons(nine)).isEmpty();
    assertThat(SalaryAdvanceRules.reasons(ten))
        .containsExactly(SalaryAdvanceReason.ROLLING_LIMIT_REACHED);
  }

  @Test
  void noPolicyAndNoReferenceAreReportedTogetherInStableOrder() {
    Facts none = new Facts(false, true, true, 18, 6, false, true, 0, 2, 0, null, TODAY);
    List<SalaryAdvanceReason> reasons = SalaryAdvanceRules.reasons(none);
    assertThat(reasons)
        .containsExactly(
            SalaryAdvanceReason.NO_ENABLED_POLICY, SalaryAdvanceReason.PAYROLL_REFERENCE_MISSING);
  }

  @Test
  void aCurrencyMismatchNeedsAReference() {
    Facts mismatch = new Facts(true, true, true, 18, 6, true, false, 0, 2, 0, null, TODAY);
    assertThat(SalaryAdvanceRules.reasons(mismatch))
        .containsExactly(SalaryAdvanceReason.CURRENCY_MISMATCH);
  }

  @Test
  void employmentAndPlacementReasons() {
    Facts noEmployment = new Facts(true, false, false, 0, 6, true, true, 0, 2, 0, null, TODAY);
    Facts otherEntity = new Facts(true, true, false, 18, 6, true, true, 0, 2, 0, null, TODAY);
    assertThat(SalaryAdvanceRules.reasons(noEmployment))
        .containsExactly(SalaryAdvanceReason.EMPLOYMENT_NOT_ACTIVE);
    assertThat(SalaryAdvanceRules.reasons(otherEntity))
        .containsExactly(SalaryAdvanceReason.LEGAL_ENTITY_MISMATCH);
  }
}
