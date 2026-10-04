package com.divalhr.core.people.domain.separation;

/** Closed separation reasons (Restricted HR, D22-3); no free-form notes. */
public enum SeparationReason {
  /** The employee resigned. */
  RESIGNATION,
  /** A fixed-term contract ended. */
  END_OF_FIXED_TERM,
  /** The employer ended the employment. */
  DISMISSAL,
  /** Both parties agreed to end it. */
  MUTUAL_AGREEMENT,
  /** The employee retired. */
  RETIREMENT,
  /** Another reason. */
  OTHER_SEPARATION
}
