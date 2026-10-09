package com.divalhr.core.people.application;

import com.divalhr.core.platform.tenancy.OrganizationDirectory;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/**
 * The business date (H7): today in the organization's IANA time zone, read through the tenant
 * module's {@link OrganizationDirectory} port. It is the single authority for statuses, timing and
 * the retroactive window.
 */
@Component
public class BusinessCalendar {

  private final OrganizationDirectory organizations;
  private final Clock clock;

  /**
   * Creates the calendar.
   *
   * @param organizations organization port
   * @param clock the application clock (platform {@code ClockConfiguration}; MVP-040A, D40A-4)
   */
  public BusinessCalendar(OrganizationDirectory organizations, Clock clock) {
    this.organizations = organizations;
    this.clock = clock;
  }

  /**
   * Today for a tenant.
   *
   * @param tenant verified tenant
   * @return the business date
   */
  public LocalDate today(TenantId tenant) {
    return today(zone(tenant));
  }

  /**
   * Today in a time zone already read with {@link #zone(TenantId)}, so that a response's business
   * date and time zone come from one read.
   *
   * @param zone the organization's time zone
   * @return the business date
   */
  public LocalDate today(ZoneId zone) {
    return LocalDate.now(clock.withZone(zone));
  }

  /**
   * The instant a business day starts for a tenant (MVP-022: a separation is effective at the start
   * of the day after the last day).
   *
   * @param tenant verified tenant
   * @param day business date
   * @return its first instant in the organization's time zone
   */
  public java.time.Instant startOf(TenantId tenant, LocalDate day) {
    return day.atStartOfDay(zone(tenant)).toInstant();
  }

  /**
   * The current instant of the calendar's clock.
   *
   * @return now
   */
  public java.time.Instant now() {
    return clock.instant();
  }

  /**
   * The organization's IANA time zone.
   *
   * @param tenant verified tenant
   * @return the zone
   */
  public ZoneId zone(TenantId tenant) {
    return organizations
        .find(tenant)
        .map(
            summary -> {
              try {
                return ZoneId.of(summary.timezone());
              } catch (DateTimeException invalid) {
                throw new IllegalStateException("organization time zone is not valid");
              }
            })
        .orElseThrow(() -> new IllegalStateException("organization not found"));
  }
}
