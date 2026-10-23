package com.divalhr.core.integrations.domain;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * The fixed retry policy (D110-6): six attempts, immediate then 1 minute, 5 minutes, 30 minutes, 2
 * hours and 12 hours after the previous failure. Production adds a bounded deterministic jitter of
 * up to plus or minus 10 percent derived from the delivery id and attempt number; tests switch it
 * off or read it back, and time comes from the injected clock.
 */
public final class RetrySchedule {

  /** Maximum number of attempts. */
  public static final int MAX_ATTEMPTS = 6;

  private static final List<Duration> DELAYS =
      List.of(
          Duration.ofMinutes(1),
          Duration.ofMinutes(5),
          Duration.ofMinutes(30),
          Duration.ofHours(2),
          Duration.ofHours(12));

  private RetrySchedule() {}

  /**
   * Delay before the next attempt.
   *
   * @param failedAttempt number of the attempt that just failed, 1 to 5
   * @param deliveryId the delivery (jitter seed)
   * @param jitter whether to apply jitter
   * @return the delay
   */
  public static Duration delayAfter(int failedAttempt, UUID deliveryId, boolean jitter) {
    if (failedAttempt < 1 || failedAttempt >= MAX_ATTEMPTS) {
      throw new IllegalArgumentException("no attempt follows attempt " + failedAttempt);
    }
    Duration base = DELAYS.get(failedAttempt - 1);
    if (!jitter) {
      return base;
    }
    long seed =
        deliveryId.getLeastSignificantBits()
            ^ (deliveryId.getMostSignificantBits() * 31)
            ^ failedAttempt * 0x9E3779B97F4A7C15L;
    long permille = Math.floorMod(seed, 201L) - 100L;
    return base.plusMillis(base.toMillis() * permille / 1000L);
  }
}
