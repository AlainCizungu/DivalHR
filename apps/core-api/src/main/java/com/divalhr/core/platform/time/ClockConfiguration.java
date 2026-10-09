package com.divalhr.core.platform.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The application clock (MVP-031A, Issue #73, decision D9): UTC system time, injectable so that
 * business-date boundaries can be tested with a fixed or settable clock. The documents module's
 * {@code ContractCalendar} and, since MVP-040A (D40A-4), the people module's {@code
 * BusinessCalendar} take it; other services keep their own clocks (wider adoption is a separate
 * maintenance item).
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {

  /**
   * The system clock in UTC.
   *
   * @return the clock
   */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
