package com.divalhr.core.payroll.domain;

/**
 * Why a payment batch cannot be prepared (preview status BLOCKED); one blocker blocks the whole
 * batch.
 */
public enum PaymentBatchBlocker {
  /** The package is VOIDED. */
  PACKAGE_VOIDED,
  /** A later package exists for the entity, month and currency. */
  PACKAGE_NOT_LATEST,
  /** A PREPARED batch already exists for the package. */
  BATCH_ALREADY_PREPARED,
  /** The package has no positive-net payslip. */
  NO_PAYABLE_LINES,
  /** A payable employee has no destination effective on the approval date. */
  MISSING_DESTINATION,
  /** A destination cannot be authenticated or is inconsistent. */
  DESTINATION_INVALID,
  /** A payslip amount is negative or arithmetically invalid. */
  INTEGRITY_FAILURE,
  /** The line or chain limit would be exceeded. */
  LIMIT_REACHED
}
