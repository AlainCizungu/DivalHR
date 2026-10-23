package com.divalhr.core.integrations.domain;

/** State of one delivery (D110-10). */
public enum DeliveryState {
  /** Created, no attempt yet. */
  PENDING,
  /** At least one attempt failed and another is scheduled. */
  RETRYING,
  /** A 2xx answer was received. */
  SUCCEEDED,
  /** All attempts failed. */
  FAILED,
  /** The webhook was stopped before the delivery finished. */
  CANCELLED;

  /**
   * Whether no further attempt will happen.
   *
   * @return true for a final state
   */
  public boolean isFinal() {
    return this == SUCCEEDED || this == FAILED || this == CANCELLED;
  }
}
