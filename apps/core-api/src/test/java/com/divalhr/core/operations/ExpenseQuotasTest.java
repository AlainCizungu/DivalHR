package com.divalhr.core.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.divalhr.core.operations.application.ExpenseQuotas;
import com.divalhr.core.operations.internal.JdbcExpenseRepository;
import com.divalhr.core.operations.internal.JdbcExpenseRepository.Usage;
import com.divalhr.core.platform.ratelimit.RateLimitedException;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The durable per-subject hourly quotas (MVP-081, D81-13): 60 writes, 30 receipt changes and 120
 * decisions, counted from the subject's own committed actions.
 */
class ExpenseQuotasTest {

  private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
  private static final TenantId TENANT = new TenantId(UUID.randomUUID());

  private final JdbcExpenseRepository expenses = mock(JdbcExpenseRepository.class);
  private final ExpenseQuotas quotas = new ExpenseQuotas(expenses);

  private void used(int count) {
    when(expenses.actionsSince(eq(TENANT), eq("subject"), anyList(), any(Instant.class)))
        .thenReturn(new Usage(count, NOW.minusSeconds(3000)));
  }

  @Test
  void theSixtyFirstWriteInAnHourIsRefusedWithTheTimeUntilTheOldestLeaves() {
    used(59);
    assertThatCode(() -> quotas.requireWrite(TENANT, "subject", NOW)).doesNotThrowAnyException();
    used(60);
    assertThatThrownBy(() -> quotas.requireWrite(TENANT, "subject", NOW))
        .isInstanceOfSatisfying(
            RateLimitedException.class,
            e -> assertThat(e.retryAfterSeconds()).isBetween(599L, 601L));
  }

  @Test
  void theThirtyFirstReceiptChangeIsRefused() {
    used(29);
    assertThatCode(() -> quotas.requireReceipt(TENANT, "subject", NOW)).doesNotThrowAnyException();
    used(30);
    assertThatThrownBy(() -> quotas.requireReceipt(TENANT, "subject", NOW))
        .isInstanceOf(RateLimitedException.class);
  }

  @Test
  void theOneHundredTwentyFirstDecisionIsRefused() {
    used(119);
    assertThatCode(() -> quotas.requireDecision(TENANT, "subject", NOW)).doesNotThrowAnyException();
    used(120);
    assertThatThrownBy(() -> quotas.requireDecision(TENANT, "subject", NOW))
        .isInstanceOf(RateLimitedException.class);
  }
}
