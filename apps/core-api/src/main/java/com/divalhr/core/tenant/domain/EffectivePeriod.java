package com.divalhr.core.tenant.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * An inclusive business-date period; {@code to == null} means open-ended.
 *
 * @param from first effective day (inclusive)
 * @param to last effective day (inclusive), or {@code null}
 */
public record EffectivePeriod(LocalDate from, LocalDate to) {

  /** Earliest supported date. */
  public static final LocalDate MIN = LocalDate.of(1900, 1, 1);

  /** Latest supported date. */
  public static final LocalDate MAX = LocalDate.of(2999, 12, 31);

  /** Requires a start that is not after the end. */
  public EffectivePeriod {
    Objects.requireNonNull(from, "from");
    if (to != null && to.isBefore(from)) {
      throw new IllegalArgumentException("period ends before it starts");
    }
  }

  /**
   * Whether this period fully contains another. An open-ended inner period requires an open-ended
   * outer period.
   *
   * @param inner candidate inner period
   * @return true when contained, boundaries inclusive
   */
  public boolean contains(EffectivePeriod inner) {
    if (inner.from().isBefore(from)) {
      return false;
    }
    if (to == null) {
      return true;
    }
    return inner.to() != null && !inner.to().isAfter(to);
  }

  /**
   * Whether a date is within the supported range.
   *
   * @param date date
   * @return true when within 1900-01-01..2999-12-31
   */
  public static boolean inSupportedRange(LocalDate date) {
    return !date.isBefore(MIN) && !date.isAfter(MAX);
  }
}
