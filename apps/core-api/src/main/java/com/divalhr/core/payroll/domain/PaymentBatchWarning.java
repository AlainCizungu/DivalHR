package com.divalhr.core.payroll.domain;

/** Non-blocking findings of a batch preview. */
public enum PaymentBatchWarning {
  /** A newer destination was recorded after the package was approved. */
  DESTINATION_CHANGED_AFTER_APPROVAL,
  /** Two payable employees share an account or number. */
  DUPLICATE_DESTINATION,
  /** Some lines are paid outside DivalHR. */
  MANUAL_LINES_PRESENT,
  /** Zero-net payslips are excluded. */
  ZERO_NET_EXCLUDED
}
