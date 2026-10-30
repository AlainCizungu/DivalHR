package com.divalhr.core.payroll.application;

import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.UnitKind;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.UnitView;
import com.divalhr.core.platform.tenancy.TenantId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Resolves the legal entity of a payroll path in the verified tenant (MVP-094A, D94A-2) through the
 * tenant module's port. A malformed, unknown, foreign or wrong-kind identifier is one safe 404.
 */
@Component
public class PayrollEntities {

  private final OrganizationPlacementDirectory placements;

  /**
   * Creates the resolver.
   *
   * @param placements the tenant module's placement port
   */
  public PayrollEntities(OrganizationPlacementDirectory placements) {
    this.placements = placements;
  }

  /**
   * The legal entity of a path value.
   *
   * @param tenant verified tenant
   * @param raw the raw path value
   * @param notFound the 404 code of the operation
   * @return the legal entity identifier
   * @throws ApiException {@code notFound} for anything that is not a legal entity of the tenant
   */
  UUID require(TenantId tenant, String raw, ErrorCode notFound) {
    UUID id = PayrollCommands.id(raw, notFound);
    return require(tenant, id, notFound);
  }

  /**
   * Checks that a parsed identifier is a legal entity of the tenant.
   *
   * @param tenant verified tenant
   * @param id the identifier
   * @param notFound the 404 code of the operation
   * @return the identifier
   * @throws ApiException {@code notFound} for anything that is not a legal entity of the tenant
   */
  UUID require(TenantId tenant, UUID id, ErrorCode notFound) {
    Map<UUID, UnitView> found = placements.resolveIds(tenant, Set.of(id));
    UnitView unit = found.get(id);
    if (unit == null || unit.kind() != UnitKind.LEGAL_ENTITY) {
      throw new ApiException(notFound, Map.of());
    }
    return id;
  }
}
