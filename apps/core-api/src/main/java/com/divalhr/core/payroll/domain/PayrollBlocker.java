package com.divalhr.core.payroll.domain;

/**
 * The stable employee-level blockers of a payroll run (MVP-094A, D94A-4); any blocker forbids
 * creating the run. The declaration order is the order they are reported in.
 */
public enum PayrollBlocker {
  /** An employed day in the legal entity without a compensation fact. */
  COMPENSATION_MISSING,
  /** An employed day in the legal entity covered only by compensation in another currency. */
  CURRENCY_MISMATCH,
  /** An employed day in the month placed in another legal entity. */
  FOREIGN_PLACEMENT,
  /** Overlapping employments, placements or compensation facts. */
  OVERLAPPING_EVIDENCE,
  /** More than one distinct monthly amount applies on the eligible days. */
  MULTIPLE_MONTHLY_AMOUNTS,
  /** The method {@code NONE} and a partial month. */
  PARTIAL_MONTH_NOT_ALLOWED
}
