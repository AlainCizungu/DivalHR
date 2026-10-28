package com.divalhr.core.operations.application;

import com.divalhr.core.operations.domain.ExpenseActionType;
import com.divalhr.core.operations.internal.JdbcExpenseRepository;
import com.divalhr.core.operations.internal.JdbcExpenseRepository.Usage;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.ratelimit.RateLimitedException;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The durable per-subject hourly quotas of expenses (MVP-081, D81-13): 60 create, update, submit
 * and withdraw actions, 30 receipt mutations and 120 manager decisions per verified subject and
 * hour. They count the subject's own committed actions in the database, so they hold across
 * instances and restarts and never count a replay or a refused command. The per-tenant hourly
 * quotas (600, 300 and 600) and the per-minute burst limits are the platform buckets. Called inside
 * the command's transaction, for a new execution only; an excess is {@code 429 RATE_LIMITED} with
 * the time until the oldest counted item leaves the window.
 */
@Component
public class ExpenseQuotas {

  /** Create, update, submit and withdraw actions per subject per hour. */
  public static final int WRITES_PER_HOUR = 60;

  /** Receipt mutations per subject per hour. */
  public static final int RECEIPTS_PER_HOUR = 30;

  /** Manager decisions per subject per hour. */
  public static final int DECISIONS_PER_HOUR = 120;

  private static final Duration WINDOW = Duration.ofHours(1);
  private static final List<ExpenseActionType> WRITES =
      List.of(
          ExpenseActionType.CREATED,
          ExpenseActionType.DRAFT_UPDATED,
          ExpenseActionType.SUBMITTED,
          ExpenseActionType.WITHDRAWN);
  private static final List<ExpenseActionType> RECEIPTS =
      List.of(ExpenseActionType.RECEIPT_SET, ExpenseActionType.RECEIPT_REMOVED);
  private static final List<ExpenseActionType> DECISIONS =
      List.of(ExpenseActionType.APPROVED, ExpenseActionType.RETURNED);

  private final JdbcExpenseRepository expenses;

  /**
   * Creates the quotas.
   *
   * @param expenses expense repository holding the committed work to count
   */
  public ExpenseQuotas(JdbcExpenseRepository expenses) {
    this.expenses = expenses;
  }

  /**
   * Refuses a create, update, submit or withdraw beyond the hourly quota.
   *
   * @param tenant verified tenant
   * @param subject verified subject
   * @param now server instant
   */
  public void requireWrite(TenantId tenant, String subject, Instant now) {
    check(expenses.actionsSince(tenant, subject, WRITES, now.minus(WINDOW)), WRITES_PER_HOUR, now);
  }

  /**
   * Refuses a receipt mutation beyond the hourly quota.
   *
   * @param tenant verified tenant
   * @param subject verified subject
   * @param now server instant
   */
  public void requireReceipt(TenantId tenant, String subject, Instant now) {
    check(
        expenses.actionsSince(tenant, subject, RECEIPTS, now.minus(WINDOW)),
        RECEIPTS_PER_HOUR,
        now);
  }

  /**
   * Refuses an approval or return beyond the hourly quota.
   *
   * @param tenant verified tenant
   * @param subject verified subject
   * @param now server instant
   */
  public void requireDecision(TenantId tenant, String subject, Instant now) {
    check(
        expenses.actionsSince(tenant, subject, DECISIONS, now.minus(WINDOW)),
        DECISIONS_PER_HOUR,
        now);
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
