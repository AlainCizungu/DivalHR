package com.divalhr.core.integrations.partner.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.integrations.partner.privacy.PrivacyQuotas.Quota;
import com.divalhr.core.platform.ratelimit.RateLimitedException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The LAUNCH-001 quotas with a fixed, movable clock: exact limits, exact window edges, one counter
 * per subject, and the time until the window ends. The limits are the architect's and are never
 * weakened to make a test pass.
 */
class PrivacyQuotasTest {

  private static final class Movable extends Clock {
    private Instant now;

    Movable(String instant) {
      this.now = Instant.parse(instant);
    }

    void set(String instant) {
      this.now = Instant.parse(instant);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @Test
  void theLimitsAreExactlyTheApprovedOnes() {
    assertThat(
            Map.of(
                Quota.EMPLOYEE_REQUESTS, 5,
                Quota.PACKAGE_GENERATION, 3,
                Quota.PLATFORM_MUTATIONS, 20,
                Quota.PURGE_PREVIEWS, 10,
                Quota.PURGE_COMMITS, 5))
        .allSatisfy((quota, limit) -> assertThat(quota.limit()).isEqualTo(limit));
  }

  @Test
  void eachQuotaAllowsExactlyItsLimitThenRefusesWithTheRemainingWindow() {
    for (Quota quota : Quota.values()) {
      Movable clock = new Movable("2026-03-10T10:30:00Z");
      PrivacyQuotas quotas = new PrivacyQuotas(clock);
      for (int i = 0; i < quota.limit(); i++) {
        quotas.require(quota, "subject");
      }
      assertThatThrownBy(() -> quotas.require(quota, "subject"))
          .as(quota.name())
          .isInstanceOf(RateLimitedException.class);
    }
  }

  @Test
  void theRetryAfterIsTheTimeLeftInTheFixedWindow() {
    Movable clock = new Movable("2026-03-10T10:30:00Z");
    PrivacyQuotas quotas = new PrivacyQuotas(clock);
    for (int i = 0; i < Quota.PURGE_COMMITS.limit(); i++) {
      quotas.require(Quota.PURGE_COMMITS, "admin");
    }
    assertThatThrownBy(() -> quotas.require(Quota.PURGE_COMMITS, "admin"))
        .isInstanceOfSatisfying(
            RateLimitedException.class,
            refused -> assertThat(refused.retryAfterSeconds()).isEqualTo(30 * 60 + 1));
  }

  @Test
  void theCounterResetsExactlyAtTheWindowEdgeAndNotBefore() {
    Movable clock = new Movable("2026-03-10T10:59:59Z");
    PrivacyQuotas quotas = new PrivacyQuotas(clock);
    for (int i = 0; i < Quota.PURGE_COMMITS.limit(); i++) {
      quotas.require(Quota.PURGE_COMMITS, "admin");
    }
    assertThatThrownBy(() -> quotas.require(Quota.PURGE_COMMITS, "admin"))
        .isInstanceOf(RateLimitedException.class);
    clock.set("2026-03-10T11:00:00Z");
    assertThatCode(() -> quotas.require(Quota.PURGE_COMMITS, "admin")).doesNotThrowAnyException();
  }

  @Test
  void theDailyQuotasResetAtMidnightUtc() {
    Movable clock = new Movable("2026-03-10T23:59:59Z");
    PrivacyQuotas quotas = new PrivacyQuotas(clock);
    for (int i = 0; i < Quota.EMPLOYEE_REQUESTS.limit(); i++) {
      quotas.require(Quota.EMPLOYEE_REQUESTS, "employee");
    }
    assertThatThrownBy(() -> quotas.require(Quota.EMPLOYEE_REQUESTS, "employee"))
        .isInstanceOf(RateLimitedException.class);
    clock.set("2026-03-11T00:00:00Z");
    assertThatCode(() -> quotas.require(Quota.EMPLOYEE_REQUESTS, "employee"))
        .doesNotThrowAnyException();
  }

  @Test
  void everySubjectAndEveryQuotaHasItsOwnCounter() {
    Movable clock = new Movable("2026-03-10T10:30:00Z");
    PrivacyQuotas quotas = new PrivacyQuotas(clock);
    for (int i = 0; i < Quota.PURGE_COMMITS.limit(); i++) {
      quotas.require(Quota.PURGE_COMMITS, "admin-a");
    }
    assertThatCode(() -> quotas.require(Quota.PURGE_COMMITS, "admin-b")).doesNotThrowAnyException();
    assertThatCode(() -> quotas.require(Quota.PURGE_PREVIEWS, "admin-a"))
        .doesNotThrowAnyException();
  }
}
