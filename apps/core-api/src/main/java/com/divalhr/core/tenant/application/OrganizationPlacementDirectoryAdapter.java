package com.divalhr.core.tenant.application;

import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory;
import com.divalhr.core.platform.tenancy.TenantId;
import com.divalhr.core.tenant.internal.JdbcPlacementRepository;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The tenant module's implementation of the {@link OrganizationPlacementDirectory} port (MVP-020).
 */
@Component
public class OrganizationPlacementDirectoryAdapter implements OrganizationPlacementDirectory {

  private final JdbcPlacementRepository placements;

  /**
   * Creates the adapter.
   *
   * @param placements placement repository
   */
  public OrganizationPlacementDirectoryAdapter(JdbcPlacementRepository placements) {
    this.placements = placements;
  }

  @Override
  public Placements resolve(TenantId tenant, PlacementCodes codes) {
    return new Placements(
        placements.legalEntities(tenant, codes.legalEntities()),
        placements.sites(tenant, codes.sites()),
        placements.departments(tenant, codes.departments()),
        placements.costCenters(tenant, codes.costCenters()),
        placements.teams(tenant, codes.teams()));
  }

  @Override
  public Map<UUID, UnitView> resolveIds(TenantId tenant, Set<UUID> ids) {
    return placements.byIds(tenant, ids);
  }
}
