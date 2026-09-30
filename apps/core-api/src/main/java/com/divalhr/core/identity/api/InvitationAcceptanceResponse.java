package com.divalhr.core.identity.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Result of a successful acceptance.
 *
 * @param status always {@code ACCEPTED}
 */
@Schema(name = "InvitationAcceptance")
public record InvitationAcceptanceResponse(String status) {

  /** The only value. */
  public static final InvitationAcceptanceResponse ACCEPTED =
      new InvitationAcceptanceResponse("ACCEPTED");
}
