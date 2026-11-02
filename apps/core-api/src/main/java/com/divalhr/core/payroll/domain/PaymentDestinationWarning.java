package com.divalhr.core.payroll.domain;

/** Non-blocking findings of a destination preview. */
public enum PaymentDestinationWarning {
  /** The same account or number is recorded for another employee (no identity is given). */
  DUPLICATE_DESTINATION,
  /** The effective date is in the past. */
  BACKDATED
}
