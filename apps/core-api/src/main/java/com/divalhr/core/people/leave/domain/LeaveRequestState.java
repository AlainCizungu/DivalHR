package com.divalhr.core.people.leave.domain;

/**
 * The state of a leave request (MVP-041A submits {@code PENDING}; MVP-041B moves it once to a
 * terminal state with its decision).
 */
public enum LeaveRequestState {
  /** Submitted, awaiting a decision. */
  PENDING,
  /** Approved: keeps blocking overlapping requests. */
  APPROVED,
  /** Rejected: releases its dates. */
  REJECTED;

  /**
   * Whether a decision has been taken.
   *
   * @return whether the state is terminal
   */
  public boolean terminal() {
    return this != PENDING;
  }
}
