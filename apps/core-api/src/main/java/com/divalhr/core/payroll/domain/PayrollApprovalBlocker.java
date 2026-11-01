package com.divalhr.core.payroll.domain;

/**
 * The closed blockers of a payroll approval (MVP-094C, ADR 0035 section 6). Any one forbids the
 * approval. The names are the contract's codes.
 */
public enum PayrollApprovalBlocker {
  /** The payroll run is not the current DRAFT of its chain. */
  SOURCE_RUN_NOT_CURRENT(true),
  /** The statutory calculation is not the current DRAFT. */
  CALCULATION_NOT_CURRENT(true),
  /** The calculation lacks lines of the run, or counts differ. */
  CALCULATION_INCOMPLETE(false),
  /** The rule pack is not VERIFIED. */
  RULE_PACK_NOT_VERIFIED(false),
  /** The payroll policy in force differs from the one frozen in the run. */
  POLICY_CHANGED(true),
  /** The statutory profiles differ from those the calculation used. */
  PROFILE_CHANGED(true),
  /** The activation or pack in effect differs from the one the calculation used. */
  RULE_PACK_CHANGED(true),
  /** A fresh payroll calculation would differ from the frozen run. */
  SOURCE_CHANGED(true),
  /** The currency is not CDF. */
  CURRENCY_NOT_SUPPORTED(false),
  /** No line has a net above zero. */
  NO_PAYABLE_LINES(false),
  /** A line, a total or a count differs from its sources. */
  ARITHMETIC_MISMATCH(false),
  /** Another APPROVED package exists for the entity, month and currency. */
  ALREADY_APPROVED(false),
  /** The run and calculation were in a voided package. */
  SOURCE_ALREADY_VOIDED(false),
  /** A required source is absent. */
  SOURCE_MISSING(false),
  /** More than 5,000 payslips, or a 26th package of the chain. */
  LIMIT_REACHED(false);

  private final boolean drift;

  PayrollApprovalBlocker(boolean drift) {
    this.drift = drift;
  }

  /**
   * Whether the blocker means the evidence moved since a preview (a commit then answers {@code
   * PAYROLL_APPROVAL_PREVIEW_STALE} rather than {@code PAYROLL_PACKAGE_BLOCKED}).
   *
   * @return true for source, calculation, policy, profile and rule-pack drift
   */
  public boolean drift() {
    return drift;
  }
}
