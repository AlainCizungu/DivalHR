package com.divalhr.core.payroll.domain;

/** Why a batch cannot be cancelled. */
public enum PaymentBatchCancelBlocker {
  /** The batch is already CANCELLED. */
  ALREADY_CANCELLED,
  /** A provider submission exists: the batch is final (MVP-095B). */
  ALREADY_SUBMITTED
}
