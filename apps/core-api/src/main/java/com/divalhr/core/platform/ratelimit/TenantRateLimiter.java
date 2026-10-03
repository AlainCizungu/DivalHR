package com.divalhr.core.platform.ratelimit;

import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * In-process fixed-window limiter per verified tenant and bucket (MVP-020, A20-4). Each bucket has
 * its own window length; counts are kept per window only. Tenant IDs are not personal data and are
 * never logged. When a bucket's tracking table is full, new tenants are refused (fail closed) until
 * the window ends.
 */
@Component
public class TenantRateLimiter {

  private final RequestLimitProperties properties;
  private final Clock clock;
  private final Map<String, Window> windows = new HashMap<>();

  private static final class Window {
    private long index = Long.MIN_VALUE;
    private final Map<TenantId, Integer> counts = new HashMap<>();
  }

  /**
   * Creates the limiter.
   *
   * @param properties bucket configuration
   */
  @Autowired
  public TenantRateLimiter(RequestLimitProperties properties) {
    this(properties, Clock.systemUTC());
  }

  /**
   * Creates the limiter with a clock (tests).
   *
   * @param properties bucket configuration
   * @param clock clock
   */
  TenantRateLimiter(RequestLimitProperties properties, Clock clock) {
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * Counts one request of the tenant in the bucket and rejects it over the limit.
   *
   * @param bucket configured bucket name
   * @param tenant verified, effective tenant
   * @throws TenantRateLimitedException with the seconds left in the window
   * @throws IllegalStateException when the bucket is not configured (fails closed)
   */
  public void acquire(String bucket, TenantId tenant) {
    RequestLimitProperties.TenantBucket limit = properties.tenant().get(bucket);
    if (limit == null) {
      throw new IllegalStateException("unconfigured tenant rate-limit bucket");
    }
    long windowMillis = limit.window().toMillis();
    Instant now = Instant.now(clock);
    long index = Math.floorDiv(now.toEpochMilli(), windowMillis);
    synchronized (windows) {
      Window window = windows.computeIfAbsent(bucket, ignored -> new Window());
      if (window.index != index) {
        window.index = index;
        window.counts.clear();
      }
      if (!window.counts.containsKey(tenant)
          && window.counts.size() >= properties.maxTrackedTenants()) {
        throw limited(index, windowMillis, now, false);
      }
      int count = window.counts.merge(tenant, 1, Integer::sum);
      if (count > limit.requests()) {
        throw limited(index, windowMillis, now, count == limit.requests() + 1);
      }
    }
  }

  private static TenantRateLimitedException limited(
      long index, long windowMillis, Instant now, boolean first) {
    long end = (index + 1) * windowMillis;
    return new TenantRateLimitedException((end - now.toEpochMilli() + 999) / 1000, first);
  }
}
