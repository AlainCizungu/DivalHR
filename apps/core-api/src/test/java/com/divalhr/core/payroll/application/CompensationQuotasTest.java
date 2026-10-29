package com.divalhr.core.payroll.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.divalhr.core.payroll.internal.JdbcCompensationRepository;
import com.divalhr.core.payroll.internal.JdbcCompensationRepository.Usage;
import com.divalhr.core.platform.ratelimit.RateLimitedException;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Hourly quotas of the compensation commands: previews in process, commands from the evidence. */
class CompensationQuotasTest {

  private static final TenantId TENANT = TenantId.parse("11111111-1111-4111-8111-111111111111");
  private static final Instant NOW = Instant.parse("2026-10-29T10:20:00Z");

  @Test
  void theSixtyFirstPreviewOfTheHourIsRefusedAndTheNextHourStartsAgain() {
    CompensationQuotas quotas = new CompensationQuotas(mock(JdbcCompensationRepository.class));
    assertThat(CompensationQuotas.PREVIEWS_PER_HOUR).isEqualTo(60);
    assertThat(CompensationQuotas.COMMANDS_PER_HOUR).isEqualTo(30);
    for (int i = 0; i < 60; i++) {
      quotas.requirePreview(TENANT, "subject-a", NOW);
    }
    assertThatThrownBy(() -> quotas.requirePreview(TENANT, "subject-a", NOW))
        .isInstanceOfSatisfying(
            RateLimitedException.class, e -> assertThat(e.retryAfterSeconds()).isEqualTo(2401));
    assertThatCode(() -> quotas.requirePreview(TENANT, "subject-b", NOW))
        .doesNotThrowAnyException();
    assertThatCode(() -> quotas.requirePreview(TENANT, "subject-a", NOW.plusSeconds(2400)))
        .doesNotThrowAnyException();
  }

  @Test
  void commandsAreCountedFromTheEvidenceOfTheLastHour() {
    JdbcCompensationRepository repository = mock(JdbcCompensationRepository.class);
    CompensationQuotas quotas = new CompensationQuotas(repository);
    when(repository.changesSince(eq(TENANT), eq("subject-a"), any()))
        .thenReturn(new Usage(CompensationQuotas.COMMANDS_PER_HOUR - 1, NOW.minusSeconds(60)));
    assertThatCode(() -> quotas.requireCommand(TENANT, "subject-a", NOW))
        .doesNotThrowAnyException();
    when(repository.changesSince(eq(TENANT), eq("subject-a"), any()))
        .thenReturn(new Usage(CompensationQuotas.COMMANDS_PER_HOUR, NOW.minusSeconds(600)));
    assertThatThrownBy(() -> quotas.requireCommand(TENANT, "subject-a", NOW))
        .isInstanceOfSatisfying(
            RateLimitedException.class, e -> assertThat(e.retryAfterSeconds()).isEqualTo(3001));
  }
}
