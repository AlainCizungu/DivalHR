package com.divalhr.core.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.people.application.BusinessCalendar;
import com.divalhr.core.platform.tenancy.OrganizationDirectory;
import com.divalhr.core.platform.tenancy.OrganizationDirectory.OrganizationSummary;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * MVP-040A (D40A-4): the business date comes from the injected application clock in the
 * organization's time zone, so local-midnight boundaries are testable with a fixed clock.
 */
class BusinessCalendarTest {

  private static final TenantId TENANT = new TenantId(UUID.randomUUID());

  private static BusinessCalendar calendar(String timezone, Instant now) {
    OrganizationDirectory directory =
        new OrganizationDirectory() {
          @Override
          public Optional<OrganizationSummary> find(TenantId tenant) {
            return tenant.equals(TENANT)
                ? Optional.of(new OrganizationSummary(TENANT, "Org", "fr", timezone))
                : Optional.empty();
          }

          @Override
          public Optional<OrganizationSummary> lockForTenantAdministration(
              TenantId tenant, Duration timeout) {
            throw new UnsupportedOperationException();
          }
        };
    return new BusinessCalendar(directory, Clock.fixed(now, ZoneOffset.UTC));
  }

  @Test
  void localMidnightDecidesTheBusinessDateInEachTimeZone() {
    Instant beforeKinshasaMidnight = Instant.parse("2026-08-31T22:59:59Z");
    Instant kinshasaMidnight = Instant.parse("2026-08-31T23:00:00Z");
    assertThat(calendar("Africa/Kinshasa", beforeKinshasaMidnight).today(TENANT))
        .isEqualTo(LocalDate.of(2026, 8, 31));
    assertThat(calendar("Africa/Kinshasa", kinshasaMidnight).today(TENANT))
        .isEqualTo(LocalDate.of(2026, 9, 1));
    // Lubumbashi is one hour ahead: its day has already started at 22:00Z.
    assertThat(calendar("Africa/Lubumbashi", Instant.parse("2026-08-31T21:59:59Z")).today(TENANT))
        .isEqualTo(LocalDate.of(2026, 8, 31));
    assertThat(calendar("Africa/Lubumbashi", Instant.parse("2026-08-31T22:00:00Z")).today(TENANT))
        .isEqualTo(LocalDate.of(2026, 9, 1));
  }

  @Test
  void theZoneAndNowComeFromTheOrganizationAndTheInjectedClock() {
    Instant now = Instant.parse("2026-01-15T12:00:00Z");
    BusinessCalendar calendar = calendar("Africa/Lubumbashi", now);
    ZoneId zone = calendar.zone(TENANT);
    assertThat(zone.getId()).isEqualTo("Africa/Lubumbashi");
    assertThat(calendar.today(zone)).isEqualTo(LocalDate.of(2026, 1, 15));
    assertThat(calendar.now()).isEqualTo(now);
    assertThat(calendar.startOf(TENANT, LocalDate.of(2026, 1, 16)))
        .isEqualTo(Instant.parse("2026-01-15T22:00:00Z"));
  }

  @Test
  void anUnknownOrganizationOrAnInvalidZoneFailsClosed() {
    BusinessCalendar calendar = calendar("Not/AZone", Instant.now());
    assertThatThrownBy(() -> calendar.today(TENANT)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> calendar.today(new TenantId(UUID.randomUUID())))
        .isInstanceOf(IllegalStateException.class);
  }
}
