package com.divalhr.core.people.application;

import com.divalhr.core.people.domain.history.AssignmentValue;
import com.divalhr.core.people.internal.JdbcEmploymentHistoryRepository;
import com.divalhr.core.people.internal.JdbcEmploymentHistoryRepository.EmploymentRecord;
import com.divalhr.core.people.internal.JdbcEmploymentHistoryRepository.ManagerEdge;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.UnitKind;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.UnitView;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Validation of new assignment values against the hierarchy (through the tenant module's port) and
 * against related employees (MVP-021, section 5). Every check runs in the preview and again inside
 * the commit transaction; problems carry only a field key and a reason code.
 */
@Component
public class EmploymentHistoryRules {

  /** Longest reporting chain (H6); the V14 trigger uses the same bound. */
  static final int MAX_CHAIN = 50;

  private final OrganizationPlacementDirectory units;
  private final JdbcEmploymentHistoryRepository history;

  /**
   * Creates the rules.
   *
   * @param units hierarchy port
   * @param history history repository
   */
  public EmploymentHistoryRules(
      OrganizationPlacementDirectory units, JdbcEmploymentHistoryRepository history) {
    this.units = units;
    this.history = history;
  }

  /**
   * Resolves a placement's units, checks kinds and parents, and completes a team's parent when
   * neither department nor cost center was given (as MVP-020 does).
   *
   * @param tenant verified tenant
   * @param placement requested placement
   * @return the normalized placement
   * @throws ApiException {@code PLACEMENT_INVALID}
   */
  public AssignmentValue.Placement normalize(TenantId tenant, AssignmentValue.Placement placement) {
    Map<UUID, UnitView> found = units.resolveIds(tenant, ids(placement));
    UnitView legalEntity =
        unit(found, placement.legalEntityId(), UnitKind.LEGAL_ENTITY, "legalEntityId");
    UnitView site = unit(found, placement.siteId(), UnitKind.SITE, "siteId");
    if (!legalEntity.id().equals(site.legalEntityId())) {
      throw invalid("siteId", "MISMATCH");
    }
    UUID department = placement.departmentId();
    UUID costCenter = placement.costCenterId();
    if (department != null && costCenter != null) {
      throw invalid("costCenterId", "MISMATCH");
    }
    if (department != null
        && !site.id()
            .equals(unit(found, department, UnitKind.DEPARTMENT, "departmentId").siteId())) {
      throw invalid("departmentId", "MISMATCH");
    }
    if (costCenter != null
        && !site.id()
            .equals(unit(found, costCenter, UnitKind.COST_CENTER, "costCenterId").siteId())) {
      throw invalid("costCenterId", "MISMATCH");
    }
    if (placement.teamId() != null) {
      UnitView team = unit(found, placement.teamId(), UnitKind.TEAM, "teamId");
      if (!site.id().equals(team.siteId())
          || (department != null && !department.equals(team.departmentId()))
          || (costCenter != null && !costCenter.equals(team.costCenterId()))) {
        throw invalid("teamId", "MISMATCH");
      }
      if (department == null && costCenter == null) {
        department = team.departmentId();
        costCenter = team.costCenterId();
      }
    }
    return new AssignmentValue.Placement(
        legalEntity.id(), site.id(), department, costCenter, placement.teamId());
  }

  /**
   * Checks that every unit of a placement is in effect on every day of a row's period.
   *
   * @param tenant verified tenant
   * @param placement normalized placement
   * @param from first day
   * @param to last day, or {@code null}
   * @throws ApiException {@code PLACEMENT_INVALID} ({@code NOT_EFFECTIVE} or {@code
   *     ENDS_DURING_PERIOD})
   */
  public void checkEffective(
      TenantId tenant, AssignmentValue.Placement placement, LocalDate from, LocalDate to) {
    Map<UUID, UnitView> found = units.resolveIds(tenant, ids(placement));
    effective(found, placement.legalEntityId(), "legalEntityId", from, to);
    effective(found, placement.siteId(), "siteId", from, to);
    effective(found, placement.departmentId(), "departmentId", from, to);
    effective(found, placement.costCenterId(), "costCenterId", from, to);
    effective(found, placement.teamId(), "teamId", from, to);
  }

  /**
   * Checks a manager row: same tenant, not the employee, employed on every day of the period, and
   * no reporting loop or chain over {@value #MAX_CHAIN} levels on any day of it (H5, H6).
   *
   * @param tenant verified tenant
   * @param employeeId the employee
   * @param managerId the manager's employee
   * @param from first day
   * @param to last day, or {@code null}
   * @throws ApiException {@code MANAGER_INVALID}
   */
  public void checkManager(
      TenantId tenant, UUID employeeId, UUID managerId, LocalDate from, LocalDate to) {
    if (managerId.equals(employeeId)) {
      throw manager("SELF");
    }
    if (history.employees(tenant, Set.of(managerId)).isEmpty()) {
      throw manager("NOT_FOUND");
    }
    List<EmploymentRecord> employments =
        history.employments(tenant, Set.of(managerId)).getOrDefault(managerId, List.of());
    if (!covered(employments, from, to)) {
      throw manager("NOT_EMPLOYED");
    }
    walk(tenant, employeeId, managerId, from, to);
  }

  /** A person reached by the walk and the part of the period on which the chain holds. */
  private record Reach(UUID person, LocalDate from, LocalDate to) {}

  private void walk(
      TenantId tenant, UUID employeeId, UUID managerId, LocalDate from, LocalDate to) {
    List<Reach> frontier = List.of(new Reach(managerId, from, to));
    int depth = 1;
    Set<Reach> seen = new HashSet<>(frontier);
    while (!frontier.isEmpty()) {
      Set<UUID> people = new HashSet<>();
      frontier.forEach(reach -> people.add(reach.person()));
      List<ManagerEdge> edges = history.managerEdges(tenant, people);
      List<Reach> next = new ArrayList<>();
      for (Reach reach : frontier) {
        for (ManagerEdge edge : edges) {
          if (!edge.employeeId().equals(reach.person())) {
            continue;
          }
          LocalDate start = later(reach.from(), edge.from());
          LocalDate end = earlier(reach.to(), edge.to());
          if (end != null && end.isBefore(start)) {
            continue;
          }
          if (edge.managerId().equals(employeeId)) {
            throw manager("CYCLE");
          }
          Reach up = new Reach(edge.managerId(), start, end);
          if (seen.add(up)) {
            next.add(up);
          }
        }
      }
      if (!next.isEmpty() && depth + 1 > MAX_CHAIN) {
        throw manager("CHAIN_TOO_DEEP");
      }
      frontier = next;
      depth++;
    }
  }

  /**
   * Checks that a start date is inside the retroactive window (H8).
   *
   * @param start start date of the change or of the corrected row's end
   * @param businessDate today
   * @param retroactiveDays window
   * @throws ApiException {@code RETROACTIVE_WINDOW_EXCEEDED}
   */
  public static void checkWindow(LocalDate start, LocalDate businessDate, int retroactiveDays) {
    if (start.isBefore(businessDate.minusDays(retroactiveDays))) {
      throw new ApiException(ErrorCode.RETROACTIVE_WINDOW_EXCEEDED, Map.of());
    }
  }

  private static boolean covered(List<EmploymentRecord> employments, LocalDate from, LocalDate to) {
    LocalDate next = from;
    for (EmploymentRecord employment :
        employments.stream()
            .sorted(java.util.Comparator.comparing(EmploymentRecord::start))
            .toList()) {
      if (employment.start().isAfter(next)) {
        continue;
      }
      if (employment.end() == null) {
        return true;
      }
      if (!employment.end().isBefore(next)) {
        next = employment.end().plusDays(1);
        if (to != null && next.isAfter(to)) {
          return true;
        }
      }
    }
    return false;
  }

  private static LocalDate later(LocalDate a, LocalDate b) {
    return a.isAfter(b) ? a : b;
  }

  private static LocalDate earlier(LocalDate a, LocalDate b) {
    if (a == null) {
      return b;
    }
    if (b == null) {
      return a;
    }
    return a.isBefore(b) ? a : b;
  }

  private static Set<UUID> ids(AssignmentValue.Placement placement) {
    Set<UUID> ids = new HashSet<>();
    ids.add(placement.legalEntityId());
    ids.add(placement.siteId());
    if (placement.departmentId() != null) {
      ids.add(placement.departmentId());
    }
    if (placement.costCenterId() != null) {
      ids.add(placement.costCenterId());
    }
    if (placement.teamId() != null) {
      ids.add(placement.teamId());
    }
    return ids;
  }

  private static UnitView unit(Map<UUID, UnitView> found, UUID id, UnitKind kind, String field) {
    UnitView unit = found.get(id);
    if (unit == null || unit.kind() != kind) {
      throw invalid(field, "NOT_FOUND");
    }
    return unit;
  }

  private static void effective(
      Map<UUID, UnitView> found, UUID id, String field, LocalDate from, LocalDate to) {
    if (id == null) {
      return;
    }
    UnitView unit = Objects.requireNonNull(found.get(id), "unit");
    if (!unit.effectiveOn(from)) {
      throw invalid(field, "NOT_EFFECTIVE");
    }
    if (!unit.effectiveThroughout(from, to)) {
      throw invalid(field, "ENDS_DURING_PERIOD");
    }
  }

  private static ApiException invalid(String field, String reason) {
    return new ApiException(ErrorCode.PLACEMENT_INVALID, Map.of("field", field, "reason", reason));
  }

  private static ApiException manager(String reason) {
    return new ApiException(ErrorCode.MANAGER_INVALID, Map.of("reason", reason));
  }
}
