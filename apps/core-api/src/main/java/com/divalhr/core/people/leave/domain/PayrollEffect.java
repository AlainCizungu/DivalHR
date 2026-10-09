package com.divalhr.core.people.leave.domain;

/** How a leave policy is described to payroll; descriptive only, no calculation (MVP-040A). */
public enum PayrollEffect {
  /** Described as paid leave. */
  PAID,
  /** Described as unpaid leave. */
  UNPAID
}
