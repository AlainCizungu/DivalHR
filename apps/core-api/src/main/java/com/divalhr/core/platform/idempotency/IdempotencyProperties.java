package com.divalhr.core.platform.idempotency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Idempotency retention. Records are kept at least this long; until the cleanup job exists they
 * are kept and honoured indefinitely. Cleanup must never remove a record before {@code
 * expires_at}.
 *
 * @param retention minimum retention period (default 7 days)
 */
@ConfigurationProperties("divalhr.idempotency")
public record IdempotencyProperties(Duration retention) {

  /** Applies the default and rejects non-positive values. */
  public IdempotencyProperties {
    retention = retention == null ? Duration.ofDays(7) : retention;
    if (retention.isNegative() || retention.isZero()) {
      throw new IllegalArgumentException("divalhr.idempotency.retention must be positive");
    }
  }
}
