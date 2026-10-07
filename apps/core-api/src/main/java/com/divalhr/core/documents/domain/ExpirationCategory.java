package com.divalhr.core.documents.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Non-overlapping expiration categories of a coverage head (MVP-031A, Issue #73), from the days
 * between the business date {@code T} and the contract's end date: {@code d = end - T}. A contract
 * ending today ({@code d = 0}) is not expired.
 */
public enum ExpirationCategory {
  /** {@code d < 0}: the end date is before today. */
  EXPIRED(Integer.MIN_VALUE, -1),
  /** {@code 0 <= d <= 30}: today through day 30. */
  NEXT_30_DAYS(0, 30),
  /** {@code 31 <= d <= 60}. */
  DAYS_31_TO_60(31, 60),
  /** {@code 61 <= d <= 90}. */
  DAYS_61_TO_90(61, 90);

  /** The last day of the window, in days after the business date. */
  public static final int WINDOW_DAYS = 90;

  private final int fromDays;
  private final int toDays;

  ExpirationCategory(int fromDays, int toDays) {
    this.fromDays = fromDays;
    this.toDays = toDays;
  }

  /**
   * Smallest day count of the category ({@link Integer#MIN_VALUE} for {@link #EXPIRED}).
   *
   * @return lower bound, inclusive
   */
  public int fromDays() {
    return fromDays;
  }

  /**
   * Largest day count of the category.
   *
   * @return upper bound, inclusive
   */
  public int toDays() {
    return toDays;
  }

  /**
   * Days from the business date to the end date (negative when overdue).
   *
   * @param endDate contract end date
   * @param asOf business date
   * @return {@code end - asOf} in days
   */
  public static long daysUntil(LocalDate endDate, LocalDate asOf) {
    return ChronoUnit.DAYS.between(asOf, endDate);
  }

  /**
   * The category of a day count, or empty beyond the window.
   *
   * @param days {@code end - asOf}
   * @return the category, or empty when {@code days > 90}
   */
  public static Optional<ExpirationCategory> of(long days) {
    for (ExpirationCategory category : values()) {
      if (days >= category.fromDays && days <= category.toDays) {
        return Optional.of(category);
      }
    }
    return Optional.empty();
  }

  /**
   * The category of an end date on a business date, or empty beyond the window.
   *
   * @param endDate contract end date
   * @param asOf business date
   * @return the category
   */
  public static Optional<ExpirationCategory> of(LocalDate endDate, LocalDate asOf) {
    return of(daysUntil(endDate, asOf));
  }
}
