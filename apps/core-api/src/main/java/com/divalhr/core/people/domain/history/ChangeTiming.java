package com.divalhr.core.people.domain.history;

import java.time.LocalDate;

/** An effective date relative to the business date when the change is recorded. */
public enum ChangeTiming {
  /** After the business date. */
  SCHEDULED,
  /** On the business date. */
  CURRENT,
  /** Before the business date. */
  RETROACTIVE;

  /**
   * Classifies an effective date.
   *
   * @param effective effective date
   * @param businessDate today in the organization's time zone
   * @return the timing
   */
  public static ChangeTiming of(LocalDate effective, LocalDate businessDate) {
    int order = effective.compareTo(businessDate);
    if (order > 0) {
      return SCHEDULED;
    }
    return order == 0 ? CURRENT : RETROACTIVE;
  }
}
