package com.divalhr.core.identity.application;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Identity-provider revocation settings ({@code divalhr.access-revocation}; MVP-022, D22-14).
 *
 * @param batchSize revocations handled per worker run (default 20; 1 to 200)
 * @param lease how long one attempt holds a revocation (default 2 min; 30 s to 15 min); an attempt
 *     whose worker died is taken over after it
 * @param maxAttempts attempts before manual intervention (default 10; 1 to 10)
 * @param firstBackoff wait after the first failed attempt; doubles each time (default 1 min)
 * @param maxBackoff longest wait between attempts (default 60 min)
 */
@ConfigurationProperties("divalhr.access-revocation")
public record AccessRevocationProperties(
    Integer batchSize,
    Duration lease,
    Integer maxAttempts,
    Duration firstBackoff,
    Duration maxBackoff) {

  /** Applies defaults and enforces bounds. */
  public AccessRevocationProperties {
    batchSize = Objects.requireNonNullElse(batchSize, 20);
    lease = Objects.requireNonNullElse(lease, Duration.ofMinutes(2));
    maxAttempts = Objects.requireNonNullElse(maxAttempts, 10);
    firstBackoff = Objects.requireNonNullElse(firstBackoff, Duration.ofMinutes(1));
    maxBackoff = Objects.requireNonNullElse(maxBackoff, Duration.ofMinutes(60));
    if (batchSize < 1 || batchSize > 200) {
      throw new IllegalArgumentException("batch-size must be between 1 and 200");
    }
    if (lease.compareTo(Duration.ofSeconds(30)) < 0
        || lease.compareTo(Duration.ofMinutes(15)) > 0) {
      throw new IllegalArgumentException("lease must be between 30 s and 15 min");
    }
    if (maxAttempts < 1 || maxAttempts > 10) {
      throw new IllegalArgumentException("max-attempts must be between 1 and 10");
    }
    if (firstBackoff.isNegative()
        || firstBackoff.isZero()
        || maxBackoff.compareTo(firstBackoff) < 0) {
      throw new IllegalArgumentException("backoff must be positive and max-backoff >= first");
    }
  }

  /**
   * The wait after a failed attempt.
   *
   * @param attempt the attempt that failed (1-based)
   * @return first-backoff x 2^(attempt-1), at most max-backoff
   */
  public Duration backoff(int attempt) {
    Duration wait = firstBackoff;
    for (int i = 1; i < attempt && wait.compareTo(maxBackoff) < 0; i++) {
      wait = wait.multipliedBy(2);
    }
    return wait.compareTo(maxBackoff) > 0 ? maxBackoff : wait;
  }
}
