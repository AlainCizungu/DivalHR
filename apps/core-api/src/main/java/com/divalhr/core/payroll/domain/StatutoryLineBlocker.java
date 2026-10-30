package com.divalhr.core.payroll.domain;

/**
 * The stable employee-level blockers of a statutory preview (MVP-094B, ADR 0034 section 6). Any
 * blocker forbids committing the calculation. The declaration order is the order they are reported
 * in.
 */
public enum StatutoryLineBlocker {
  /** No statutory profile is in force on the first eligible day. */
  PROFILE_MISSING,
  /** The profile in force is classified {@code UNSUPPORTED}. */
  PROFILE_UNSUPPORTED,
  /** A profile row takes effect after the first and up to the last eligible day. */
  PROFILE_CHANGES_IN_MONTH,
  /** An earning other than the contractual base exists for the employee and month. */
  UNMODELED_EARNINGS,
  /** The employee deductions would exceed the gross. */
  DEDUCTIONS_EXCEED_GROSS
}
