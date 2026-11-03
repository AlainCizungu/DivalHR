package com.divalhr.core.payroll.domain;

/** Why a submission cannot be investigated (preview status BLOCKED; MVP-095C, ADR 0038). */
public enum PaymentReconciliationBlocker {
  /** The submission is not in DELIVERY_UNKNOWN or MANUAL_INTERVENTION. */
  SUBMISSION_NOT_ELIGIBLE,
  /** An investigation already exists for the submission. */
  RECONCILIATION_EXISTS,
  /** No active operator profile serves the submission's provider. */
  PROVIDER_NOT_AVAILABLE,
  /** The signing key is not configured. */
  PROVIDER_KEY_UNAVAILABLE
}
