package com.divalhr.core.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.divalhr.core.operations.application.TaskQuotas;
import com.divalhr.core.operations.internal.JdbcTaskRepository;
import com.divalhr.core.operations.internal.JdbcTaskRepository.Usage;
import com.divalhr.core.platform.ratelimit.RateLimitedException;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The durable per-subject hourly quotas (MVP-080, D80-11): 60 creations, 120 transitions. */
class TaskQuotasTest {

  private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
  private static final TenantId TENANT = new TenantId(UUID.randomUUID());

  @Test
  void theSixtyFirstCreationInAnHourIsRefusedWithTheTimeUntilTheOldestLeaves() {
    JdbcTaskRepository tasks = mock(JdbcTaskRepository.class);
    TaskQuotas quotas = new TaskQuotas(tasks);
    when(tasks.createdSince(eq(TENANT), eq("admin"), any(Instant.class)))
        .thenReturn(new Usage(59, NOW.minusSeconds(3000)));
    assertThatCode(() -> quotas.requireCreate(TENANT, "admin", NOW)).doesNotThrowAnyException();
    when(tasks.createdSince(eq(TENANT), eq("admin"), any(Instant.class)))
        .thenReturn(new Usage(60, NOW.minusSeconds(3000)));
    assertThatThrownBy(() -> quotas.requireCreate(TENANT, "admin", NOW))
        .isInstanceOfSatisfying(
            RateLimitedException.class,
            e -> assertThat(e.retryAfterSeconds()).isBetween(599L, 601L));
  }

  @Test
  void theOneHundredTwentyFirstTransitionInAnHourIsRefused() {
    JdbcTaskRepository tasks = mock(JdbcTaskRepository.class);
    TaskQuotas quotas = new TaskQuotas(tasks);
    when(tasks.transitionsSince(eq(TENANT), eq("employee"), any(Instant.class)))
        .thenReturn(new Usage(119, NOW.minusSeconds(10)));
    assertThatCode(() -> quotas.requireTransition(TENANT, "employee", NOW))
        .doesNotThrowAnyException();
    when(tasks.transitionsSince(eq(TENANT), eq("employee"), any(Instant.class)))
        .thenReturn(new Usage(120, NOW.minusSeconds(10)));
    assertThatThrownBy(() -> quotas.requireTransition(TENANT, "employee", NOW))
        .isInstanceOf(RateLimitedException.class);
  }
}
