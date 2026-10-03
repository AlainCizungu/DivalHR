package com.divalhr.core.people.application;

import com.divalhr.core.people.domain.ImportColumn;
import com.divalhr.core.people.domain.RowError;
import com.divalhr.core.people.domain.RowErrorCode;
import com.divalhr.core.people.domain.RowValues;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.PlacementCodes;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.PlacementUnit;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.Placements;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Placement rules of an employment (MVP-020, section 10): every unit exists in the caller's tenant
 * (a foreign unit is the same as a missing one), belongs to its parent, and is effective on the
 * start date. A team without a department or cost center takes its own parent. Used at preview and
 * again inside the commit transaction.
 */
public final class PlacementRules {

  private PlacementRules() {}

  /**
   * The resolved placement of a valid row.
   *
   * @param legalEntityId legal entity
   * @param siteId site
   * @param departmentId department, or {@code null}
   * @param costCenterId cost center, or {@code null}
   * @param teamId team, or {@code null}
   */
  public record Placement(
      UUID legalEntityId, UUID siteId, UUID departmentId, UUID costCenterId, UUID teamId) {}

  /**
   * The outcome of the rules for one row.
   *
   * @param errors errors in column order (empty when the placement is valid)
   * @param placement the placement, or {@code null} when there are errors
   */
  public record Outcome(List<RowError> errors, Placement placement) {
    /** Copies the errors so the outcome is immutable. */
    public Outcome {
      errors = List.copyOf(errors);
    }
  }

  /**
   * Collects the codes to resolve.
   *
   * @param rows normalized values
   * @return codes per kind
   */
  public static PlacementCodes codesOf(Collection<RowValues> rows) {
    Set<String> legalEntities = new HashSet<>();
    Set<String> sites = new HashSet<>();
    Set<String> departments = new HashSet<>();
    Set<String> costCenters = new HashSet<>();
    Set<String> teams = new HashSet<>();
    for (RowValues row : rows) {
      legalEntities.add(row.legalEntityCode());
      sites.add(row.siteCode());
      addIfPresent(departments, row.departmentCode());
      addIfPresent(costCenters, row.costCenterCode());
      addIfPresent(teams, row.teamCode());
    }
    return new PlacementCodes(legalEntities, sites, departments, costCenters, teams);
  }

  private static void addIfPresent(Set<String> set, String code) {
    if (code != null) {
      set.add(code);
    }
  }

  /**
   * Applies the rules.
   *
   * @param row normalized values
   * @param units resolved units
   * @return errors or the placement
   */
  public static Outcome check(RowValues row, Placements units) {
    List<RowError> errors = new ArrayList<>();
    LocalDate day = row.startDate();
    PlacementUnit legalEntity = units.legalEntities().get(row.legalEntityCode());
    if (legalEntity == null) {
      errors.add(error(ImportColumn.LEGAL_ENTITY_CODE, RowErrorCode.ROW_UNIT_NOT_FOUND));
    } else if (!legalEntity.effectiveOn(day)) {
      errors.add(error(ImportColumn.LEGAL_ENTITY_CODE, RowErrorCode.ROW_UNIT_NOT_EFFECTIVE));
    }
    PlacementUnit site = units.sites().get(row.siteCode());
    if (site == null) {
      errors.add(error(ImportColumn.SITE_CODE, RowErrorCode.ROW_UNIT_NOT_FOUND));
    } else if (legalEntity != null && !legalEntity.id().equals(site.legalEntityId())) {
      errors.add(error(ImportColumn.SITE_CODE, RowErrorCode.ROW_UNIT_MISMATCH));
    } else if (!site.effectiveOn(day)) {
      errors.add(error(ImportColumn.SITE_CODE, RowErrorCode.ROW_UNIT_NOT_EFFECTIVE));
    }
    PlacementUnit department =
        siteChild(
            errors,
            row.departmentCode(),
            find(units.departments(), row.departmentCode()),
            site,
            day,
            ImportColumn.DEPARTMENT_CODE);
    PlacementUnit costCenter =
        siteChild(
            errors,
            row.costCenterCode(),
            find(units.costCenters(), row.costCenterCode()),
            site,
            day,
            ImportColumn.COST_CENTER_CODE);
    UUID departmentId = department == null ? null : department.id();
    UUID costCenterId = costCenter == null ? null : costCenter.id();
    UUID teamId = null;
    if (row.teamCode() != null) {
      PlacementUnit team = units.teams().get(row.teamCode());
      if (team == null) {
        errors.add(error(ImportColumn.TEAM_CODE, RowErrorCode.ROW_UNIT_NOT_FOUND));
      } else if ((site != null && !site.id().equals(team.siteId()))
          || (department != null && !department.id().equals(team.departmentId()))
          || (costCenter != null && !costCenter.id().equals(team.costCenterId()))) {
        errors.add(error(ImportColumn.TEAM_CODE, RowErrorCode.ROW_UNIT_MISMATCH));
      } else if (!team.effectiveOn(day)) {
        errors.add(error(ImportColumn.TEAM_CODE, RowErrorCode.ROW_UNIT_NOT_EFFECTIVE));
      } else {
        teamId = team.id();
        if (department == null && costCenter == null) {
          // The team's own parent; its period lies within the parent's (MVP-002).
          departmentId = team.departmentId();
          costCenterId = team.costCenterId();
        }
      }
    }
    if (!errors.isEmpty()) {
      return new Outcome(errors, null);
    }
    return new Outcome(
        List.of(), new Placement(legalEntity.id(), site.id(), departmentId, costCenterId, teamId));
  }

  private static PlacementUnit siteChild(
      List<RowError> errors,
      String code,
      PlacementUnit unit,
      PlacementUnit site,
      LocalDate day,
      ImportColumn column) {
    if (code == null) {
      return null;
    }
    if (unit == null) {
      errors.add(error(column, RowErrorCode.ROW_UNIT_NOT_FOUND));
      return null;
    }
    if (site != null && !site.id().equals(unit.siteId())) {
      errors.add(error(column, RowErrorCode.ROW_UNIT_MISMATCH));
      return null;
    }
    if (!unit.effectiveOn(day)) {
      errors.add(error(column, RowErrorCode.ROW_UNIT_NOT_EFFECTIVE));
      return null;
    }
    return unit;
  }

  /** Immutable maps reject {@code null} keys: an absent optional code finds nothing. */
  private static PlacementUnit find(java.util.Map<String, PlacementUnit> units, String code) {
    return code == null ? null : units.get(code);
  }

  private static RowError error(ImportColumn column, RowErrorCode code) {
    return new RowError(column, code);
  }
}
