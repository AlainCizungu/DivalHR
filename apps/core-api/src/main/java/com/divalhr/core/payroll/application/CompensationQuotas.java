package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.internal.JdbcCompensationRepository;
import com.divalhr.core.payroll.internal.JdbcCompensationRepository.Usage;
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
 * The per-subject hourly quotas of monthly compensation (MVP-093A, D93-10): 60 previews and 30
 * commands or cancellations per verified subject and hour. Commands count the subject's own
 * committed changes in the database, so they hold across instances and restarts and never count a
 * replay or a refused command. A preview writes no row, so its quota is a fixed-hour counter held
 * by the instance (a restart or another instance starts its own count; the per-minute burst
 * limits and the per-tenant hourly buckets are platform limits shared by the cluster). Called
 * inside the command's transaction for a new execution only; an excess is {@code 429
 * RATE_LIMITED} with the time until the window ends.
 */
@Component
public class CompensationQuotas {

  /** Previews per subject per hour. */
  public static final int PREVIEWS_PER_HOUR = 60;

  /** Commits and cancellations per subject per hour. */
  public static final int COMMANDS_PER_HOUR = 30;

  private static final Duration WINDOW = Duration.ofHours(1);
  private static final int PRUNE_ABOVE = 1_000;

  private final JdbcCompensationRepository compensation;
  private final ConcurrentHashMap<String, Counter> previews = new ConcurrentHashMap<>();

  /**
   * Creates the quotas.
   *
   * @param compensation compensation repository holding the committed work to count
   */
  public CompensationQuotas(JdbcCompensationRepository compensation) {
    this.compensation = compensation;
  }

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

  /**
   * Refuses a commit or cancellation beyond the hourly quota.
   *
   * @param tenant verified tenant
   * @param subject verified subject
   * @param now server instant
   */
  public void requireCommand(TenantId tenant, String subject, Instant now) {
    Usage usage = compensation.changesSince(tenant, subject, now.minus(WINDOW));
    if (usage.count() >= COMMANDS_PER_HOUR) {
      long seconds =
          usage.oldest() == null
              ? WINDOW.toSeconds()
              : Duration.between(now, usage.oldest().plus(WINDOW)).toSeconds() + 1;
      throw new RateLimitedException(ErrorCode.RATE_LIMITED, seconds);
    }
  }
}
