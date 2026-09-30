package com.divalhr.core.identity.domain;

/**
 * Delivery of the newest invitation link (architecture amendment A3). Never retried automatically:
 * the plaintext token exists only in memory during the first attempt.
 */
public enum DeliveryState {
  /** Created; delivery pending. */
  QUEUED,
  /** The mail server accepted the message. */
  SENT,
  /** Delivery failed, timed out or its outcome is unknown; the administrator can resend. */
  FAILED
}
