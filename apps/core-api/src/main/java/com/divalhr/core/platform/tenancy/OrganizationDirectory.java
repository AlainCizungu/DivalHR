package com.divalhr.core.platform.tenancy;

import java.time.Duration;
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
   * Takes the organization's tenant-administration lock for the rest of the caller's transaction
   * and returns the organization when it exists and is active (MVP-014, architect decision on #38,
   * A1).
   *
   * <p>This is step 1 of the documented lock order shared by every path that creates a tenant-admin
   * invitation or a tenant-admin membership: (0, bootstrap creation only) the platform actor's
   * bootstrap lock; (1) this organization lock; (2) the identity module's per-tenant invitation
   * lock; (3) invitation row locks; (4) membership writes. No path takes an earlier lock after a
   * later one. The lock is a row lock that does not conflict with foreign-key checks, so ordinary
   * hierarchy writes are not serialized by it. Waiting longer than {@code timeout} fails the
   * transaction instead of blocking indefinitely.
   *
   * @param tenant target organization (its id is the tenant id)
   * @param timeout maximum time to wait for the lock
   * @return the organization, or empty when it does not exist or is not active (nothing locked)
   */
  Optional<OrganizationSummary> lockForTenantAdministration(TenantId tenant, Duration timeout);

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
