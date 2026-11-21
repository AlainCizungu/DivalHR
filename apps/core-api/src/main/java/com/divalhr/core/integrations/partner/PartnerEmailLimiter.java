package com.divalhr.core.integrations.partner;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Per-business-email submission limit (D120D-10). It counts attempts by the keyed email digest only
 * and in memory only: the digest is dropped at every window boundary and the raw address never
 * reaches it. It never consults stored applications, so a refusal says nothing about whether an
 * applicant already exists. In-process and per instance, like the other public limiters.
 */
@Component
public class PartnerEmailLimiter {

  /** Window length. */
  static final Duration WINDOW = Duration.ofHours(1);

  /** Digests remembered per window before new ones are refused (memory safety). */
  static final int MAX_TRACKED = 10_000;

  private final Clock clock;
  private final int maxAttempts;
  private final Object lock = new Object();
  private final Map<String, Integer> attempts = new ConcurrentHashMap<>();
  private long windowIndex = Long.MIN_VALUE;

  /**
   * Creates the limiter.
   *
   * @param clock clock
   * @param maxAttempts attempts allowed per email digest per hour (1 to 1,000; default 5)
   */
  @Autowired
  public PartnerEmailLimiter(
      Clock clock,
      @Value("${divalhr.partner-application.email-attempts-per-hour:5}") int maxAttempts) {
    if (maxAttempts < 1 || maxAttempts > 1000) {
      throw new IllegalStateException(
          "divalhr.partner-application.email-attempts-per-hour must be 1..1000");
    }
    this.clock = clock;
    this.maxAttempts = maxAttempts;
  }

  /**
   * Counts one attempt.
   *
   * @param emailDigest keyed digest of the normalized email
   * @return seconds until the window ends when the attempt is refused, or {@code 0} when allowed
   */
  public long acquire(String emailDigest) {
    Instant now = Instant.now(clock);
    long index = Math.floorDiv(now.toEpochMilli(), WINDOW.toMillis());
    synchronized (lock) {
      if (index != windowIndex) {
        windowIndex = index;
        attempts.clear();
      }
      if (!attempts.containsKey(emailDigest) && attempts.size() >= MAX_TRACKED) {
        return secondsLeft(index, now);
      }
      int total = attempts.merge(emailDigest, 1, Integer::sum);
      return total > maxAttempts ? secondsLeft(index, now) : 0;
    }
  }

  private static long secondsLeft(long index, Instant now) {
    long end = (index + 1) * WINDOW.toMillis();
    return Math.max(1, (end - now.toEpochMilli() + 999) / 1000);
  }
}
