package com.divalhr.core.payroll.domain;

import java.time.Duration;
import java.util.List;

/**
 * The bounded retry policy of provider status inquiries (MVP-095C, ADR 0038 section 6): six
 * attempts, then 30 seconds, 2 minutes, 10 minutes, 1 hour and 6 hours after the previous failure.
 * A retry reuses the same inquiry ID and is allowed only after a transport failure, a 429 or a 5xx
 * before a usable answer. An inquiry is not a payment instruction: repeating it is always safe.
 */
public final class PaymentReconciliationRetry {

  /** Maximum number of inquiry attempts. */
  public static final int MAX_ATTEMPTS = 6;

  private static final List<Duration> DELAYS =
      List.of(
          Duration.ofSeconds(30),
          Duration.ofMinutes(2),
          Duration.ofMinutes(10),
          Duration.ofHours(1),
          Duration.ofHours(6));

  private PaymentReconciliationRetry() {}

  /**
   * Delay before the next attempt.
   *
   * @param failedAttempt number of the attempt that just failed, 1 to 5
   * @return the delay
   */
  public static Duration delayAfter(int failedAttempt) {
    if (failedAttempt < 1 || failedAttempt >= MAX_ATTEMPTS) {
      throw new IllegalArgumentException("no inquiry attempt follows attempt " + failedAttempt);
    }
    return DELAYS.get(failedAttempt - 1);
  }
}
