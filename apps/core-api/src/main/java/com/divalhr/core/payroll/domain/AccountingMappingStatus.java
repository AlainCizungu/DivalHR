package com.divalhr.core.payroll.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * The derived status of an accounting mapping version (MVP-096, ADR 0039 section 3): {@code
 * SCHEDULED} when it takes effect after the business date, {@code ACTIVE} when it is the version of
 * its chain with the greatest effective date on or before the business date, {@code SUPERSEDED}
 * otherwise. Never stored: a stored version never changes.
 */
public enum AccountingMappingStatus {
  /** Takes effect after the business date. */
  SCHEDULED,
  /** The latest version in effect on the business date. */
  ACTIVE,
  /** In effect on the business date but replaced by a later version that is also in effect. */
  SUPERSEDED;

  /**
   * The effective date of the version in force on a date.
   *
   * @param effectiveDates the effective dates of one chain (one currency), in any order
   * @param today the business date
   * @return the greatest date on or before {@code today}, or {@code null} when none is
   */
  public static LocalDate activeDate(List<LocalDate> effectiveDates, LocalDate today) {
    LocalDate active = null;
    for (LocalDate date : effectiveDates) {
      if (!date.isAfter(today) && (active == null || date.isAfter(active))) {
        active = date;
      }
    }
    return active;
  }

  /**
   * The status of one version.
   *
   * @param effectiveFrom the version's effective date
   * @param today the business date
   * @param activeDate the chain's {@link #activeDate}, or {@code null}
   * @return the status
   */
  public static AccountingMappingStatus of(
      LocalDate effectiveFrom, LocalDate today, LocalDate activeDate) {
    if (effectiveFrom.isAfter(today)) {
      return SCHEDULED;
    }
    return effectiveFrom.equals(activeDate) ? ACTIVE : SUPERSEDED;
  }
}
