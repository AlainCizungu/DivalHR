package com.divalhr.core.platform.audit;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounds of the authorization-denial audit ({@code divalhr.denial-audit}; MVP-013, D3, D6, A13-2
 * and A13-6). Budgets are in-process and per instance: the cluster ceiling is the value times the
 * number of active instances. Denials beyond a budget are not written; they appear only in the
 * {@code suppressed} metric.
 *
 * @param perActorPerMinute rows one verified subject may produce per fixed one-minute window
 *     (default 20; 1 to 600)
 * @param perInstancePerMinute rows one instance may write per window (default 600; 10 to 100,000)
 * @param maxTrackedActors subjects tracked per window; further subjects are suppressed (default
 *     10,000; 100 to 1,000,000)
 * @param poolSize connections of the dedicated bulkhead pool (default 2; 1 to 4)
 * @param connectionTimeout wait for a bulkhead connection (default 1s; 250ms to 5s)
 * @param statementTimeout database statement and lock timeout (default 2s; 100ms to 10s)
 */
@ConfigurationProperties("divalhr.denial-audit")
public record DenialAuditProperties(
    Integer perActorPerMinute,
    Integer perInstancePerMinute,
    Integer maxTrackedActors,
    Integer poolSize,
    Duration connectionTimeout,
    Duration statementTimeout) {

  /** Applies defaults and enforces bounds. */
  public DenialAuditProperties {
    perActorPerMinute = Objects.requireNonNullElse(perActorPerMinute, 20);
    perInstancePerMinute = Objects.requireNonNullElse(perInstancePerMinute, 600);
    maxTrackedActors = Objects.requireNonNullElse(maxTrackedActors, 10_000);
    poolSize = Objects.requireNonNullElse(poolSize, 2);
    connectionTimeout = Objects.requireNonNullElse(connectionTimeout, Duration.ofSeconds(1));
    statementTimeout = Objects.requireNonNullElse(statementTimeout, Duration.ofSeconds(2));
    within("per-actor-per-minute", perActorPerMinute, 1, 600);
    within("per-instance-per-minute", perInstancePerMinute, 10, 100_000);
    within("max-tracked-actors", maxTrackedActors, 100, 1_000_000);
    within("pool-size", poolSize, 1, 4);
    within("connection-timeout", connectionTimeout.toMillis(), 250, 5_000);
    within("statement-timeout", statementTimeout.toMillis(), 100, 10_000);
  }

  private static void within(String name, long value, long min, long max) {
    if (value < min || value > max) {
      throw new IllegalStateException(
          "divalhr.denial-audit." + name + " must be " + min + ".." + max);
    }
  }
}
