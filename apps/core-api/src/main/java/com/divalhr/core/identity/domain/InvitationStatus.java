package com.divalhr.core.identity.domain;

/** Public lifecycle status ({@code InvitationStatus} in docs/API-SPEC.yaml). */
public enum InvitationStatus {
  /** Waiting for the invitee (including an acceptance in progress). */
  PENDING,
  /** Accepted. */
  ACCEPTED,
  /** Expired. */
  EXPIRED,
  /** Revoked. */
  REVOKED
}
