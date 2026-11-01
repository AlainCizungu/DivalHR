package com.divalhr.core.payroll.domain;

/** The closed blockers of voiding a payroll package (MVP-094C, ADR 0035 section 9). */
public enum PayrollVoidBlocker {
  /** The package is not APPROVED. */
  ALREADY_VOIDED,
  /** The payment guard reports a payment or disbursement record. */
  PAYMENT_EXISTS
}
