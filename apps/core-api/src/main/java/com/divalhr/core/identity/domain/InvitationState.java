package com.divalhr.core.identity.domain;

import java.time.Instant;

/**
 * Persisted lifecycle state. {@code ACCEPTING} is internal: an acceptance holds a lease while the
 * identity is provisioned. The public status ({@link InvitationStatus}) hides it.
 */
public enum InvitationState {
  /** Usable link; waiting for the invitee. */
  PENDING,
  /** Acceptance in progress under a lease. */
  ACCEPTING,
  /** Accepted; the membership exists. */
  ACCEPTED,
  /** Expired before acceptance. */
  EXPIRED,
  /** Revoked by an administrator. */
  REVOKED;

  /**
   * The public status at a given time.
   *
   * @param expiresAt expiry of the current link
   * @param now current time
   * @return {@code EXPIRED} for a pending invitation past its expiry, {@code PENDING} while
   *     accepting, otherwise the matching status
   */
  public InvitationStatus publicStatus(Instant expiresAt, Instant now) {
    return switch (this) {
      case PENDING -> expiresAt.isAfter(now) ? InvitationStatus.PENDING : InvitationStatus.EXPIRED;
      case ACCEPTING -> InvitationStatus.PENDING;
      case ACCEPTED -> InvitationStatus.ACCEPTED;
      case EXPIRED -> InvitationStatus.EXPIRED;
      case REVOKED -> InvitationStatus.REVOKED;
    };
  }
}
