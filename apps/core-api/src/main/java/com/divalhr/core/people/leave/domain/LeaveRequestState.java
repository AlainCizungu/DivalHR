package com.divalhr.core.people.leave.domain;

/** The state of a leave request (MVP-041A: PENDING only; MVP-041B reviews transitions). */
public enum LeaveRequestState {
  /** Submitted, awaiting a decision that a later story introduces. */
  PENDING
}
