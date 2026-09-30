package com.divalhr.core.identity.application;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MVP-010 invitation settings ({@code divalhr.invitations}). Bounds are enforced at start-up.
 *
 * @param ttl link lifetime (default 7 days; 1 hour to 14 days)
 * @param retention how long terminal invitations are kept (default 90 days; 7 to 365 days)
 * @param createPerHour invitations a tenant may create per rolling hour
 * @param maxOpen open invitations a tenant may have at once
 * @param resendInterval minimum time between reissues of one invitation
 * @param deliveryStaleAfter a delivery still QUEUED after this becomes FAILED (amendment A3)
 * @param acceptanceLease how long an acceptance may hold an invitation before takeover
 * @param credentialSetupRetry base delay between "choose your password" retries
 * @param credentialSetupMaxAttempts attempts before the credential setup is marked FAILED
 * @param jobBatchSize rows claimed per job run
 * @param webBaseUrl base URL of the web app used in invitation links (no trailing slash)
 * @param emailLookupKey HMAC key for address lookups (at least 32 bytes; fail closed)
 */
@ConfigurationProperties("divalhr.invitations")
public record InvitationProperties(
    Duration ttl,
    Duration retention,
    Integer createPerHour,
    Integer maxOpen,
    Duration resendInterval,
    Duration deliveryStaleAfter,
    Duration acceptanceLease,
    Duration credentialSetupRetry,
    Integer credentialSetupMaxAttempts,
    Integer jobBatchSize,
    String webBaseUrl,
    String emailLookupKey) {

  /** Applies defaults and enforces bounds. */
  public InvitationProperties {
    ttl = ttl == null ? Duration.ofDays(7) : ttl;
    retention = retention == null ? Duration.ofDays(90) : retention;
    createPerHour = Objects.requireNonNullElse(createPerHour, 50);
    maxOpen = Objects.requireNonNullElse(maxOpen, 500);
    resendInterval = resendInterval == null ? Duration.ofMinutes(5) : resendInterval;
    deliveryStaleAfter = deliveryStaleAfter == null ? Duration.ofMinutes(10) : deliveryStaleAfter;
    acceptanceLease = acceptanceLease == null ? Duration.ofMinutes(2) : acceptanceLease;
    credentialSetupRetry =
        credentialSetupRetry == null ? Duration.ofMinutes(5) : credentialSetupRetry;
    credentialSetupMaxAttempts = Objects.requireNonNullElse(credentialSetupMaxAttempts, 5);
    jobBatchSize = Objects.requireNonNullElse(jobBatchSize, 200);
    require(within(ttl, Duration.ofHours(1), Duration.ofDays(14)), "ttl must be 1h..14d");
    require(
        within(retention, Duration.ofDays(7), Duration.ofDays(365)), "retention must be 7d..365d");
    require(createPerHour >= 1 && maxOpen >= 1, "quotas must be positive");
    require(within(resendInterval, Duration.ZERO, Duration.ofHours(24)), "resend-interval 0..24h");
    require(
        within(deliveryStaleAfter, Duration.ofMinutes(1), Duration.ofHours(24)),
        "delivery-stale-after must be 1m..24h");
    require(
        within(acceptanceLease, Duration.ofSeconds(10), Duration.ofMinutes(30)),
        "acceptance-lease must be 10s..30m");
    require(!credentialSetupRetry.isNegative(), "credential-setup-retry must not be negative");
    require(
        credentialSetupMaxAttempts >= 1 && credentialSetupMaxAttempts <= 10,
        "credential-setup-max-attempts must be 1..10");
    require(jobBatchSize >= 1 && jobBatchSize <= 1000, "job-batch-size must be 1..1000");
    require(webBaseUrl != null && !webBaseUrl.isBlank(), "web-base-url is required");
    URI uri = URI.create(webBaseUrl);
    require(
        ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
            && uri.getHost() != null
            && uri.getRawQuery() == null
            && uri.getRawFragment() == null
            && !webBaseUrl.endsWith("/"),
        "web-base-url must be an absolute http(s) URL without query, fragment or trailing slash");
  }

  private static boolean within(Duration value, Duration min, Duration max) {
    return value.compareTo(min) >= 0 && value.compareTo(max) <= 0;
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException("divalhr.invitations." + message);
    }
  }

  @Override
  public String toString() {
    return "InvitationProperties[ttl=" + ttl + ", emailLookupKey=<redacted>]";
  }
}
