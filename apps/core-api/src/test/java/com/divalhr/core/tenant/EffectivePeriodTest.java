package com.divalhr.core.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.tenant.domain.EffectivePeriod;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EffectivePeriodTest {

  private static EffectivePeriod period(String from, String to) {
    return new EffectivePeriod(LocalDate.parse(from), to == null ? null : LocalDate.parse(to));
  }

  @ParameterizedTest(name = "{0}..{1} contains {2}..{3}: {4}")
  @CsvSource(
      nullValues = "open",
      value = {
        "2026-01-01, 2026-12-31, 2026-01-01, 2026-12-31, true",
        "2026-01-01, 2026-12-31, 2026-01-01, 2026-01-01, true",
        "2026-01-01, 2026-12-31, 2026-12-31, 2026-12-31, true",
        "2026-06-15, 2026-06-15, 2026-06-15, 2026-06-15, true",
        "2026-01-01, open, 2026-01-01, open, true",
        "2026-01-01, open, 2999-12-31, 2999-12-31, true",
        "2026-01-01, 2026-12-31, 2026-01-01, open, false",
        "2026-01-01, 2026-12-31, 2025-12-31, 2026-12-31, false",
        "2026-01-01, 2026-12-31, 2026-01-01, 2027-01-01, false",
        "2026-01-01, open, 2025-12-31, open, false",
      })
  void containmentIsInclusiveAndOpenEndedAware(
      String outerFrom, String outerTo, String innerFrom, String innerTo, boolean expected) {
    assertThat(period(outerFrom, outerTo).contains(period(innerFrom, innerTo))).isEqualTo(expected);
  }

  @Test
  void rejectsInvertedPeriodsAndBoundsTheSupportedRange() {
    assertThatThrownBy(() -> period("2026-01-02", "2026-01-01"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(EffectivePeriod.inSupportedRange(LocalDate.of(1900, 1, 1))).isTrue();
    assertThat(EffectivePeriod.inSupportedRange(LocalDate.of(2999, 12, 31))).isTrue();
    assertThat(EffectivePeriod.inSupportedRange(LocalDate.of(1899, 12, 31))).isFalse();
    assertThat(EffectivePeriod.inSupportedRange(LocalDate.of(3000, 1, 1))).isFalse();
  }
}
