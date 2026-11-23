package com.divalhr.core.integrations.partner.referral;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.ratelimit.RateLimitedException;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** MVP-120F hourly quotas at fixed instants: deterministic boundaries, no wall clock. */
class PartnerReferralQuotasTest {

  private static final Instant HOUR = Instant.parse("2026-11-23T10:00:00Z");

  private final TenantId tenant = new TenantId(UUID.randomUUID());

  @Test
  void theTwentyFirstPreviewInAnHourIsRefusedAndTheNextHourStartsFresh() {
    PartnerReferralQuotas quotas = new PartnerReferralQuotas();
    for (int i = 0; i < 20; i++) {
      quotas.requirePreview(tenant, "sub-a", HOUR.plusSeconds(i));
    }
    assertThatThrownBy(() -> quotas.requirePreview(tenant, "sub-a", HOUR.plusSeconds(3599)))
        .isInstanceOfSatisfying(
            RateLimitedException.class,
            refused -> {
              assertThat(refused.code()).isEqualTo(ErrorCode.PARTNER_REFERRAL_RATE_LIMITED);
              assertThat(refused.retryAfterSeconds()).isEqualTo(2);
            });
    assertThatCode(() -> quotas.requirePreview(tenant, "sub-a", HOUR.plusSeconds(3600)))
        .doesNotThrowAnyException();
  }

  @Test
  void eachEmployeeAndEachTenantHasItsOwnPreviewCount() {
    PartnerReferralQuotas quotas = new PartnerReferralQuotas();
    for (int i = 0; i < 20; i++) {
      quotas.requirePreview(tenant, "sub-a", HOUR);
    }
    assertThatCode(() -> quotas.requirePreview(tenant, "sub-b", HOUR)).doesNotThrowAnyException();
    assertThatCode(() -> quotas.requirePreview(new TenantId(UUID.randomUUID()), "sub-a", HOUR))
        .doesNotThrowAnyException();
  }

  @Test
  void commandQuotasRefuseExactlyAtTheirLimit() {
    PartnerReferralQuotas quotas = new PartnerReferralQuotas();
    Instant now = HOUR.plusSeconds(100);
    assertThatCode(() -> quotas.requireCommands(4, PartnerReferralQuotas.COMMITS_PER_HOUR, now))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> quotas.requireCommands(5, PartnerReferralQuotas.COMMITS_PER_HOUR, now))
        .isInstanceOf(RateLimitedException.class);
    assertThatCode(() -> quotas.requireCommands(9, PartnerReferralQuotas.WITHDRAWALS_PER_HOUR, now))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () -> quotas.requireCommands(10, PartnerReferralQuotas.WITHDRAWALS_PER_HOUR, now))
        .isInstanceOf(RateLimitedException.class);
    assertThat(PartnerReferralQuotas.windowStart(now)).isEqualTo(HOUR);
  }
}
