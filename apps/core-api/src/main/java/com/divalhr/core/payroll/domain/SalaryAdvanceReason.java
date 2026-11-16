package com.divalhr.core.payroll.domain;

/**
 * MVP-120A (D120A-5): the closed, stable reason codes of the employer-policy eligibility. They are
 * codes, never free text, and carry no amount; the client translates them. Eligibility means only
 * that a request fits employer policy: it is not a lender affordability assessment, credit
 * approval or an entitlement.
 */
public enum SalaryAdvanceReason {
  /** No enabled, non-cancelled policy is in force for the legal entity and currency. */
  NO_ENABLED_POLICY,
  /** The employee has no employment covering the request date. */
  EMPLOYMENT_NOT_ACTIVE,
  /** The employee is not placed in the legal entity of the policy. */
  LEGAL_ENTITY_MISMATCH,
  /** Fewer completed employment months than the policy requires. */
  TENURE_TOO_SHORT,
  /** No eligible approved payroll reference exists. */
  PAYROLL_REFERENCE_MISSING,
  /** The requested currency differs from the policy or the payroll reference. */
  CURRENCY_MISMATCH,
  /** The employee already has the maximum number of open requests. */
  OPEN_LIMIT_REACHED,
  /** The waiting period after a withdrawn or refused request has not ended. */
  WAITING_PERIOD,
  /** The amount is below the policy minimum or above the employer-policy ceiling. */
  AMOUNT_OUT_OF_RANGE,
  /** Ten requests in the last twelve months. */
  ROLLING_LIMIT_REACHED
}
