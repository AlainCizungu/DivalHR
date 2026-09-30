package com.divalhr.core.platform.tenancy;

import java.util.Optional;

/**
 * Read-only view of a tenant's organization for other modules (implemented by the tenant module).
 * It is the only way a module other than {@code tenant} learns an organization's display data;
 * modules never read the tenant module's tables.
 */
public interface OrganizationDirectory {

  /**
   * Finds the verified tenant's organization.
   *
   * @param tenant verified tenant
   * @return the organization summary, or empty when the tenant has no organization
   */
  Optional<OrganizationSummary> find(TenantId tenant);

  /**
   * Display data of an organization.
   *
   * @param tenant tenant (the organization id)
   * @param name display name (customer data: never logged)
   * @param defaultLocale {@code fr} or {@code en}
   * @param timezone IANA time zone
   */
  record OrganizationSummary(TenantId tenant, String name, String defaultLocale, String timezone) {}
}
