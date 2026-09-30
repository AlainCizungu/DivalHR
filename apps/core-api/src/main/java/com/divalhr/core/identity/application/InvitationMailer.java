package com.divalhr.core.identity.application;

import com.divalhr.core.identity.domain.DeliveryState;
import com.divalhr.core.identity.domain.EmailAddress;
import com.divalhr.core.identity.domain.InvitationLocale;
import com.divalhr.core.identity.domain.TenantRole;
import java.time.Instant;

/** Port for sending the invitation email. */
public interface InvitationMailer {

  /**
   * Sends one message. Implementations never throw and never retry: the link contains a token that
   * exists only in memory for this attempt.
   *
   * @param message message
   * @return {@link DeliveryState#SENT} when the mail server accepted it, otherwise {@link
   *     DeliveryState#FAILED} (including timeouts, whose outcome is unknown)
   */
  DeliveryState send(InvitationMessage message);

  /**
   * An invitation email. {@link #toString()} hides the address and link.
   *
   * @param to recipient
   * @param locale language
   * @param organizationName inviting organization (display only)
   * @param timezone organization time zone for the expiry text
   * @param role assigned role
   * @param expiresAt link expiry
   * @param link acceptance link; the token is in the fragment
   */
  record InvitationMessage(
      EmailAddress to,
      InvitationLocale locale,
      String organizationName,
      String timezone,
      TenantRole role,
      Instant expiresAt,
      String link) {

    @Override
    public String toString() {
      return "InvitationMessage[locale=" + locale + ", role=" + role + ", <redacted>]";
    }
  }
}
