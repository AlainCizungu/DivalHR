package com.divalhr.core.tenant.application;

import com.divalhr.core.platform.tenancy.OrganizationUnitDirectory;
import com.divalhr.core.platform.tenancy.TenantId;
import com.divalhr.core.tenant.internal.JdbcLegalEntityRepository;
import com.divalhr.core.tenant.internal.JdbcSiteRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The tenant module's implementation of the {@link OrganizationUnitDirectory} port (MVP-012B). */
@Component
public class OrganizationUnitDirectoryAdapter implements OrganizationUnitDirectory {

  private final JdbcLegalEntityRepository legalEntities;
  private final JdbcSiteRepository sites;

  /**
   * Creates the adapter.
   *
   * @param legalEntities legal entity repository
   * @param sites site repository
   */
  public OrganizationUnitDirectoryAdapter(
      JdbcLegalEntityRepository legalEntities, JdbcSiteRepository sites) {
    this.legalEntities = legalEntities;
    this.sites = sites;
  }

  @Override
  public boolean legalEntityExists(TenantId tenant, UUID legalEntityId) {
    return legalEntities.exists(tenant, legalEntityId);
  }

  @Override
  public boolean siteExists(TenantId tenant, UUID siteId) {
    return sites.exists(tenant, siteId);
  }
}
