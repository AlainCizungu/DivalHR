package com.divalhr.core.operations.application;

import com.divalhr.core.platform.tenancy.OrganizationDirectory;
import com.divalhr.core.platform.tenancy.OrganizationDirectory.OrganizationSummary;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The server-owned instant and business date of operations (MVP-080, D80-5): every timestamp comes
 * from the application clock, never from a client; the business date is today in the organization's
 * IANA time zone, read through the tenant module's port.
 */
@Component
public class OperationsCalendar {

  private final OrganizationDirectory organizations;
  private final Clock clock;

  /**
   * Creates the calendar.
   *
   * @param organizations organization port
   * @param clock the application clock
   */
  @Autowired
  public OperationsCalendar(OrganizationDirectory organizations, Clock clock) {
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
   * Today for a tenant (the business date of the assignee's active employment).
   *
   * @param tenant verified tenant
   * @return the business date
   */
  public LocalDate today(TenantId tenant) {
    OrganizationSummary organization =
        organizations
            .find(tenant)
            .orElseThrow(() -> new IllegalStateException("organization not found"));
    try {
      return LocalDate.now(clock.withZone(ZoneId.of(organization.timezone())));
    } catch (DateTimeException invalid) {
      throw new IllegalStateException("organization time zone is not valid");
    }
  }
}
