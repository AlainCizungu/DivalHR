package com.divalhr.core.people.domain.separation;

/** Separation lifecycle (section 1 of the approved proposal). */
public enum SeparationState {
  /** Recorded; the start of the day after the last day has not passed. */
  SCHEDULED,
  /** That instant passed (or the separation was retroactive). */
  EFFECTIVE,
  /** Cancelled before it became effective; its work was reversed. */
  CANCELLED
}
