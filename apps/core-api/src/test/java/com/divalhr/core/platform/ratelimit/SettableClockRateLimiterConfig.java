package com.divalhr.core.platform.ratelimit;

import com.divalhr.core.support.SettableClock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * DEVX-005 (Issue #222): runs the production {@link SubjectRateLimiter}, unchanged, on the test's
 * {@link SettableClock}, so a test decides which one-minute window every request falls in. The
 * limiter's one-minute fixed window and its limits are the production ones; only the clock is
 * injected. The package-private constructor it uses is the one the unit tests already use.
 */
@TestConfiguration(proxyBeanMethods = false)
public class SettableClockRateLimiterConfig {

  /**
   * The subject limiter on the settable clock; it replaces the production bean by type only in the
   * contexts that import this configuration.
   *
   * @param properties the access-review limits
   * @param buckets the per-bucket request limits (the production binding, with test overrides)
   * @param clock the settable clock
   * @return the limiter
   */
  @Bean
  @Primary
  SubjectRateLimiter settableClockSubjectRateLimiter(
      ReviewRateLimitProperties properties, RequestLimitProperties buckets, SettableClock clock) {
    return new SubjectRateLimiter(properties, buckets, clock);
  }
}
