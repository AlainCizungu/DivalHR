package com.divalhr.core.platform.tenancy;

import java.util.UUID;

/**
 * Tenant-bound existence checks for organizational units, for modules other than {@code tenant}
 * (MVP-012B, architect decision R5). Implemented by the tenant module; callers never read its
 * tables. A unit of another tenant and a missing unit are indistinguishable.
 */
public interface OrganizationUnitDirectory {

  /**
   * Whether the legal entity exists in the verified tenant.
   *
   * @param tenant verified tenant
   * @param legalEntityId candidate legal entity
   * @return true only for a legal entity of this tenant
   */
  boolean legalEntityExists(TenantId tenant, UUID legalEntityId);

  /**
   * Whether the site exists in the verified tenant.
   *
   * @param tenant verified tenant
   * @param siteId candidate site
   * @return true only for a site of this tenant
   */
  boolean siteExists(TenantId tenant, UUID siteId);
}
