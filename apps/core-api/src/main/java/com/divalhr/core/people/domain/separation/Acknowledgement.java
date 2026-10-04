package com.divalhr.core.people.domain.separation;

/** Explicit confirmations a separation may require. */
public enum Acknowledgement {
  /** The last day is before today. */
  RETROACTIVE,
  /** No DivalHR access is linked: nothing will be revoked. */
  NO_LINKED_ACCESS,
  /** Linked access ends at the commit (A22-1). */
  IMMEDIATE_ACCESS_REMOVAL
}
