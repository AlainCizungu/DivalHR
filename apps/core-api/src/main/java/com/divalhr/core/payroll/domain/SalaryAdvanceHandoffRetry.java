package com.divalhr.core.payroll.domain;

import java.time.Duration;
import java.util.List;

/**
 * The bounded retry policy of a partner handoff (ADR 0042 §6): six automatic attempts per round,
 * then 1 minute, 5 minutes, 30 minutes, 2 hours and 12 hours after the previous transient failure.
 * A retry reuses the same application id and applies only to failures where nothing could have been
 * delivered.
 */
public final class SalaryAdvanceHandoffRetry {

  /** Maximum automatic attempts in one round. */
  public static final int MAX_ATTEMPTS = 6;

  /** Maximum administrator retries and reconciles per handoff. */
  public static final int MAX_ADMIN_ACTIONS = 3;

  private static final List<Duration> DELAYS =
      List.of(
          Duration.ofMinutes(1),
          Duration.ofMinutes(5),
          Duration.ofMinutes(30),
          Duration.ofHours(2),
          Duration.ofHours(12));

  private SalaryAdvanceHandoffRetry() {}

  /**
   * Delay before the next attempt.
   *
   * @param failedAttemptInRound number of the attempt that just failed in this round, 1 to 5
   * @return the delay
   */
  public static Duration delayAfter(int failedAttemptInRound) {
    if (failedAttemptInRound < 1 || failedAttemptInRound >= MAX_ATTEMPTS) {
      throw new IllegalArgumentException("no attempt follows attempt " + failedAttemptInRound);
    }
    return DELAYS.get(failedAttemptInRound - 1);
  }
}
