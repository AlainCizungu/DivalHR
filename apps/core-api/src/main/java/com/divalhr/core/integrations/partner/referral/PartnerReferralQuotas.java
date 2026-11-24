package com.divalhr.core.integrations.partner.referral;

import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.ratelimit.RateLimitedException;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * The hourly per-employee quotas of the consented referral (MVP-120F, D120F-9): previews 20,
 * commits 5 and withdrawals 10 per employee per hour, each its own bucket. A preview writes no row,
 * so its quota is a fixed-hour counter held by the instance (the per-minute subject limits are
 * shared by the cluster). Commits and withdrawals are counted from the employee's own committed
 * rows, so they hold across instances and restarts and never count a replay or a refused command.
 * An excess is {@code 429 PARTNER_REFERRAL_RATE_LIMITED} with the time until the window ends.
 */
@Component
public class PartnerReferralQuotas {

  /** Previews per employee per hour. */
  public static final int PREVIEWS_PER_HOUR = 20;

  /** Commits per employee per hour. */
  public static final int COMMITS_PER_HOUR = 5;

  /** Withdrawals per employee per hour. */
  public static final int WITHDRAWALS_PER_HOUR = 10;

  private static final Duration WINDOW = Duration.ofHours(1);
  private static final int PRUNE_ABOVE = 1_000;

  private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();

  private record Counter(Instant windowStart, int count) {}

  /**
   * Counts a preview and refuses it beyond the hourly quota.
   *
   * @param tenant verified tenant
   * @param subject verified subject
   * @param now server instant
   */
  void requirePreview(TenantId tenant, String subject, Instant now) {
    Instant windowStart = now.truncatedTo(ChronoUnit.HOURS);
    if (counters.size() > PRUNE_ABOVE) {
      counters.values().removeIf(counter -> counter.windowStart().isBefore(windowStart));
    }
    AtomicBoolean refused = new AtomicBoolean(false);
    counters.compute(
        "preview:" + tenant + ':' + subject,
        (key, current) -> {
          Counter live =
              current == null || current.windowStart().isBefore(windowStart)
                  ? new Counter(windowStart, 0)
                  : current;
          if (live.count() >= PREVIEWS_PER_HOUR) {
            refused.set(true);
            return live;
          }
          return new Counter(live.windowStart(), live.count() + 1);
        });
    if (refused.get()) {
      throw refusal(now, windowStart);
    }
  }

  /**
   * Refuses a command when the employee already committed {@code limit} in the window.
   *
   * @param committed the employee's own committed commands since the window start
   * @param limit the hourly limit
   * @param now server instant
   */
  void requireCommands(long committed, int limit, Instant now) {
    if (committed >= limit) {
      throw refusal(now, now.truncatedTo(ChronoUnit.HOURS));
    }
  }

  /**
   * The start of the hourly window that contains an instant.
   *
   * @param now server instant
   * @return the window start
   */
  static Instant windowStart(Instant now) {
    return now.truncatedTo(ChronoUnit.HOURS);
  }

  private static RateLimitedException refusal(Instant now, Instant windowStart) {
    long seconds = Duration.between(now, windowStart.plus(WINDOW)).toSeconds() + 1;
    return new RateLimitedException(ErrorCode.PARTNER_REFERRAL_RATE_LIMITED, seconds);
  }
}
