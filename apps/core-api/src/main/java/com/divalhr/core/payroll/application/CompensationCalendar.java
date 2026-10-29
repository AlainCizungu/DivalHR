package com.divalhr.core.payroll.application;

import com.divalhr.core.platform.tenancy.OrganizationDirectory;
import com.divalhr.core.platform.tenancy.OrganizationDirectory.OrganizationSummary;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/**
 * The server-owned instant, business date and preview window of monthly compensation (MVP-093A,
 * D93-5 and D93-8): every timestamp comes from the application clock, never from a client; the
 * business date is today in the organization's IANA time zone, read through the tenant module's
 * port.
 */
@Component
public class CompensationCalendar {

  /** The preview window: a preview expires at the end of the next window. */
  static final Duration WINDOW = Duration.ofMinutes(15);

  private final OrganizationDirectory organizations;
  private final Clock clock;

  /**
   * Creates the calendar.
   *
   * @param organizations organization port
   * @param clock the application clock
   */
  public CompensationCalendar(OrganizationDirectory organizations, Clock clock) {
    this.organizations = organizations;
    this.clock = clock;
  }

  /**
   * Now, truncated to microseconds (the database precision).
   *
   * @return now
   */
  public Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  /**
   * The organization's business time zone.
   *
   * @param tenant verified tenant
   * @return the zone
   */
  public ZoneId zone(TenantId tenant) {
    OrganizationSummary organization =
        organizations
            .find(tenant)
            .orElseThrow(() -> new IllegalStateException("organization not found"));
    try {
      return ZoneId.of(organization.timezone());
    } catch (DateTimeException invalid) {
      throw new IllegalStateException("organization time zone is not valid");
    }
  }

  /**
   * Today in the organization's zone.
   *
   * @param tenant verified tenant
   * @return the business date
   */
  public LocalDate today(TenantId tenant) {
    return LocalDate.now(clock.withZone(zone(tenant)));
  }

  /**
   * The end of the window after the current one (15 to 30 minutes from an instant).
   *
   * @param at an instant
   * @return the expiry of a preview made then
   */
  static Instant expiry(Instant at) {
    long window = WINDOW.toMillis();
    long start = Math.floorDiv(at.toEpochMilli(), window) * window;
    return Instant.ofEpochMilli(start + 2 * window);
  }

  /**
   * Whether an instant lies on the 15-minute grid previews expire on.
   *
   * @param at an instant
   * @return true on the grid
   */
  static boolean onGrid(Instant at) {
    return at.getNano() == 0 && Math.floorMod(at.toEpochMilli(), WINDOW.toMillis()) == 0;
  }
}
