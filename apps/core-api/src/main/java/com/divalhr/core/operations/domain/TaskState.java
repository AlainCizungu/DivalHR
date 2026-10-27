package com.divalhr.core.operations.domain;

/** The state of a task (MVP-080, D80-4). {@code COMPLETED} and {@code CANCELLED} are terminal. */
public enum TaskState {
  /** Assigned, not started. */
  OPEN,
  /** Started by the assignee. */
  IN_PROGRESS,
  /** Completed by the assignee (terminal). */
  COMPLETED,
  /** Cancelled by an administrator (terminal). */
  CANCELLED;

  /**
   * Whether no further transition exists.
   *
   * @return true for {@code COMPLETED} and {@code CANCELLED}
   */
  public boolean terminal() {
    return this == COMPLETED || this == CANCELLED;
  }

  /**
   * Whether the task is unfinished (open or in progress).
   *
   * @return true when not terminal
   */
  public boolean unfinished() {
    return !terminal();
  }
}
