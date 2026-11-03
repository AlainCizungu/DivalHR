package com.divalhr.core.payroll.domain;

import java.time.Duration;
import java.util.List;

/**
 * The bounded retry policy of provider submissions (MVP-095B, D95B-6): six attempts, then 1 minute,
 * 5 minutes, 30 minutes, 2 hours and 12 hours after the previous failure. A retry reuses the same
 * request ID and is allowed only for failures before acceptance.
 */
public final class PaymentSubmissionRetry {

  /** Maximum number of attempts. */
  public static final int MAX_ATTEMPTS = 6;

  private static final List<Duration> DELAYS =
      List.of(
          Duration.ofMinutes(1),
          Duration.ofMinutes(5),
          Duration.ofMinutes(30),
          Duration.ofHours(2),
          Duration.ofHours(12));

  private PaymentSubmissionRetry() {}

  /**
   * Delay before the next attempt.
   *
   * @param failedAttempt number of the attempt that just failed, 1 to 5
   * @return the delay
   */
  public static Duration delayAfter(int failedAttempt) {
    if (failedAttempt < 1 || failedAttempt >= MAX_ATTEMPTS) {
      throw new IllegalArgumentException("no attempt follows attempt " + failedAttempt);
    }
    return DELAYS.get(failedAttempt - 1);
  }
}
