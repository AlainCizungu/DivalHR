package com.divalhr.core.identity.domain;

import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An invitation row (without its token hash or address lookup, which never leave persistence).
 *
 * @param id invitation id
 * @param tenant owning tenant
 * @param email normalized invitee address (confidential)
 * @param role assigned tenant role
 * @param locale email and page language
 * @param state persisted lifecycle state
 * @param tokenIssuedAt when the current link was issued
 * @param expiresAt expiry of the current link
 * @param issueCount issuance number of the current link (1 + resends)
 * @param deliveryState delivery of the current link
 * @param acceptedAt acceptance time, if accepted
 * @param revokedAt revocation time, if revoked
 * @param createdAt creation time
 * @param origin who created it (MVP-014)
 */
public record Invitation(
    UUID id,
    TenantId tenant,
    EmailAddress email,
    TenantRole role,
    InvitationLocale locale,
    InvitationState state,
    Instant tokenIssuedAt,
    Instant expiresAt,
    int issueCount,
    DeliveryState deliveryState,
    Instant acceptedAt,
    Instant revokedAt,
    Instant createdAt,
    InvitationOrigin origin) {

  /** Requires an origin. */
  public Invitation {
    Objects.requireNonNull(origin, "origin");
  }

  /** Maximum reissues per invitation. */
  public static final int MAX_RESENDS = 3;

  /**
   * The public status now.
   *
   * @param now current time
   * @return status
   */
  public InvitationStatus status(Instant now) {
    return state.publicStatus(expiresAt, now);
  }

  /**
   * Reissues still allowed.
   *
   * @return 0 to 3
   */
  public int resendsRemaining() {
    return Math.max(0, MAX_RESENDS - (issueCount - 1));
  }
}
