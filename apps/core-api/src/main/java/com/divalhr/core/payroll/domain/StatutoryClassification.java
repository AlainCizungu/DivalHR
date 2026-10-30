package com.divalhr.core.payroll.domain;

/** The classification of an employee's statutory profile (MVP-094B, D94B-4). */
public enum StatutoryClassification {
  /** A standard resident employee, the only case the first rule pack models. */
  STANDARD_RESIDENT,
  /** Any other case (expatriate, nonresident, special regime): blocks the calculation. */
  UNSUPPORTED
}
