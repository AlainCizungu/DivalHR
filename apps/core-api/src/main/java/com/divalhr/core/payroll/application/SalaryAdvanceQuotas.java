package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceRepository;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceRepository.Usage;
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
 * The hourly per-subject quotas of the salary-advance workflow (MVP-120A, D120A-14): eligibility
 * reads 30, list reads 60, employee previews 10, employee commands 10, employer decisions 20,
 * policy previews 20 and policy commands 20 per verified subject and hour. A command quota counts
 * the subject's own committed work in the database, so it holds across instances and restarts and
 * never counts a replay or a refused command. A read or preview writes no row, so its quota is a
 * fixed-hour counter held by the instance: another instance or a restart starts its own count (the
 * per-minute burst limits and the per-tenant hourly buckets are platform limits shared by the
 * cluster). An excess is {@code 429 RATE_LIMITED} with the time until the window ends.
 */
@Component
public class SalaryAdvanceQuotas {

  /** Eligibility reads per subject per hour. */
  public static final int ELIGIBILITY_PER_HOUR = 30;

  /** List and detail reads per subject per hour. */
  public static final int READS_PER_HOUR = 60;

  /** Employee previews per subject per hour. */
  public static final int PREVIEWS_PER_HOUR = 10;

  /** Employee submissions and withdrawals per subject per hour. */
  public static final int EMPLOYEE_COMMANDS_PER_HOUR = 10;

  /** Employer decisions per subject per hour. */
  public static final int DECISIONS_PER_HOUR = 20;

  /** Policy previews per subject per hour. */
  public static final int POLICY_PREVIEWS_PER_HOUR = 20;

  /** Policy commits and cancellations per subject per hour. */
  public static final int POLICY_COMMANDS_PER_HOUR = 20;

  private static final Duration WINDOW = Duration.ofHours(1);
  private static final int PRUNE_ABOVE = 1_000;

  private final JdbcSalaryAdvanceRepository repository;
  private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();

  /**
   * Creates the quotas.
   *
   * @param repository the committed work to count
   */
  public SalaryAdvanceQuotas(JdbcSalaryAdvanceRepository repository) {
    this.repository = repository;
  }

  private record Counter(Instant windowStart, int count) {}

  /**
   * Refuses a read or preview beyond its hourly quota and counts it otherwise.
   *
   * @param kind the quota name
   * @param limit requests per hour
   * @param tenant verified tenant
   * @param subject verified subject
   * @param now server instant
   */
  void requireCounted(String kind, int limit, TenantId tenant, String subject, Instant now) {
    Instant windowStart = now.truncatedTo(ChronoUnit.HOURS);
    if (counters.size() > PRUNE_ABOVE) {
      counters.values().removeIf(counter -> counter.windowStart().isBefore(windowStart));
    }
    AtomicBoolean refused = new AtomicBoolean(false);
    counters.compute(
        kind + ':' + tenant + ':' + subject,
        (key, current) -> {
          Counter live =
              current == null || current.windowStart().isBefore(windowStart)
                  ? new Counter(windowStart, 0)
                  : current;
          if (live.count() >= limit) {
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
   * Refuses an employee command beyond the hourly quota.
   *
   * @param tenant verified tenant
   * @param subject verified subject
   * @param now server instant
   */
  void requireEmployeeCommand(TenantId tenant, String subject, Instant now) {
    refuseWhenFull(
        repository.eventsSince(tenant, subject, "EMPLOYEE", now.minus(WINDOW)),
        EMPLOYEE_COMMANDS_PER_HOUR,
        now);
  }

  /**
   * Refuses an employer decision beyond the hourly quota.
   *
   * @param tenant verified tenant
   * @param subject verified subject
   * @param now server instant
   */
  void requireDecision(TenantId tenant, String subject, Instant now) {
    refuseWhenFull(
        repository.eventsSince(tenant, subject, "TENANT_ADMIN", now.minus(WINDOW)),
        DECISIONS_PER_HOUR,
        now);
  }

  /**
   * Refuses a policy commit or cancellation beyond the hourly quota.
   *
   * @param tenant verified tenant
   * @param subject verified subject
   * @param now server instant
   */
  void requirePolicyCommand(TenantId tenant, String subject, Instant now) {
    refuseWhenFull(
        repository.policyChangesSince(tenant, subject, now.minus(WINDOW)),
        POLICY_COMMANDS_PER_HOUR,
        now);
  }

  private static void refuseWhenFull(Usage usage, int limit, Instant now) {
    if (usage.count() >= limit) {
      long seconds =
          usage.oldest() == null
              ? WINDOW.toSeconds()
              : Duration.between(now, usage.oldest().plus(WINDOW)).toSeconds() + 1;
      throw new RateLimitedException(ErrorCode.RATE_LIMITED, seconds);
    }
  }
}
