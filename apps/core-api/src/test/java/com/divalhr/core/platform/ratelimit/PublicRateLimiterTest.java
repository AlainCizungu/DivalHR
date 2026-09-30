package com.divalhr.core.platform.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.platform.error.ErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PublicRateLimiterTest {

  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-09-30T10:00:00Z"));
  private final Clock clock =
      new Clock() {
        @Override
        public ZoneOffset getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          return now.get();
        }
      };

  @Test
  void limitsPerClientPerWindowWithRetryAfterAndResetsNextWindow() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    PublicRateLimiter limiter =
        new PublicRateLimiter(
            new RateLimitProperties(3, 100, Duration.ofMinutes(1), 100, List.of()),
            registry,
            clock);
    for (int i = 0; i < 3; i++) {
      limiter.acquire("b", "203.0.113.7");
    }
    now.set(now.get().plusSeconds(15));
    assertThatThrownBy(() -> limiter.acquire("b", "203.0.113.7"))
        .isInstanceOfSatisfying(
            RateLimitedException.class,
            limited -> {
              assertThat(limited.code()).isEqualTo(ErrorCode.RATE_LIMITED);
              assertThat(limited.params()).isEmpty();
              assertThat(limited.retryAfterSeconds()).isEqualTo(45);
            });
    // Another client is unaffected; the next window starts fresh.
    limiter.acquire("b", "198.51.100.1");
    now.set(now.get().plusSeconds(60));
    limiter.acquire("b", "203.0.113.7");
    assertThat(registry.get(PublicRateLimiter.METRIC).tag("bucket", "b").counter().count())
        .isEqualTo(1);
    assertThat(registry.getMeters())
        .allSatisfy(meter -> assertThat(meter.getId().toString()).doesNotContain("203.0.113.7"));
  }

  @Test
  void aGlobalCapAndABoundOnRememberedClients() {
    PublicRateLimiter limiter =
        new PublicRateLimiter(
            new RateLimitProperties(10, 2, Duration.ofMinutes(1), 100, List.of()),
            new SimpleMeterRegistry(),
            clock);
    limiter.acquire("b", "a");
    limiter.acquire("b", "b");
    assertThatThrownBy(() -> limiter.acquire("b", "c")).isInstanceOf(RateLimitedException.class);

    PublicRateLimiter bounded =
        new PublicRateLimiter(
            new RateLimitProperties(10, 100, Duration.ofMinutes(1), 2, List.of()),
            new SimpleMeterRegistry(),
            clock);
    bounded.acquire("b", "a");
    bounded.acquire("b", "b");
    assertThatThrownBy(() -> bounded.acquire("b", "c")).isInstanceOf(RateLimitedException.class);
    bounded.acquire("b", "a");
  }
}
