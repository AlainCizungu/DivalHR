package com.divalhr.core.integrations.messaging.domain;

/** Recipient statuses; a recipient is never deleted. */
public enum RecipientStatus {
  /** Receives the notifications it selected. */
  ACTIVE,
  /** Receives nothing; history stays readable. */
  DISABLED
}
