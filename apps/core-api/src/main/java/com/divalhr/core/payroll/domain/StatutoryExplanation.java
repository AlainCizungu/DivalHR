package com.divalhr.core.payroll.domain;

/** The stable explanation codes of a statutory component (MVP-094B, ADR 0034 section 6). */
public enum StatutoryExplanation {
  /** A flat rate was applied. */
  RATE_APPLIED,
  /** A progressive schedule was applied. */
  PROGRESSIVE_APPLIED,
  /** The cap on the monthly result was smaller than the progressive result and was applied. */
  CAP_APPLIED,
  /** The employee's profile does not make the component apply: the amount is 0. */
  NOT_SUBJECT,
  /** The base is the contractual gross. */
  BASE_GROSS,
  /** The base is the gross less the employee social contributions of a lower order. */
  BASE_GROSS_LESS_EMPLOYEE_SOCIAL
}
