package com.divalhr.core.payroll.domain;

/**
 * Why a batch cannot be submitted (preview status BLOCKED); one blocker blocks the whole request and
 * no network access happens (MVP-095B, D95B-4).
 */
public enum PaymentSubmissionBlocker {
  /** The batch is not PREPARED (it is cancelled). */
  BATCH_NOT_PREPARED,
  /** The payroll package is not APPROVED. */
  PACKAGE_NOT_APPROVED,
  /** The payroll package is VOIDED. */
  PACKAGE_VOIDED,
  /** A later package exists for the entity, month and currency. */
  PACKAGE_NOT_LATEST,
  /** A submission already exists for the batch. */
  SUBMISSION_EXISTS,
  /** No active operator profile serves this legal entity and currency. */
  PROVIDER_NOT_AVAILABLE,
  /** The profile does not support a method or the currency of the batch. */
  PROVIDER_UNSUPPORTED,
  /** The signing key is not configured. */
  PROVIDER_KEY_UNAVAILABLE,
  /** A destination is missing, cannot be opened or cannot be mapped. */
  DESTINATION_UNREADABLE,
  /** The batch exceeds the line limit of a submission. */
  LIMIT_REACHED
}
