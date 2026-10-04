package com.divalhr.core.people.application;

import com.divalhr.core.people.api.EmploymentHistoryResponses;
import com.divalhr.core.people.api.EmploymentHistoryResponses.AssignmentPeriod;
import com.divalhr.core.people.api.EmploymentHistoryResponses.AssignmentStatus;
import com.divalhr.core.people.api.EmploymentHistoryResponses.ChangeState;
import com.divalhr.core.people.api.EmploymentHistoryResponses.EmploymentStatus;
import com.divalhr.core.people.api.EmploymentHistoryResponses.ManagerValue;
import com.divalhr.core.people.api.EmploymentHistoryResponses.PlacementValue;
import com.divalhr.core.people.api.EmploymentHistoryResponses.PreviewKind;
import com.divalhr.core.people.api.EmploymentHistoryResponses.UnitRef;
import com.divalhr.core.people.domain.history.Assignment;
import com.divalhr.core.people.domain.history.AssignmentKind;
import com.divalhr.core.people.domain.history.AssignmentValue;
import com.divalhr.core.people.domain.history.NewAssignment;
import com.divalhr.core.people.domain.history.TimelinePlan;
import com.divalhr.core.people.internal.JdbcEmploymentHistoryRepository;
import com.divalhr.core.people.internal.JdbcEmploymentHistoryRepository.ChangeRecord;
import com.divalhr.core.people.internal.JdbcEmploymentHistoryRepository.EmployeeRecord;
import com.divalhr.core.people.internal.JdbcEmploymentHistoryRepository.EmploymentRecord;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.UnitView;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds the MVP-021 response bodies: unit codes and names through the tenant module's port,
 * manager names from the people module, statuses against the business date. Each build resolves
 * every referenced unit and manager in one query each.
 */
@Component
public class EmploymentHistoryViews {

  private final OrganizationPlacementDirectory units;
  private final JdbcEmploymentHistoryRepository history;

  /**
   * Creates the builder.
   *
   * @param units hierarchy port
   * @param history history repository
   */
  public EmploymentHistoryViews(
      OrganizationPlacementDirectory units, JdbcEmploymentHistoryRepository history) {
    this.units = units;
    this.history = history;
  }

  /** Units and managers referenced by a set of values. */
  private record References(Map<UUID, UnitView> units, Map<UUID, EmployeeRecord> managers) {}

  private References references(TenantId tenant, Collection<AssignmentValue> values) {
    Set<UUID> unitIds = new HashSet<>();
    Set<UUID> managerIds = new HashSet<>();
    for (AssignmentValue value : values) {
      if (value instanceof AssignmentValue.Placement p) {
        unitIds.add(p.legalEntityId());
        unitIds.add(p.siteId());
        addIfPresent(unitIds, p.departmentId());
        addIfPresent(unitIds, p.costCenterId());
        addIfPresent(unitIds, p.teamId());
      } else if (value instanceof AssignmentValue.Manager m) {
        managerIds.add(m.employeeId());
      }
    }
    return new References(
        unitIds.isEmpty() ? Map.of() : units.resolveIds(tenant, unitIds),
        managerIds.isEmpty() ? Map.of() : history.employees(tenant, managerIds));
  }

  private static void addIfPresent(Set<UUID> ids, UUID id) {
    if (id != null) {
      ids.add(id);
    }
  }

  /**
   * Stored rows as response rows.
   *
   * @param tenant verified tenant
   * @param rows rows
   * @param businessDate today
   * @return response rows, in the given order
   */
  public List<EmploymentHistoryResponses.Assignment> assignments(
      TenantId tenant, List<Assignment> rows, LocalDate businessDate) {
    References refs = references(tenant, rows.stream().map(Assignment::value).toList());
    return rows.stream().map(row -> assignment(row, businessDate, refs)).toList();
  }

  /**
   * The current row of each kind.
   *
   * @param tenant verified tenant
   * @param rows the employment's rows
   * @param businessDate today
   * @return current rows per kind
   */
  public EmploymentHistoryResponses.CurrentAssignments current(
      TenantId tenant, List<Assignment> rows, LocalDate businessDate) {
    Map<AssignmentKind, Assignment> current = new EnumMap<>(AssignmentKind.class);
    for (Assignment row : rows) {
      if (row.active() && row.covers(businessDate)) {
        current.put(row.kind(), row);
      }
    }
    References refs = references(tenant, current.values().stream().map(Assignment::value).toList());
    return new EmploymentHistoryResponses.CurrentAssignments(
        view(current.get(AssignmentKind.PLACEMENT), businessDate, refs),
        view(current.get(AssignmentKind.MANAGER), businessDate, refs),
        view(current.get(AssignmentKind.CONTRACT), businessDate, refs),
        view(current.get(AssignmentKind.COMPENSATION), businessDate, refs));
  }

  private EmploymentHistoryResponses.Assignment view(
      Assignment row, LocalDate businessDate, References refs) {
    return row == null ? null : assignment(row, businessDate, refs);
  }

