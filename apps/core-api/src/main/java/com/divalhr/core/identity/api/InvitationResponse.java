package com.divalhr.core.identity.api;

import com.divalhr.core.identity.domain.DeliveryState;
import com.divalhr.core.identity.domain.Invitation;
import com.divalhr.core.identity.domain.InvitationOrigin;
import com.divalhr.core.identity.domain.InvitationStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * An invitation for a tenant administrator of the owning tenant. Contains the email address
 * (confidential, decision D-11); never a token, token hash, subject or actor.
 *
 * @param id invitation
 * @param email normalized invitee address
 * @param role role
 * @param locale language
 * @param status public status
 * @param deliveryState delivery of the current link
 * @param expiresAt link expiry
 * @param createdAt creation time
 * @param acceptedAt acceptance time or {@code null}
 * @param revokedAt revocation time or {@code null}
 * @param resendsRemaining reissues still allowed
 * @param origin who created it (MVP-014)
 */
@Schema(name = "Invitation")
public record InvitationResponse(
    UUID id,
    String email,
    String role,
    String locale,
    InvitationStatus status,
    DeliveryState deliveryState,
    Instant expiresAt,
    Instant createdAt,
    @JsonInclude(JsonInclude.Include.ALWAYS) Instant acceptedAt,
    @JsonInclude(JsonInclude.Include.ALWAYS) Instant revokedAt,
    int resendsRemaining,
    InvitationOrigin origin) {

  /**
   * Maps an invitation.
   *
   * @param invitation invitation
   * @param now current time (for the public status)
   * @return response
   */
  public static InvitationResponse from(Invitation invitation, Instant now) {
    return new InvitationResponse(
        invitation.id(),
        invitation.email().value(),
        invitation.role().wireName(),
        invitation.locale().tag(),
        invitation.status(now),
        invitation.deliveryState(),
        invitation.expiresAt(),
        invitation.createdAt(),
        invitation.acceptedAt(),
        invitation.revokedAt(),
        invitation.resendsRemaining(),
        invitation.origin());
  }

  @Override
  public String toString() {
    return "InvitationResponse[id=" + id + ", <redacted>]";
  }
}
