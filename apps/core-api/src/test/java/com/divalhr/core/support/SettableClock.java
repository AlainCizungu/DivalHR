package com.divalhr.core.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * A UTC clock tests can set (MVP-031A, Issue #73, D9): replaces the application {@link Clock} bean
 * for business-date boundary tests. It starts at the system time; {@link #reset()} returns to it.
 */
public final class SettableClock extends Clock {

  private volatile Instant fixed;

  /**
   * Freezes the clock at an instant.
   *
   * @param instant the instant
   */
  public void set(Instant instant) {
    this.fixed = instant;
  }

  /** Follows the system time again. */
  public void reset() {
    this.fixed = null;
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    SettableClock self = this;
    return new Clock() {
      @Override
      public ZoneId getZone() {
        return zone;
      }

      @Override
      public Clock withZone(ZoneId other) {
        return self.withZone(other);
      }

      @Override
      public Instant instant() {
        return self.instant();
      }
    };
  }

  @Override
  public Instant instant() {
    Instant now = fixed;
    return now == null ? Instant.now() : now;
  }

  /** Registers the settable clock as the application clock. */
  @TestConfiguration(proxyBeanMethods = false)
  public static class Config {

    /**
     * The settable clock.
     *
     * @return the clock
     */
    @Bean
    @Primary
    public SettableClock settableClock() {
      return new SettableClock();
    }
  }
}
