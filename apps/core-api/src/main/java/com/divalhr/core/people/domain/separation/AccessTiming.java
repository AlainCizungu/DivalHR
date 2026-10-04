package com.divalhr.core.people.domain.separation;

/** When a separated employee's DivalHR access ends (D22-1, A22-1). */
public enum AccessTiming {
  /** At the start of the day after the last day, in the organization's time zone. */
  END_OF_LAST_DAY,
  /** At the commit; only when the last day is today or earlier. */
  IMMEDIATELY
}
