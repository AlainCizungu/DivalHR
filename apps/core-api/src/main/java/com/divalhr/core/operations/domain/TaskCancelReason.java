package com.divalhr.core.operations.domain;

/** The closed reasons for cancelling a task (MVP-080, D80-4). */
public enum TaskCancelReason {
  /** The work is no longer needed. */
  NO_LONGER_REQUIRED,
  /** The task should never have been created. */
  CREATED_IN_ERROR,
  /** The work is done elsewhere. */
  WORK_MOVED_ELSEWHERE,
  /** Another reason, which needs a 10 to 500 character explanation. */
  OTHER
}
