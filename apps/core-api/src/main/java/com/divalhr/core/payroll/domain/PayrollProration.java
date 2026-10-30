package com.divalhr.core.payroll.domain;

/**
 * The proration method of a legal entity's payroll policy (MVP-094A, D94A-3): how a partial month
 * of eligible days becomes a contractual gross. Rounded once, HALF_UP, in the currency minor unit.
 */
public enum PayrollProration {
  /** Eligible days divided by the days of the calendar month. */
  CALENDAR_DAYS,
  /** Eligible days (at most 30) divided by 30. */
  FIXED_30_DAYS,
  /** No proration: a partial month is an employee blocker. */
  NONE
}
