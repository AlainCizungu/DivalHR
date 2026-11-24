package com.divalhr.core.integrations.partner.referral;

import java.time.Instant;

/**
 * The only way a referral leaves DivalHR (MVP-120F, D120F-7). The sole implementation is the
 * in-process synthetic provider: there is no partner endpoint, no credential and no network here.
 */
public interface ReferralGateway {

  /** What was sent: exactly the previewed disclosure, signed. */
  record Delivery(String reference, Instant timestamp, String payload, String signature) {}

  /** What the provider answered. */
  enum Kind {
    /** The provider accepted the referral and returned a receipt. */
    ACCEPTED,
    /** The provider refused the referral. */
    REJECTED,
    /** Nothing reached the provider; a retry is safe. */
    NOT_SENT,
    /** The referral may or may not have reached the provider; it is never resent. */
    UNCERTAIN
  }

  /** The provider's answer; the reference is set only when accepted. */
  record Result(Kind kind, String providerReference) {}

  /**
   * Sends one referral.
   *
   * @param delivery the signed disclosure
   * @return the answer
   */
  Result send(Delivery delivery);
}
