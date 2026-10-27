package com.divalhr.core.operations.domain;

/**
 * The derived due classification of an unfinished task (MVP-080, D80-5). Never stored: overdue is
 * only {@code now > dueAt}, strictly, and never changes the task.
 */
public enum TaskDueClass {
  /** Unfinished and the server instant is strictly later than the due instant. */
  OVERDUE,
  /** Not overdue and due within {@link #SOON_HOURS} hours. */
  DUE_SOON,
  /** Due later than that. */
  LATER,
  /** No due instant. */
  NONE;

  /** The due-soon horizon in hours. */
  public static final long SOON_HOURS = 48;
}
