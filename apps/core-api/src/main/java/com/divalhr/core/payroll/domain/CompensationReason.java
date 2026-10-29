package com.divalhr.core.payroll.domain;

/** The closed correction reason codes (MVP-093A, D93-7). */
public enum CompensationReason {
  /** The amount or another value was entered wrongly. */
  DATA_ENTRY_ERROR,
  /** The effective date was wrong. */
  WRONG_EFFECTIVE_DATE,
  /** The currency was wrong. */
  WRONG_CURRENCY,
  /** Any other correction. */
  OTHER_CORRECTION
}
