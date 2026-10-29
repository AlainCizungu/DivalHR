package com.divalhr.core.payroll.domain;

/** Non-blocking notices of a preview. */
public enum CompensationWarning {
  /** The effective date is before the business date. */
  RETROACTIVE,
  /** Another change is already scheduled after today. */
  LATER_CHANGE_SCHEDULED
}
