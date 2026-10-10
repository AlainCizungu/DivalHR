package com.divalhr.core.people.leave.domain;

/**
 * The state of a leave request (MVP-041A submits {@code PENDING}; MVP-041B moves it once to a
 * terminal state with its decision; MVP-041C lets its employee cancel it while pending; MVP-041D
 * lets its employee replace it while pending).
 */
public enum LeaveRequestState {
  /** Submitted, awaiting a decision. */
  PENDING,
  /** Approved: keeps blocking overlapping requests. */
  APPROVED,
  /** Rejected: releases its dates. */
  REJECTED,
  /** Cancelled by its employee while pending: releases its dates (MVP-041C). */
  CANCELLED,
  /** Replaced by its employee while pending: releases its dates to its replacement (MVP-041D). */
  AMENDED,
  /**
   * Approved, then withdrawn by its employee before it started: keeps its approval decision and
   * releases its dates (MVP-041F). Never changes again.
   */
  WITHDRAWN;

  /**
   * Whether the request has left {@code PENDING} (decided, cancelled, amended or withdrawn).
   *
   * @return whether the state is terminal
   */
  public boolean terminal() {
    return this != PENDING;
  }
}
