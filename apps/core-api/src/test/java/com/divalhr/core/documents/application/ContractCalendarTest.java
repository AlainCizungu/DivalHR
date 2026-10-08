package com.divalhr.core.documents.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.platform.tenancy.OrganizationDirectory;
import com.divalhr.core.platform.tenancy.OrganizationDirectory.OrganizationSummary;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * MVP-031A (Issue #73, D9): the business date is today in the organization's IANA zone, from the
 * injected clock, including around daylight-saving transitions.
 */
class ContractCalendarTest {

  private static final TenantId TENANT = new TenantId(UUID.randomUUID());

  private static final OrganizationDirectory NONE =
      new OrganizationDirectory() {
        @Override
        public Optional<OrganizationSummary> find(TenantId tenant) {
          return Optional.empty();
        }

        @Override
        public Optional<OrganizationSummary> lockForTenantAdministration(
            TenantId tenant, Duration timeout) {
          return Optional.empty();
        }
      };

  private static LocalDate today(String instant, String zone) {
    ContractCalendar calendar =
        new ContractCalendar(NONE, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    return calendar.today(new OrganizationSummary(TENANT, "Org", "fr", zone));
  }

  @Test
  void theSameInstantIsADifferentBusinessDateInEachSupportedZone() {
    assertThat(today("2026-10-07T22:30:00Z", "Africa/Kinshasa")).isEqualTo("2026-10-07");
    assertThat(today("2026-10-07T22:30:00Z", "Africa/Lubumbashi")).isEqualTo("2026-10-08");
    assertThat(today("2026-10-07T22:59:59Z", "Africa/Kinshasa")).isEqualTo("2026-10-07");
    assertThat(today("2026-10-07T23:00:00Z", "Africa/Kinshasa")).isEqualTo("2026-10-08");
  }

  @Test
  void daylightSavingTransitionsKeepTheLocalCalendarDate() {
    // Europe/Paris: 2026-03-29 has 23 hours, 2026-10-25 has 25 hours.
    assertThat(today("2026-03-28T22:59:59Z", "Europe/Paris")).isEqualTo("2026-03-28");
    assertThat(today("2026-03-28T23:00:00Z", "Europe/Paris")).isEqualTo("2026-03-29");
    assertThat(today("2026-03-29T21:59:59Z", "Europe/Paris")).isEqualTo("2026-03-29");
    assertThat(today("2026-03-29T22:00:00Z", "Europe/Paris")).isEqualTo("2026-03-30");
    assertThat(today("2026-10-24T21:59:59Z", "Europe/Paris")).isEqualTo("2026-10-24");
    assertThat(today("2026-10-24T22:00:00Z", "Europe/Paris")).isEqualTo("2026-10-25");
    assertThat(today("2026-10-25T22:59:59Z", "Europe/Paris")).isEqualTo("2026-10-25");
    assertThat(today("2026-10-25T23:00:00Z", "Europe/Paris")).isEqualTo("2026-10-26");
  }

  @Test
  void anInvalidZoneFailsClosed() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> today("2026-10-07T12:00:00Z", "Nope/X"))
        .isInstanceOf(IllegalStateException.class);
  }
}
