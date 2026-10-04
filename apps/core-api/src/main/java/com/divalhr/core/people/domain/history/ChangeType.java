package com.divalhr.core.people.domain.history;

/** Types of recorded employment changes. */
public enum ChangeType {
  /** The employment's first placement (written with the employment). */
  HIRE,
  /** A business change from an effective date. */
  CHANGE,
  /** Replacement of one recorded row's value for the same dates. */
  CORRECTION,
  /** Cancellation of a scheduled business change, or of a scheduled separation's work. */
  CANCELLATION,
  /** The end of the employment on the last day: every row past it is closed (MVP-022). */
  SEPARATION
}
