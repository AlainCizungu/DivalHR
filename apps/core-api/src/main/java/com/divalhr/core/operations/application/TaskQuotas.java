package com.divalhr.core.operations.application;

import com.divalhr.core.operations.internal.JdbcTaskRepository;
import com.divalhr.core.operations.internal.JdbcTaskRepository.Usage;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.ratelimit.RateLimitedException;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * The durable per-subject hourly quotas of tasks (MVP-080, D80-11): 60 creations and 120 starts,
 * completions and cancellations combined, per verified subject and hour. They count the subject's
 * own committed work in the database, so they hold across instances and restarts and never count a
 * replay or a refused command. The per-tenant hourly quotas (300 and 600) and the per-minute burst
 * limits are the platform buckets. Called inside the command's transaction, for a new execution
 * only; an excess is {@code 429 RATE_LIMITED} with the time until the oldest counted item leaves
 * the window.
 */
@Component
public class TaskQuotas {

  /** Creations per subject per hour. */
  public static final int CREATES_PER_HOUR = 60;

  /** Starts, completions and cancellations per subject per hour, combined. */
  public static final int TRANSITIONS_PER_HOUR = 120;

  private static final Duration WINDOW = Duration.ofHours(1);

  private final JdbcTaskRepository tasks;

  /**
   * Creates the quotas.
   *
   * @param tasks task repository holding the committed work to count
   */
  public TaskQuotas(JdbcTaskRepository tasks) {
    this.tasks = tasks;
  }

  /**
   * Refuses a creation beyond the hourly quota.
   *
   * @param tenant verified tenant
   * @param subject verified subject
   * @param now server instant
   */
  public void requireCreate(TenantId tenant, String subject, Instant now) {
    check(tasks.createdSince(tenant, subject, now.minus(WINDOW)), CREATES_PER_HOUR, now);
  }

  /**
   * Refuses a start, completion or cancellation beyond the hourly quota.
   *
   * @param tenant verified tenant
   * @param subject verified subject
   * @param now server instant
   */
  public void requireTransition(TenantId tenant, String subject, Instant now) {
    check(tasks.transitionsSince(tenant, subject, now.minus(WINDOW)), TRANSITIONS_PER_HOUR, now);
  }

  private static void check(Usage usage, int limit, Instant now) {
    if (usage.count() >= limit) {
      long seconds =
          usage.oldest() == null
              ? WINDOW.toSeconds()
              : Duration.between(now, usage.oldest().plus(WINDOW)).toSeconds() + 1;
      throw new RateLimitedException(ErrorCode.RATE_LIMITED, seconds);
    }
  }
}
