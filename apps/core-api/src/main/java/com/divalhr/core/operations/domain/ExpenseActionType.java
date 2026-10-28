package com.divalhr.core.operations.domain;

/** The append-only actions of an expense (MVP-081, D81-8). */
public enum ExpenseActionType {
  /** The draft was created. */
  CREATED,
  /** The draft or the correcting revision was changed. */
  DRAFT_UPDATED,
  /** A receipt was attached or replaced. */
  RECEIPT_SET,
  /** The receipt was removed. */
  RECEIPT_REMOVED,
  /** The revision was submitted. */
  SUBMITTED,
  /** The employee withdrew the submission. */
  WITHDRAWN,
  /** The routed manager approved. */
  APPROVED,
  /** The routed manager returned it for correction. */
  RETURNED;

  /**
   * Whether the action changes the editable revision (not a submission or a decision).
   *
   * @return true for the draft actions
   */
  public boolean editing() {
    return this == CREATED
        || this == DRAFT_UPDATED
        || this == RECEIPT_SET
        || this == RECEIPT_REMOVED;
  }
}
