package com.divalhr.core.payroll.domain;

/** Why a destination cannot be recorded (preview status BLOCKED). */
public enum PaymentDestinationBlocker {
  /** The destination equals the latest recorded version. */
  NO_CHANGE
}
