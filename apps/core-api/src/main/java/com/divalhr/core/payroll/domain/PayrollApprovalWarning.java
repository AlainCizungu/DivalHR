package com.divalhr.core.payroll.domain;

/** The closed warnings of a payroll approval (MVP-094C); they never block. */
public enum PayrollApprovalWarning {
  /** The approver also created the payroll run or the statutory calculation. */
  SAME_PREPARER_AND_APPROVER,
  /** The rule pack is a SYNTHETIC-TEST pack. */
  SYNTHETIC_RULE_PACK,
  /** Some employees are subject to neither CNSS nor IRPP. */
  EXCLUDED_LINES_PRESENT,
  /** Some employees have a net of zero. */
  ZERO_NET_LINES_PRESENT
}
