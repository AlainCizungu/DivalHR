package com.divalhr.core.platform.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** MVP-020 (A20-4): per-tenant fixed windows per bucket, first refusal marked, unknown buckets. */
class TenantRateLimiterTest {

  private static RequestLimitProperties props() {
    return new RequestLimitProperties(
        Map.of(),
        Map.of("uploads", new RequestLimitProperties.TenantBucket(2, Duration.ofMinutes(10))),
        null);
  }

  @Test
  void eachTenantHasItsOwnWindowAndRetryAfterReachesItsEnd() {
    TenantRateLimiter limiter =
        new TenantRateLimiter(
            props(), Clock.fixed(Instant.parse("2026-10-03T10:07:30Z"), ZoneOffset.UTC));
    TenantId a = new TenantId(UUID.randomUUID());
    limiter.acquire("uploads", a);
    limiter.acquire("uploads", a);
    TenantRateLimitedException first =
        catchThrowableOfType(TenantRateLimitedException.class, () -> limiter.acquire("uploads", a));
    TenantRateLimitedException second =
        catchThrowableOfType(TenantRateLimitedException.class, () -> limiter.acquire("uploads", a));
    assertThat(first.firstInWindow()).isTrue();
    assertThat(second.firstInWindow()).isFalse();
    assertThat(first.retryAfterSeconds()).isEqualTo(150);
    limiter.acquire("uploads", new TenantId(UUID.randomUUID()));
  }

  @Test
  void unconfiguredBucketsFailClosedAndBoundsAreEnforced() {
    TenantRateLimiter limiter = new TenantRateLimiter(props(), Clock.systemUTC());
    assertThatThrownBy(() -> limiter.acquire("other", new TenantId(UUID.randomUUID())))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new RequestLimitProperties.TenantBucket(0, Duration.ofMinutes(1)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new RequestLimitProperties.TenantBucket(1, Duration.ofSeconds(30)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new RequestLimitProperties(Map.of("x", 0), Map.of(), null))
        .isInstanceOf(IllegalStateException.class);
  }
}
