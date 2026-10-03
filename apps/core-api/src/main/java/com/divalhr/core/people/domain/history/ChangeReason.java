package com.divalhr.core.people.domain.history;

/** Closed reason codes; no free-form notes (H9). */
public enum ChangeReason {
  /** The change happened earlier than it was recorded. */
  LATE_NOTIFICATION(false),
  /** Organizational restructuring. */
  REORGANIZATION(false),
  /** A contract changed. */
  CONTRACT_CHANGE(false),
  /** Another business change. */
  OTHER_BUSINESS_CHANGE(false),
  /** A recorded value was mistyped. */
  DATA_ENTRY_ERROR(true),
  /** An imported value was wrong. */
  IMPORT_ERROR(true),
  /** A document showed the recorded value was wrong. */
  DOCUMENT_RECEIVED(true);

  private final boolean correction;

  ChangeReason(boolean correction) {
    this.correction = correction;
  }

  /**
   * Whether the reason applies to corrections (otherwise to business changes).
   *
   * @return true for correction reasons
   */
  public boolean correction() {
    return correction;
  }
}
