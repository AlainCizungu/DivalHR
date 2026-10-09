package com.divalhr.core.people.leave.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * A leave policy's status on the organization's business date (MVP-040A, D40A-4). Computed once on
 * the server; clients never derive it.
 */
public enum LeavePolicyStatus {
  /** The period starts after the business date. */
  PLANNED,
  /** The business date is inside the period (both ends inclusive). */
  ACTIVE,
  /** The period ended before the business date. */
  ENDED;

  /**
   * The status of a period on a business date.
   *
   * @param effectiveFrom first day
   * @param effectiveTo last day, or {@code null} when open-ended
   * @param businessDate the organization's business date
   * @return the status
   */
  public static LeavePolicyStatus of(
      LocalDate effectiveFrom, LocalDate effectiveTo, LocalDate businessDate) {
    Objects.requireNonNull(effectiveFrom, "effectiveFrom");
    Objects.requireNonNull(businessDate, "businessDate");
    if (effectiveFrom.isAfter(businessDate)) {
      return PLANNED;
    }
    if (effectiveTo != null && effectiveTo.isBefore(businessDate)) {
      return ENDED;
    }
    return ACTIVE;
  }
}
