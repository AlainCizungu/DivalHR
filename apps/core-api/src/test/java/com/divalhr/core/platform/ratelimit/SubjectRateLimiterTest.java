package com.divalhr.core.platform.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** MVP-012B (R2, B5): fixed one-minute window per subject, Retry-After to the window's end. */
class SubjectRateLimiterTest {

  private static Clock at(String instant) {
    return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
  }

  @Test
  void theLimitIsPerSubjectAndBucketAndRetryAfterReachesTheWindowEnd() {
    SubjectRateLimiter limiter =
        new SubjectRateLimiter(new ReviewRateLimitProperties(3, 100), at("2026-10-01T10:00:15Z"));
    for (int i = 0; i < 3; i++) {
      limiter.acquire("access-review", "subject-a");
    }
    RateLimitedException limited =
        catchThrowableOfType(
            RateLimitedException.class, () -> limiter.acquire("access-review", "subject-a"));
    assertThat(limited.retryAfterSeconds()).isEqualTo(45);
    assertThat(limited.params()).isEmpty();
    assertThat(limited.getMessage() == null ? "" : limited.getMessage())
        .doesNotContain("subject-a");
    // Another subject and another bucket are independent.
    limiter.acquire("access-review", "subject-b");
    limiter.acquire("other-bucket", "subject-a");
  }

  @Test
  void aNewWindowStartsAFreshCount() {
    SubjectRateLimiter first =
        new SubjectRateLimiter(new ReviewRateLimitProperties(1, 100), at("2026-10-01T10:00:59Z"));
    first.acquire("access-review", "s");
    assertThatThrownBy(() -> first.acquire("access-review", "s"))
        .isInstanceOf(RateLimitedException.class)
        .satisfies(e -> assertThat(((RateLimitedException) e).retryAfterSeconds()).isEqualTo(1));
  }

  @Test
  void aFullTrackingTableRefusesNewSubjectsUntilTheWindowEnds() {
    SubjectRateLimiter limiter =
        new SubjectRateLimiter(new ReviewRateLimitProperties(5, 100), at("2026-10-01T10:00:00Z"));
    for (int i = 0; i < 100; i++) {
      limiter.acquire("access-review", "subject-" + i);
    }
    assertThatThrownBy(() -> limiter.acquire("access-review", "newcomer"))
        .isInstanceOf(RateLimitedException.class);
    // Known subjects keep their quota.
    limiter.acquire("access-review", "subject-1");
  }

  @Test
  void boundsAreEnforcedAtStartUp() {
    assertThatThrownBy(() -> new ReviewRateLimitProperties(0, null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new ReviewRateLimitProperties(121, null))
        .isInstanceOf(IllegalStateException.class);
    assertThat(new ReviewRateLimitProperties(null, null).requestsPerMinute()).isEqualTo(30);
  }

  @Test
  void onlyTheFirstRefusalInAWindowIsMarkedFirst() {
    // MVP-013 (D6): one refusal per subject and window may become denial evidence.
    SubjectRateLimiter limiter =
        new SubjectRateLimiter(new ReviewRateLimitProperties(2, 100), at("2026-10-01T10:00:00Z"));
    limiter.acquire("access-review", "s");
    limiter.acquire("access-review", "s");
    SubjectRateLimitedException first =
        catchThrowableOfType(
            SubjectRateLimitedException.class, () -> limiter.acquire("access-review", "s"));
    SubjectRateLimitedException second =
        catchThrowableOfType(
            SubjectRateLimitedException.class, () -> limiter.acquire("access-review", "s"));
    assertThat(first.firstInWindow()).isTrue();
    assertThat(second.firstInWindow()).isFalse();
  }

  @Test
  void aFullTrackingTableIsNeverAFirstRefusal() {
    SubjectRateLimiter limiter =
        new SubjectRateLimiter(new ReviewRateLimitProperties(5, 100), at("2026-10-01T10:00:00Z"));
    for (int i = 0; i < 100; i++) {
      limiter.acquire("access-review", "subject-" + i);
    }
    SubjectRateLimitedException full =
        catchThrowableOfType(
            SubjectRateLimitedException.class, () -> limiter.acquire("access-review", "newcomer"));
    assertThat(full.firstInWindow()).isFalse();
  }
}
