package com.divalhr.core.operations.domain;

/**
 * The state of an expense (MVP-081, D81-6). {@code APPROVED} and {@code WITHDRAWN} are terminal.
 * Approval is a decision about the claim only: it is not reimbursement or payment.
 */
public enum ExpenseState {
  /** Being written by the employee; never seen by a manager. */
  DRAFT,
  /** Submitted to the one routed manager. */
  SUBMITTED,
  /** Approved by the routed manager (terminal). */
  APPROVED,
  /** Returned by the routed manager for correction. */
  RETURNED,
  /** Withdrawn by the employee while submitted (terminal). */
  WITHDRAWN;

  /**
   * Whether the expense counts against the open-expense limits.
   *
   * @return true for draft, submitted and returned
   */
  public boolean open() {
    return this == DRAFT || this == SUBMITTED || this == RETURNED;
  }

  /**
   * Whether the employee may still change the current revision.
   *
   * @return true for draft and returned
   */
  public boolean editable() {
    return this == DRAFT || this == RETURNED;
  }
}
