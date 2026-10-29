package com.divalhr.core.payroll.domain;

/**
 * Stable reasons a command cannot be committed. A preview returns them (a blocked preview has no
 * digest); a commit maps the first one, in this order, to its problem code. The order is the
 * contract's.
 */
public enum CompensationBlocker {
  /** The amount is well formed but outside the currency's range. */
  AMOUNT_OUT_OF_RANGE,
  /** The effective date is outside {@code [today - 60 days, today + 366 days]}. */
  DATE_OUT_OF_RANGE,
  /** The effective date is not inside an employment interval. */
  EMPLOYMENT_CONFLICT,
  /** The action does not fit the timeline. */
  TRANSITION_NOT_ALLOWED,
  /** The values are identical to the recorded ones. */
  NO_CHANGE,
  /** The change is not an unstarted scheduled change. */
  NOT_CANCELLABLE,
  /** The employee reached the change or scheduled-change limit. */
  LIMIT_REACHED
}
