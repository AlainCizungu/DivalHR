package com.divalhr.core.people.application;

import com.divalhr.core.platform.tenancy.OrganizationDirectory;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Autowired;
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
   */
  @Autowired
  public BusinessCalendar(OrganizationDirectory organizations) {
    this(organizations, Clock.systemUTC());
  }

  BusinessCalendar(OrganizationDirectory organizations, Clock clock) {
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
    ZoneId zone =
        organizations
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
    return LocalDate.now(clock.withZone(zone));
  }
}
