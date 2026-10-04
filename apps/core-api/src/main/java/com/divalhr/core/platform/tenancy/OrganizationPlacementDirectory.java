package com.divalhr.core.platform.tenancy;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves organizational units by code for other modules (MVP-020, architect decision E13).
 * Implemented by the tenant module; callers never read its tables. Read-only, tenant-bound, and
 * batched: one lookup per unit kind. A unit of another tenant and a missing unit are both simply
 * absent from the result. Names are never returned.
 */
public interface OrganizationPlacementDirectory {

  /**
   * Resolves the given codes in the verified tenant.
   *
   * @param tenant verified tenant
   * @param codes upper-case unit codes per kind
   * @return the units found, keyed by code per kind
   */
  Placements resolve(TenantId tenant, PlacementCodes codes);

  /**
   * Resolves units by ID in the caller's tenant (MVP-021): one query, read-only. IDs of another
   * tenant, of no unit, or of a unit of another kind than expected are simply absent, so callers
   * treat foreign and unknown alike.
   *
   * @param tenant verified tenant
   * @param ids unit IDs of any kind
   * @return the units found, by ID
   */
  Map<UUID, UnitView> resolveIds(TenantId tenant, Set<UUID> ids);

  /** Kinds of organizational unit a placement references. */
  enum UnitKind {
    /** Legal entity. */
    LEGAL_ENTITY,
    /** Site. */
    SITE,
    /** Department. */
    DEPARTMENT,
    /** Cost center. */
    COST_CENTER,
    /** Team. */
    TEAM
  }

  /**
   * A unit with its display code and name (organizational data, not personal data), period and
   * parents.
   *
   * @param id unit ID
   * @param kind unit kind
   * @param code code as stored
   * @param name display name
   * @param effectiveFrom first day
   * @param effectiveTo last day, or {@code null}
   * @param legalEntityId parent legal entity (sites), or {@code null}
   * @param siteId parent site (departments, cost centers, teams), or {@code null}
   * @param departmentId parent department (teams), or {@code null}
   * @param costCenterId parent cost center (teams), or {@code null}
   */
  record UnitView(
      UUID id,
      UnitKind kind,
      String code,
      String name,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      UUID legalEntityId,
      UUID siteId,
      UUID departmentId,
      UUID costCenterId) {

    /** Requires the identity, kind and start. */
    public UnitView {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(effectiveFrom, "effectiveFrom");
    }

    /**
     * Whether the unit is in effect on every day of a period.
     *
     * @param from first day
     * @param to last day, or {@code null} for open-ended
     * @return true when the unit's period contains the whole period
     */
    public boolean effectiveThroughout(LocalDate from, LocalDate to) {
      if (from.isBefore(effectiveFrom)) {
        return false;
      }
      if (effectiveTo == null) {
        return true;
      }
      return to != null && !to.isAfter(effectiveTo);
    }

    /**
     * Whether the unit is in effect on a day.
     *
     * @param day day
     * @return true when the unit's period contains it
     */
    public boolean effectiveOn(LocalDate day) {
      return !day.isBefore(effectiveFrom) && (effectiveTo == null || !day.isAfter(effectiveTo));
    }
  }

  /**
   * Codes to resolve, per unit kind.
   *
   * @param legalEntities legal entity codes
   * @param sites site codes
   * @param departments department codes
   * @param costCenters cost center codes
   * @param teams team codes
   */
  record PlacementCodes(
      Set<String> legalEntities,
      Set<String> sites,
      Set<String> departments,
      Set<String> costCenters,
      Set<String> teams) {

    /** Defensively copies the sets. */
    public PlacementCodes {
      legalEntities = Set.copyOf(legalEntities);
      sites = Set.copyOf(sites);
      departments = Set.copyOf(departments);
      costCenters = Set.copyOf(costCenters);
      teams = Set.copyOf(teams);
    }
  }

  /**
   * One unit with its parents and effective period. Parent IDs that do not apply to the kind are
   * {@code null}: a site has a legal entity; a department or cost center a site; a team a site and
   * exactly one of department or cost center.
   *
   * @param id unit ID
   * @param effectiveFrom first effective day
   * @param effectiveTo last effective day, or {@code null} when open-ended
   * @param legalEntityId parent legal entity (sites)
   * @param siteId parent site (departments, cost centers, teams)
   * @param departmentId parent department (teams)
   * @param costCenterId parent cost center (teams)
   */
  record PlacementUnit(
      UUID id,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      UUID legalEntityId,
      UUID siteId,
      UUID departmentId,
      UUID costCenterId) {

    /** Requires the ID and start. */
    public PlacementUnit {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(effectiveFrom, "effectiveFrom");
    }

    /**
     * Whether the unit is effective on the day.
     *
     * @param day calendar day
     * @return true within the inclusive period
     */
    public boolean effectiveOn(LocalDate day) {
      return !day.isBefore(effectiveFrom) && (effectiveTo == null || !day.isAfter(effectiveTo));
    }
  }

  /**
   * Resolved units, keyed by code per kind.
   *
   * @param legalEntities legal entities
   * @param sites sites
   * @param departments departments
   * @param costCenters cost centers
   * @param teams teams
   */
  record Placements(
      Map<String, PlacementUnit> legalEntities,
      Map<String, PlacementUnit> sites,
      Map<String, PlacementUnit> departments,
      Map<String, PlacementUnit> costCenters,
      Map<String, PlacementUnit> teams) {

    /** Defensively copies the maps. */
    public Placements {
      legalEntities = Map.copyOf(legalEntities);
      sites = Map.copyOf(sites);
      departments = Map.copyOf(departments);
      costCenters = Map.copyOf(costCenters);
      teams = Map.copyOf(teams);
    }
  }
}
