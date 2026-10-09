package com.divalhr.core.people.leave.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** MVP-040A (D40A-4): status derivation on a business date, both ends inclusive. */
class LeavePolicyStatusTest {

  private static final LocalDate FROM = LocalDate.of(2026, 3, 1);
  private static final LocalDate TO = LocalDate.of(2026, 3, 31);

  @Test
  void aPeriodIsPlannedThenActiveOnBothEndsThenEnded() {
    assertThat(LeavePolicyStatus.of(FROM, TO, FROM.minusDays(1)))
        .isEqualTo(LeavePolicyStatus.PLANNED);
    assertThat(LeavePolicyStatus.of(FROM, TO, FROM)).isEqualTo(LeavePolicyStatus.ACTIVE);
    assertThat(LeavePolicyStatus.of(FROM, TO, TO)).isEqualTo(LeavePolicyStatus.ACTIVE);
    assertThat(LeavePolicyStatus.of(FROM, TO, TO.plusDays(1))).isEqualTo(LeavePolicyStatus.ENDED);
  }

  @Test
  void anOpenEndedPeriodNeverEndsAndAOneDayPeriodIsActiveThatDayOnly() {
    assertThat(LeavePolicyStatus.of(FROM, null, LocalDate.of(2999, 12, 31)))
        .isEqualTo(LeavePolicyStatus.ACTIVE);
    assertThat(LeavePolicyStatus.of(FROM, FROM, FROM)).isEqualTo(LeavePolicyStatus.ACTIVE);
    assertThat(LeavePolicyStatus.of(FROM, FROM, FROM.plusDays(1)))
        .isEqualTo(LeavePolicyStatus.ENDED);
  }

  @Test
  void theStartAndTheBusinessDateAreRequired() {
    assertThatThrownBy(() -> LeavePolicyStatus.of(null, TO, FROM))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> LeavePolicyStatus.of(FROM, TO, null))
        .isInstanceOf(NullPointerException.class);
  }
}
