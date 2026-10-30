package com.divalhr.core.payroll.domain;

import java.util.Objects;

/**
 * The pay day of a payroll policy (MVP-094A, D94A-3): a day of the month from 1 to 28, or the last
 * day of the month. Stored and shown; this story computes no pay date.
 *
 * @param kind {@code DAY} or {@code LAST_DAY}
 * @param day 1 to 28 for {@code DAY}, {@code null} for {@code LAST_DAY}
 */
public record PayrollPayDay(Kind kind, Integer day) {

  /** The two shapes of a pay day. */
  public enum Kind {
    /** A fixed day of the month, 1 to 28. */
    DAY,
    /** The last day of the month. */
    LAST_DAY
  }

  /** Requires the shape of the kind. */
  public PayrollPayDay {
    Objects.requireNonNull(kind, "kind");
    if (kind == Kind.DAY && (day == null || day < 1 || day > 28)) {
      throw new IllegalArgumentException("a DAY pay day is 1 to 28");
    }
    if (kind == Kind.LAST_DAY && day != null) {
      throw new IllegalArgumentException("LAST_DAY has no day");
    }
  }

  /**
   * A fixed day of the month.
   *
   * @param day 1 to 28
   * @return the pay day
   */
  public static PayrollPayDay ofDay(int day) {
    return new PayrollPayDay(Kind.DAY, day);
  }

  /**
   * The last day of the month.
   *
   * @return the pay day
   */
  public static PayrollPayDay lastDay() {
    return new PayrollPayDay(Kind.LAST_DAY, null);
  }
}
