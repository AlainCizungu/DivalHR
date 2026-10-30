package com.divalhr.core.payroll.domain;

/**
 * The stable explanation codes of a payroll line (MVP-094A, D94A-4), in the order they are
 * reported.
 */
public enum PayrollExplanation {
  /** Every day of the month is eligible: the monthly amount, no division. */
  FULL_MONTH,
  /** Prorated by the days of the calendar month. */
  PRORATED_CALENDAR_DAYS,
  /** Prorated by 30 days. */
  PRORATED_FIXED_30_DAYS,
  /** Thirty or more eligible days under the 30-day method return the full month. */
  CAPPED_AT_FULL_MONTH,
  /** An employment started inside the month. */
  STARTED_IN_MONTH,
  /** An employment ended inside the month. */
  ENDED_IN_MONTH,
  /** The eligible days form more than one range. */
  MULTIPLE_PERIODS
}
