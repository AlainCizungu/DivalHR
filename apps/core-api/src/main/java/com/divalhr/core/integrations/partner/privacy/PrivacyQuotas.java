package com.divalhr.core.integrations.partner.privacy;

import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.ratelimit.RateLimitedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * The fixed-window quotas of LAUNCH-001 (ADR 0046, L10): employee requests 5 per employee per day,
 * access-package generation 3 per request per day, platform mutations 20 per administrator per
 * hour, purge previews 10 and purge commits 5 per administrator per hour. Counters are held by the
 * instance and driven by an injectable clock, so tests are deterministic. An excess is {@code 429
 * PRIVACY_RATE_LIMITED} with the time until the window ends.
 */
@Component
public class PrivacyQuotas {

  /** A quota: its name, limit and window. */
  public enum Quota {
    EMPLOYEE_REQUESTS(5, ChronoUnit.DAYS),
    PACKAGE_GENERATION(3, ChronoUnit.DAYS),
    PLATFORM_MUTATIONS(20, ChronoUnit.HOURS),
    PURGE_PREVIEWS(10, ChronoUnit.HOURS),
    PURGE_COMMITS(5, ChronoUnit.HOURS);

    private final int limit;
    private final ChronoUnit unit;

    Quota(int limit, ChronoUnit unit) {
      this.limit = limit;
      this.unit = unit;
    }

    /** Maximum count per window. */
    public int limit() {
      return limit;
    }
  }

  private static final int PRUNE_ABOVE = 2_000;

  private final Clock clock;
  private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();

  private record Counter(Instant windowStart, int count) {}

  /**
   * Creates the quotas.
   *
   * @param clock server clock
   */
  public PrivacyQuotas(Clock clock) {
    this.clock = clock;
  }

  /**
   * Counts one use of a quota for a subject and refuses it beyond the limit.
   *
   * @param quota the quota
   * @param subject the employee, request or administrator the quota is per
   */
  public void require(Quota quota, String subject) {
    Instant now = clock.instant();
    Instant start = now.truncatedTo(quota.unit);
    if (counters.size() > PRUNE_ABOVE) {
      counters.values().removeIf(c -> c.windowStart().isBefore(start.minus(1, ChronoUnit.DAYS)));
    }
    AtomicBoolean refused = new AtomicBoolean(false);
    counters.compute(
        quota.name() + ':' + subject,
        (key, current) -> {
          Counter live =
              current == null || current.windowStart().isBefore(start)
                  ? new Counter(start, 0)
                  : current;
          if (live.count() >= quota.limit) {
            refused.set(true);
            return live;
          }
          return new Counter(live.windowStart(), live.count() + 1);
        });
    if (refused.get()) {
      long seconds = Duration.between(now, start.plus(1, quota.unit)).toSeconds() + 1;
      throw new RateLimitedException(ErrorCode.PRIVACY_RATE_LIMITED, seconds);
    }
  }
}
