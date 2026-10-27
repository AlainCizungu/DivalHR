package com.divalhr.core.operations.domain;

/** The priority of a task (MVP-080, D80-2). */
public enum TaskPriority {
  /** Ordinary work. */
  NORMAL,
  /** Important work. */
  HIGH,
  /** Work that must happen first. */
  URGENT
}
