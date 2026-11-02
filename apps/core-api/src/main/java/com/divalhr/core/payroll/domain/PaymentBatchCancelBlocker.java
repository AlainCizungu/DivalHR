package com.divalhr.core.payroll.domain;

/** Why a batch cannot be cancelled. */
public enum PaymentBatchCancelBlocker {
  /** The batch is already CANCELLED. */
  ALREADY_CANCELLED
}
