package com.divalhr.core.integrations.domain;

/** Lifecycle of a webhook endpoint (D110-2). */
public enum WebhookStatus {
  /** Receives new deliveries. */
  ACTIVE,
  /** Stopped by an administrator; history stays readable. */
  DISABLED,
  /** Stopped automatically after repeated terminal delivery failures. */
  SUSPENDED
}
