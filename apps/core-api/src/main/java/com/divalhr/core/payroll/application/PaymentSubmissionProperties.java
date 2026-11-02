package com.divalhr.core.payroll.application;

import java.time.Duration;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Provider submission settings ({@code divalhr.payroll.submission}, MVP-095B, ADR 0037). The
 * signing key has no default and is read from the protected secret directory; without it
 * submissions fail closed (503). The synthetic provider is the only profile this story ships; it
 * names one exact host and port and is refused outside development and test environments. {@code
 * toString} is redacted.
 *
 * @param signingKey the HMAC signing key text (at least 32 bytes), or blank when not configured
 * @param synthetic the synthetic test provider
 * @param worker the delivery worker
 */
@ConfigurationProperties("divalhr.payroll.submission")
public record PaymentSubmissionProperties(String signingKey, Synthetic synthetic, Worker worker) {

  /**
   * The synthetic provider.
   *
   * @param enabled whether the profile is active
   * @param host exact HTTPS host name
   * @param port exact HTTPS port
   * @param caFile PEM file with the receiver's throwaway CA, trusted in addition to the defaults
   */
  public record Synthetic(Boolean enabled, String host, Integer port, String caFile) {
    /** Applies defaults. */
    public Synthetic {
      enabled = enabled != null && enabled;
      host = host == null ? "" : host.trim();
      port = port == null ? Integer.valueOf(8443) : port;
    }

    /**
     * @return whether the profile is enabled and names a host
     */
    public boolean active() {
      return enabled && !host.isBlank();
    }
  }

  /**
   * The delivery worker.
   *
   * @param enabled whether the scheduler runs
   * @param interval pause between cycles
   * @param lease how long a claim lasts
   * @param claimSize most submissions claimed per cycle
   * @param connectTimeout TCP connect deadline
   * @param requestTimeout whole-attempt deadline
   */
  public record Worker(
      Boolean enabled,
      Duration interval,
      Duration lease,
      Integer claimSize,
      Duration connectTimeout,
      Duration requestTimeout) {
    /** Applies defaults and bounds. */
    public Worker {
      enabled = enabled == null || enabled;
      interval = interval == null ? Duration.ofSeconds(5) : interval;
      lease = lease == null ? Duration.ofMinutes(2) : lease;
      claimSize = claimSize == null ? 8 : Math.max(1, Math.min(claimSize, 8));
      connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
      requestTimeout = requestTimeout == null ? Duration.ofSeconds(15) : requestTimeout;
    }
  }

  /** Applies defaults. */
  public PaymentSubmissionProperties {
    synthetic = synthetic == null ? new Synthetic(false, null, null, null) : synthetic;
    worker = worker == null ? new Worker(null, null, null, null, null, null) : worker;
  }

  /**
   * @return whether a signing key is configured
   */
  public boolean keyConfigured() {
    return signingKey != null && !signingKey.isBlank();
  }

  /**
   * Refuses an unsafe configuration at start-up.
   *
   * @param environment the deployment environment
   */
  public void validate(String environment) {
    String env = environment == null ? "" : environment.trim().toLowerCase(Locale.ROOT);
    if (keyConfigured()
        && signingKey.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
      throw new IllegalStateException(
          "divalhr.payroll.submission.signing-key must be at least 32 bytes");
    }
    if (keyConfigured()
        && !"development".equals(env)
        && signingKey.toLowerCase(Locale.ROOT).startsWith("dev-only-")) {
      throw new IllegalStateException(
          "the development submission signing key must not be used in environment " + env);
    }
    if (synthetic.active() && !"development".equals(env) && !"test".equals(env)) {
      throw new IllegalStateException(
          "the synthetic payment provider is refused in environment " + env);
    }
  }

  @Override
  public String toString() {
    return "PaymentSubmissionProperties[redacted]";
  }
}
