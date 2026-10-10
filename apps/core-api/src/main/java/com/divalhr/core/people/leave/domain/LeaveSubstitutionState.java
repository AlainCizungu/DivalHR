package com.divalhr.core.people.leave.domain;

import java.time.LocalDate;

/**
 * The derived state of a leave approval substitute appointment (MVP-041G): never stored. Revoked
 * first; otherwise the organization's business date against the inclusive period.
 */
public enum LeaveSubstitutionState {
  /** Not revoked; its first day is after the business date. */
  SCHEDULED,
  /** Not revoked; the business date is within its period: the substitute may decide. */
  ACTIVE,
  /** Not revoked; its last day is before the business date. */
  EXPIRED,
  /** Revoked (immediately, whatever its dates). */
  REVOKED;

  /**
   * The state of an appointment on a business date.
   *
   * @param from first day (inclusive)
   * @param to last day (inclusive)
   * @param revoked whether a revocation exists
   * @param today the organization's business date
   * @return the state
   */
  public static LeaveSubstitutionState of(
      LocalDate from, LocalDate to, boolean revoked, LocalDate today) {
    if (revoked) {
      return REVOKED;
    }
    if (today.isBefore(from)) {
      return SCHEDULED;
    }
    return today.isAfter(to) ? EXPIRED : ACTIVE;
  }
}
