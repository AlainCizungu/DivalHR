package com.divalhr.core.integrations.messaging.domain;

/** Connector states (D111-3). The connector is never deleted. */
public enum ConnectorState {
  /** Configured but not yet verified and activated. */
  DRAFT,
  /** Verified and sending. */
  ACTIVE,
  /** Stopped by an administrator; queued work was cancelled. */
  DISABLED,
  /** Stopped automatically after ten consecutive terminal failures. */
  SUSPENDED
}
