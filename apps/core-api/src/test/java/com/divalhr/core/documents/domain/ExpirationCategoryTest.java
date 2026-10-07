package com.divalhr.core.documents.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** MVP-031A (Issue #73): the category boundaries of {@code d = end - T}. */
class ExpirationCategoryTest {

  @ParameterizedTest
  @CsvSource({
    "-365, EXPIRED",
    "-1, EXPIRED",
    "0, NEXT_30_DAYS",
    "30, NEXT_30_DAYS",
    "31, DAYS_31_TO_60",
    "60, DAYS_31_TO_60",
    "61, DAYS_61_TO_90",
    "90, DAYS_61_TO_90"
  })
  void everyBoundaryFallsInExactlyOneCategory(long days, ExpirationCategory expected) {
    assertThat(ExpirationCategory.of(days)).contains(expected);
  }

  @Test
  void beyondTheWindowThereIsNoCategory() {
    assertThat(ExpirationCategory.of(91)).isEqualTo(Optional.empty());
    assertThat(ExpirationCategory.of(ExpirationCategory.WINDOW_DAYS + 1L)).isEmpty();
  }

  @Test
  void daysAreCalendarDaysAcrossMonthAndLeapYearEnds() {
    LocalDate asOf = LocalDate.of(2028, 2, 28);
    assertThat(ExpirationCategory.daysUntil(LocalDate.of(2028, 3, 1), asOf)).isEqualTo(2);
    assertThat(ExpirationCategory.daysUntil(asOf, asOf)).isZero();
    assertThat(ExpirationCategory.of(LocalDate.of(2028, 2, 27), asOf))
        .contains(ExpirationCategory.EXPIRED);
    assertThat(ExpirationCategory.of(LocalDate.of(2028, 5, 28), asOf))
        .contains(ExpirationCategory.DAYS_61_TO_90);
    assertThat(ExpirationCategory.of(LocalDate.of(2028, 5, 29), asOf)).isEmpty();
  }

  @Test
  void categoriesAreContiguousAndDoNotOverlap() {
    ExpirationCategory[] all = ExpirationCategory.values();
    for (int i = 1; i < all.length; i++) {
      assertThat(all[i].fromDays()).isEqualTo(all[i - 1].toDays() + 1);
    }
    assertThat(all[all.length - 1].toDays()).isEqualTo(ExpirationCategory.WINDOW_DAYS);
  }
}
