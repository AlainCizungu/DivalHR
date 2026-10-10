package com.divalhr.core.people.leave.domain;

/**
 * How the caller may decide a pending request of the manager inbox (MVP-041G). A safe code: it
 * never names the primary manager, the appointment or its reason.
 */
public enum ApprovalCapacity {
  /** The caller is the request's primary manager. */
  PRIMARY,
  /** The caller is the effective substitute of the request's primary manager. */
  SUBSTITUTE
}
