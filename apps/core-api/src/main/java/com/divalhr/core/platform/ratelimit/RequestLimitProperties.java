package com.divalhr.core.platform.ratelimit;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Named request-rate buckets beyond the access review ({@code divalhr.request-limits}; MVP-020,
 * architect decision A20-4). In-process and per instance: the effective cluster ceiling is each
 * value times the number of active instances.
 *
 * @param subject per-subject buckets: requests per one-minute fixed window (1 to 600)
 * @param tenant per-tenant buckets: requests per fixed window of the given length
 * @param maxTrackedTenants tenants tracked per bucket and window before new ones are refused
 *     (default 10,000; 100 to 1,000,000)
 */
@ConfigurationProperties("divalhr.request-limits")
public record RequestLimitProperties(
    Map<String, Integer> subject, Map<String, TenantBucket> tenant, Integer maxTrackedTenants) {

  /**
   * One per-tenant bucket.
   *
   * @param requests requests per window (1 to 10,000)
   * @param window fixed window length (1 minute to 1 hour)
   */
  public record TenantBucket(Integer requests, Duration window) {

    /** Enforces bounds. */
    public TenantBucket {
      Objects.requireNonNull(requests, "requests");
      Objects.requireNonNull(window, "window");
      if (requests < 1 || requests > 10_000) {
        throw new IllegalStateException(
            "divalhr.request-limits.tenant.*.requests must be 1..10000");
      }
      if (window.compareTo(Duration.ofMinutes(1)) < 0
          || window.compareTo(Duration.ofHours(1)) > 0) {
        throw new IllegalStateException("divalhr.request-limits.tenant.*.window must be 1m..1h");
      }
    }
  }

  /** Applies defaults and enforces bounds. */
  public RequestLimitProperties {
    subject = Map.copyOf(Objects.requireNonNullElse(subject, Map.of()));
    tenant = Map.copyOf(Objects.requireNonNullElse(tenant, Map.of()));
    maxTrackedTenants = Objects.requireNonNullElse(maxTrackedTenants, 10_000);
    subject.forEach(
        (bucket, perMinute) -> {
          if (perMinute == null || perMinute < 1 || perMinute > 600) {
            throw new IllegalStateException(
                "divalhr.request-limits.subject." + bucket + " must be 1..600");
          }
        });
    if (maxTrackedTenants < 100 || maxTrackedTenants > 1_000_000) {
      throw new IllegalStateException(
          "divalhr.request-limits.max-tracked-tenants must be 100..1000000");
    }
  }
}