  /**
   * A plan as rows before and after, per kind (kind order).
   *
   * @param tenant verified tenant
   * @param plan plan
   * @return one entry per touched kind
   */
  public List<PreviewKind> periods(TenantId tenant, TimelinePlan plan) {
    List<AssignmentValue> values = new ArrayList<>();
    plan.superseded().forEach(row -> values.add(row.value()));
    plan.created().forEach(row -> values.add(row.value()));
    References refs = references(tenant, values);
    List<PreviewKind> kinds = new ArrayList<>();
    for (AssignmentKind kind : plan.kinds()) {
      List<AssignmentPeriod> before =
          plan.superseded().stream()
              .filter(row -> row.kind() == kind)
              .map(row -> period(row.id(), row.from(), row.to(), row.value(), refs))
              .toList();
      List<AssignmentPeriod> after =
          plan.created().stream()
              .filter(row -> row.kind() == kind)
              .map((NewAssignment row) -> period(null, row.from(), row.to(), row.value(), refs))
              .toList();
      kinds.add(new PreviewKind(kind, before, after));
    }
    return kinds;
  }

  /**
   * A recorded change.
   *
   * @param change the change
   * @return its response
   */
  public static EmploymentHistoryResponses.EmploymentChange change(ChangeRecord change) {
    return new EmploymentHistoryResponses.EmploymentChange(
        change.id(),
        change.type(),
        change.effectiveFrom(),
        change.kinds().stream().sorted().toList(),
        change.reason(),
        change.timing(),
        change.cancelled() ? ChangeState.CANCELLED : ChangeState.ACTIVE,
        change.cancelsChangeId(),
        change.recordedAt());
  }

  /**
   * An employment's status on the business date.
   *
   * @param employment employment
   * @param businessDate today
   * @return status
   */
  public static EmploymentStatus status(EmploymentRecord employment, LocalDate businessDate) {
    if (employment.start().isAfter(businessDate)) {
      return EmploymentStatus.NOT_STARTED;
    }
    if (employment.end() != null && employment.end().isBefore(businessDate)) {
      return EmploymentStatus.ENDED;
    }
    return EmploymentStatus.CURRENT;
  }

  /**
   * The employment shown for an employee: the one covering the day, else the latest started, else
   * the first future one (same order as the repository's single-employment read).
   *
   * @param employments the employee's employments
   * @param businessDate today
   * @return status, or {@link EmploymentStatus#ENDED} when there is none
   */
  public static EmploymentStatus status(
      List<EmploymentRecord> employments, LocalDate businessDate) {
    EmploymentStatus best = null;
    for (EmploymentRecord employment : employments) {
      EmploymentStatus status = status(employment, businessDate);
      if (status == EmploymentStatus.CURRENT) {
        return status;
      }
      if (best == null || status == EmploymentStatus.ENDED) {
        best = status;
      }
    }
    return best == null ? EmploymentStatus.ENDED : best;
  }

  private static AssignmentStatus rowStatus(Assignment row, LocalDate businessDate) {
    if (row.from().isAfter(businessDate)) {
      return AssignmentStatus.SCHEDULED;
    }
    if (row.to() != null && row.to().isBefore(businessDate)) {
      return AssignmentStatus.ENDED;
    }
    return AssignmentStatus.CURRENT;
  }

  private static EmploymentHistoryResponses.Assignment assignment(
      Assignment row, LocalDate businessDate, References refs) {
    AssignmentValue value = row.value();
    return new EmploymentHistoryResponses.Assignment(
        row.id(),
        row.kind(),
        row.from(),
        row.to(),
        rowStatus(row, businessDate),
        row.createdBy(),
        row.supersededAt(),
        value instanceof AssignmentValue.Placement p ? placement(p, refs) : null,
        value instanceof AssignmentValue.Manager m ? manager(m, refs) : null,
        value instanceof AssignmentValue.Contract c ? c.classification() : null,
        value instanceof AssignmentValue.Compensation c ? c.basis() : null);
  }

  private static AssignmentPeriod period(
      UUID id, LocalDate from, LocalDate to, AssignmentValue value, References refs) {
    return new AssignmentPeriod(
        id,
        from,
        to,
        value instanceof AssignmentValue.Placement p ? placement(p, refs) : null,
        value instanceof AssignmentValue.Manager m ? manager(m, refs) : null,
        value instanceof AssignmentValue.Contract c ? c.classification() : null,
        value instanceof AssignmentValue.Compensation c ? c.basis() : null);
  }

  private static PlacementValue placement(AssignmentValue.Placement p, References refs) {
    return new PlacementValue(
        unit(p.legalEntityId(), refs),
        unit(p.siteId(), refs),
        unit(p.departmentId(), refs),
        unit(p.costCenterId(), refs),
        unit(p.teamId(), refs));
  }

  private static UnitRef unit(UUID id, References refs) {
    if (id == null) {
      return null;
    }
    UnitView unit = refs.units().get(id);
    if (unit == null) {
      throw new IllegalStateException("assigned unit is not in the tenant");
    }
    return new UnitRef(unit.id(), unit.code(), unit.name());
  }

  private static ManagerValue manager(AssignmentValue.Manager m, References refs) {
    EmployeeRecord manager = refs.managers().get(m.employeeId());
    if (manager == null) {
      throw new IllegalStateException("assigned manager is not in the tenant");
    }
    return new ManagerValue(
        manager.id(), manager.employeeNumber(), manager.givenNames(), manager.familyName());
  }
}
