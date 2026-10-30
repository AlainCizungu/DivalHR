package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.payroll.domain.PayrollPeriod;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * MVP-094A (D94A-5): a run needs a fully closed month within the prior 24 months of the business
 * date; a policy date lies from the first day of the month 24 months back to 366 days ahead.
 */
class PayrollPeriodTest {

  private static PayrollPeriod month(String text) {
    return PayrollPeriod.parse(text).orElseThrow();
  }

  private static LocalDate d(String text) {
    return LocalDate.parse(text);
  }

  @Test
  void onlyYyyyMmWithinTheSupportedYearsIsAPeriod() {
    assertThat(PayrollPeriod.parse("2026-05")).isPresent();
    assertThat(PayrollPeriod.parse("2000-01")).isPresent();
    assertThat(PayrollPeriod.parse("2999-12")).isPresent();
    for (String bad :
        new String[] {
          null,
          "",
          "2026-5",
          "2026-13",
          "2026-00",
          "2026-05-01",
          "26-05",
          "1999-12",
          "\u0662\u0660\u0662\u0666-\u0660\u0665",
          " 2026-05"
        }) {
      assertThat(PayrollPeriod.parse(bad)).as("%s", bad).isEmpty();
    }
  }

  @Test
  void aMonthKnowsItsDays() {
    assertThat(month("2024-02").days()).isEqualTo(29);
    assertThat(month("2025-02").days()).isEqualTo(28);
    assertThat(month("2026-03").first()).isEqualTo(d("2026-03-01"));
    assertThat(month("2026-03").last()).isEqualTo(d("2026-03-31"));
    assertThat(month("2026-03").text()).isEqualTo("2026-03");
  }

  @Test
  void aRunNeedsAFullyClosedMonth() {
    LocalDate today = d("2026-06-15");
    assertThat(month("2026-05").isRunnable(today)).isTrue();
    assertThat(month("2026-06").isRunnable(today)).isFalse();
    assertThat(month("2026-07").isRunnable(today)).isFalse();
    assertThat(month("2027-01").isRunnable(today)).isFalse();
    // The month ends today: not closed yet; the next day it is.
    assertThat(month("2026-06").isRunnable(d("2026-06-30"))).isFalse();
    assertThat(month("2026-06").isRunnable(d("2026-07-01"))).isTrue();
    // The first day of the next month closes the previous one.
    assertThat(month("2026-05").isRunnable(d("2026-06-01"))).isTrue();
  }

  @Test
  void theTwentyFourthMonthBackIsTheOldestAndTheTwentyFifthIsRefused() {
    LocalDate today = d("2026-06-15");
    assertThat(PayrollPeriod.earliestStart(today)).isEqualTo(d("2024-06-01"));
    assertThat(month("2024-06").isRunnable(today)).isTrue();
    assertThat(month("2024-05").isRunnable(today)).isFalse();
    assertThat(month("2020-01").isRunnable(today)).isFalse();
    // On the first of a month the window has already moved.
    assertThat(month("2024-07").isRunnable(d("2026-07-01"))).isTrue();
    assertThat(month("2024-06").isRunnable(d("2026-07-01"))).isFalse();
    assertThat(month("2024-02").isRunnable(d("2026-02-28"))).isFalse();
    assertThat(month("2024-02").isRunnable(d("2026-02-01"))).isTrue();
  }

  @Test
  void aPolicyDateLiesBetweenTheWindowStartAndTodayPlus366Days() {
    LocalDate today = d("2026-06-15");
    assertThat(PayrollPeriod.isPolicyDate(d("2024-06-01"), today)).isTrue();
    assertThat(PayrollPeriod.isPolicyDate(d("2024-05-31"), today)).isFalse();
    assertThat(PayrollPeriod.isPolicyDate(today, today)).isTrue();
    assertThat(PayrollPeriod.isPolicyDate(today.plusDays(366), today)).isTrue();
    assertThat(PayrollPeriod.isPolicyDate(today.plusDays(367), today)).isFalse();
    assertThat(PayrollPeriod.latestPolicyDate(today)).isEqualTo(today.plusDays(366));
  }
}
