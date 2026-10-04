package com.divalhr.core.documents.application;

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
 * The business date of contracts: today in the organization's IANA time zone, through the tenant
 * module's {@link OrganizationDirectory} port (the documents module never reads tenant tables).
 */
@Component
public class ContractCalendar {

  private final OrganizationDirectory organizations;
  private final Clock clock;

  /**
   * Creates the calendar.
   *
   * @param organizations organization port
   */
  @Autowired
  public ContractCalendar(OrganizationDirectory organizations) {
    this(organizations, Clock.systemUTC());
  }

  ContractCalendar(OrganizationDirectory organizations, Clock clock) {
    this.organizations = organizations;
    this.clock = clock;
  }

  /**
   * The organization of the tenant (its display name fills {@code organization.name}).
   *
   * @param tenant verified tenant
   * @return the organization
   */
  public OrganizationSummary organization(TenantId tenant) {
    return organizations
        .find(tenant)
        .orElseThrow(() -> new IllegalStateException("organization not found"));
  }

  /**
   * Today for a tenant.
   *
   * @param organization the tenant's organization
   * @return the business date
   */
  public LocalDate today(OrganizationSummary organization) {
    try {
      return LocalDate.now(clock.withZone(ZoneId.of(organization.timezone())));
    } catch (DateTimeException invalid) {
      throw new IllegalStateException("organization time zone is not valid");
    }
  }

  /**
   * Now, truncated to microseconds (the database precision).
   *
   * @return now
   */
  public Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
