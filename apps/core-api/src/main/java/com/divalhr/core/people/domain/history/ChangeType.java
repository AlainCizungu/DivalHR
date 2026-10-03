package com.divalhr.core.people.domain.history;

/** Types of recorded employment changes. */
public enum ChangeType {
  /** The employment's first placement (written with the employment). */
  HIRE,
  /** A business change from an effective date. */
  CHANGE,
  /** Replacement of one recorded row's value for the same dates. */
  CORRECTION,
  /** Cancellation of a scheduled business change. */
  CANCELLATION
}
