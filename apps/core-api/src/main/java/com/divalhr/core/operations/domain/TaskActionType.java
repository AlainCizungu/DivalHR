package com.divalhr.core.operations.domain;

/** The append-only actions of a task (MVP-080, D80-6). */
public enum TaskActionType {
  /** The task was assigned. */
  CREATED,
  /** The assignee started it. */
  STARTED,
  /** The assignee completed it. */
  COMPLETED,
  /** An administrator cancelled it. */
  CANCELLED
}
