package com.divalhr.core.people.domain.history;

/** Why a plan cannot be built; mapped to stable Problem codes by the application. */
public enum TimelineRule {
  /** The new value equals the current one (EMPLOYMENT_CHANGE_NO_EFFECT). */
  NO_EFFECT,
  /** The kind already changes on that date (EMPLOYMENT_CHANGE_DATE_TAKEN). */
  DATE_TAKEN,
  /** The date is outside the employment (EMPLOYMENT_DATE_OUTSIDE_EMPLOYMENT). */
  OUTSIDE_EMPLOYMENT,
  /** A later row derives from the change to cancel (EMPLOYMENT_CHANGE_HAS_DEPENDENTS). */
  HAS_DEPENDENTS
}
