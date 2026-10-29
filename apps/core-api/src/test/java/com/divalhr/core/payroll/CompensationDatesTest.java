package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.payroll.domain.CompensationDates;
import com.divalhr.core.payroll.domain.CompensationDates.Span;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * MVP-093A (D93-5): every exact boundary of the effective-date window, the retroactive flag, the
 * reach of a correction and the containment of a start inside an employment interval.
 */
class CompensationDatesTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);

  @Test
  void theWindowAcceptsTheExactBoundariesAndRefusesOneDayBeyond() {
    assertThat(CompensationDates.withinWindow(TODAY.minusDays(60), TODAY)).isTrue();
    assertThat(CompensationDates.withinWindow(TODAY.minusDays(61), TODAY)).isFalse();
    assertThat(CompensationDates.withinWindow(TODAY.plusDays(366), TODAY)).isTrue();
    assertThat(CompensationDates.withinWindow(TODAY.plusDays(367), TODAY)).isFalse();
    assertThat(CompensationDates.withinWindow(TODAY, TODAY)).isTrue();
    assertThat(CompensationDates.earliest(TODAY)).isEqualTo(LocalDate.of(2026, 8, 16));
    assertThat(CompensationDates.latest(TODAY)).isEqualTo(LocalDate.of(2027, 10, 16));
  }

  @Test
  void theWindowCrossesAYearAndALeapDayExactly() {
    LocalDate leap = LocalDate.of(2028, 3, 1);
    assertThat(CompensationDates.withinWindow(LocalDate.of(2028, 1, 1), leap)).isTrue();
    assertThat(CompensationDates.withinWindow(LocalDate.of(2027, 12, 31), leap)).isFalse();
    assertThat(CompensationDates.withinWindow(leap.plusDays(366), leap)).isTrue();
    assertThat(CompensationDates.withinWindow(leap.plusDays(367), leap)).isFalse();
    assertThat(CompensationDates.earliest(leap)).isEqualTo(LocalDate.of(2028, 1, 1));
  }

  @Test
  void aDateBeforeTodayIsRetroactiveAndTodayIsNot() {
    assertThat(CompensationDates.retroactive(TODAY.minusDays(1), TODAY)).isTrue();
    assertThat(CompensationDates.retroactive(TODAY, TODAY)).isFalse();
    assertThat(CompensationDates.retroactive(TODAY.plusDays(1), TODAY)).isFalse();
  }

  @Test
  void aCorrectionsReachIsTheEarlierOfTheOldAndTheNewStart() {
    LocalDate limit = TODAY.minusDays(60);
    assertThat(CompensationDates.reachWithinWindow(limit, limit.plusDays(10), TODAY)).isTrue();
    assertThat(CompensationDates.reachWithinWindow(limit.minusDays(1), limit.plusDays(10), TODAY))
        .isFalse();
    assertThat(CompensationDates.reachWithinWindow(limit.plusDays(10), limit, TODAY)).isTrue();
    assertThat(CompensationDates.reachWithinWindow(limit.plusDays(10), limit.minusDays(1), TODAY))
        .isFalse();
    assertThat(CompensationDates.reachWithinWindow(TODAY, TODAY.plusDays(200), TODAY)).isTrue();
  }

  @Test
  void aStartMustLieInsideSomeEmploymentIntervalIncludingBothEnds() {
    Span first =
        new Span(UUID.randomUUID(), 3, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));
    Span second = new Span(UUID.randomUUID(), 1, LocalDate.of(2026, 3, 1), null);
    List<Span> spans = List.of(first, second);
    assertThat(CompensationDates.withinEmployment(LocalDate.of(2025, 1, 1), spans)).isTrue();
    assertThat(CompensationDates.withinEmployment(LocalDate.of(2025, 12, 31), spans)).isTrue();
    assertThat(CompensationDates.withinEmployment(LocalDate.of(2024, 12, 31), spans)).isFalse();
    assertThat(CompensationDates.withinEmployment(LocalDate.of(2026, 1, 1), spans)).isFalse();
    assertThat(CompensationDates.withinEmployment(LocalDate.of(2026, 2, 28), spans)).isFalse();
    assertThat(CompensationDates.withinEmployment(LocalDate.of(2026, 3, 1), spans)).isTrue();
    assertThat(CompensationDates.withinEmployment(LocalDate.of(2099, 1, 1), spans)).isTrue();
    assertThat(CompensationDates.withinEmployment(TODAY, List.of())).isFalse();
  }

  @Test
  void anEmploymentThatEndedExcludesTheDayAfterItsLastDay() {
    Span ended = new Span(UUID.randomUUID(), 2, LocalDate.of(2026, 1, 5), TODAY);
    assertThat(CompensationDates.withinEmployment(TODAY, List.of(ended))).isTrue();
    assertThat(CompensationDates.withinEmployment(TODAY.plusDays(1), List.of(ended))).isFalse();
    assertThat(CompensationDates.withinEmployment(LocalDate.of(2026, 1, 4), List.of(ended)))
        .isFalse();
  }
}
