package com.divalhr.core.payroll.application;

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
 * The per-subject hourly preview quota of the accounting export (MVP-096, ADR 0039 section 11): 20
 * previews per verified subject and hour, shared by the mapping and the journal preview. The
 * subject limiter of the platform counts per minute only, so, as for monthly compensation (ADR
 * 0032), the hourly quota is a fixed-hour counter held by the instance (a restart or another
 * instance starts its own count); the per-minute burst bucket and the per-tenant hourly buckets are
 * the platform limits shared by the cluster. An excess is {@code 429 RATE_LIMITED} with the time
 * until the window ends.
 */
@Component
public class AccountingQuotas {

  /** Previews per subject per hour. */
  public static final int PREVIEWS_PER_HOUR = 20;

  private static final Duration WINDOW = Duration.ofHours(1);
  private static final int PRUNE_ABOVE = 1_000;

  private final ConcurrentHashMap<String, Counter> previews = new ConcurrentHashMap<>();

  private record Counter(Instant windowStart, int count) {}

  /**
   * Refuses a preview beyond the hourly quota and counts it otherwise.
   *
   * @param tenant verified tenant
   * @param subject verified subject
   * @param now server instant
   */
  public void requirePreview(TenantId tenant, String subject, Instant now) {
    Instant windowStart = now.truncatedTo(ChronoUnit.HOURS);
    if (previews.size() > PRUNE_ABOVE) {
      previews.values().removeIf(counter -> counter.windowStart().isBefore(windowStart));
    }
    AtomicBoolean refused = new AtomicBoolean(false);
    previews.compute(
        tenant + ":" + subject,
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
      long seconds = Duration.between(now, windowStart.plus(WINDOW)).toSeconds() + 1;
      throw new RateLimitedException(ErrorCode.RATE_LIMITED, seconds);
    }
  }
}
