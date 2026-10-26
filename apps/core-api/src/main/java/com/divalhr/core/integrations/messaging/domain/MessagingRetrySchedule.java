package com.divalhr.core.integrations.messaging.domain;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * The fixed retry policy (D111-9): five attempts, the first immediate, then 1 minute, 5 minutes, 30
 * minutes and 2 hours after the previous failure. Production adds a bounded deterministic jitter of
 * up to plus or minus 10 percent derived from the delivery id and attempt number. A provider {@code
 * Retry-After} can lengthen a delay but never shorten it, and is bounded to two hours.
 */
public final class MessagingRetrySchedule {

  /** Maximum number of attempts. */
  public static final int MAX_ATTEMPTS = 5;

  private static final Duration CEILING = Duration.ofHours(2);
  private static final List<Duration> DELAYS =
      List.of(
          Duration.ofMinutes(1),
          Duration.ofMinutes(5),
          Duration.ofMinutes(30),
          Duration.ofHours(2));

  private MessagingRetrySchedule() {}

  /**
   * Delay before the next attempt.
   *
   * @param failedAttempt number of the attempt that just failed, 1 to 4
   * @param deliveryId the delivery (jitter seed)
   * @param jitter whether to apply jitter
   * @param retryAfter the provider's {@code Retry-After}, or null
   * @return the delay
   */
  public static Duration delayAfter(
      int failedAttempt, UUID deliveryId, boolean jitter, Duration retryAfter) {
    if (failedAttempt < 1 || failedAttempt >= MAX_ATTEMPTS) {
      throw new IllegalArgumentException("no attempt follows attempt " + failedAttempt);
    }
    Duration base = DELAYS.get(failedAttempt - 1);
    if (jitter) {
      long seed =
          deliveryId.getLeastSignificantBits()
              ^ (deliveryId.getMostSignificantBits() * 31)
              ^ failedAttempt * 0x9E3779B97F4A7C15L;
      long permille = Math.floorMod(seed, 201L) - 100L;
      base = base.plusMillis(base.toMillis() * permille / 1000L);
    }
    if (retryAfter != null && !retryAfter.isNegative() && retryAfter.compareTo(base) > 0) {
      return retryAfter.compareTo(CEILING) > 0 ? CEILING : retryAfter;
    }
    return base;
  }
}
