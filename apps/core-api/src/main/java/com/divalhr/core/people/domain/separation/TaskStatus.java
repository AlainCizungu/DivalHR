package com.divalhr.core.people.domain.separation;

/** Follow-up task status. */
public enum TaskStatus {
  /** To do. */
  OPEN,
  /** Marked done by an administrator. */
  DONE,
  /** Marked not applicable by an administrator. */
  NOT_APPLICABLE,
  /** Closed with its cancelled separation. */
  CANCELLED
}
