package com.divalhr.core.payroll.domain;

/** The problem of one line of a blocked preview. */
public enum PaymentBatchLineIssue {
  /** No destination effective on the approval date. */
  MISSING_DESTINATION,
  /** The destination cannot be authenticated or is inconsistent. */
  DESTINATION_INVALID,
  /** The amount is not a valid payable net. */
  INTEGRITY_FAILURE
}
