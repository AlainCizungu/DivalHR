package com.divalhr.core.people.application;

import com.divalhr.core.people.internal.JdbcEmploymentHistoryRepository;
import com.divalhr.core.people.internal.JdbcEmploymentHistoryRepository.EmploymentRecord;
import com.divalhr.core.people.internal.JdbcEmploymentHistoryRepository.PlacementRecord;
import com.divalhr.core.people.internal.JdbcEmploymentHistoryRepository.PopulationEmployee;
import com.divalhr.core.platform.access.PayrollPopulation;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * People's implementation of the {@link PayrollPopulation} port (MVP-094A, ADR 0009, ADR 0033): the
 * employees of a legal entity for a month with their employments and placements. It returns
 * employee numbers, dates and identifiers only, never a name.
 */
@Component
public class PeoplePayrollPopulation implements PayrollPopulation {

  private final JdbcEmploymentHistoryRepository history;

  /**
   * Creates the adapter.
   *
   * @param history employment history repository
   */
  public PeoplePayrollPopulation(JdbcEmploymentHistoryRepository history) {
    this.history = history;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockShared(TenantId tenant, LocalDate from, LocalDate to) {
    history.shareEmploymentsOverlapping(tenant, from, to);
  }

  @Override
  public List<Member> read(
      TenantId tenant, UUID legalEntityId, LocalDate from, LocalDate to, int limit) {
    List<PopulationEmployee> employees =
        history.populationEmployees(tenant, legalEntityId, from, to, limit);
    Set<UUID> ids = new HashSet<>();
    for (PopulationEmployee employee : employees) {
      ids.add(employee.id());
    }
    Map<UUID, List<EmploymentRecord>> employments =
        history.employmentsOverlapping(tenant, ids, from, to);
    Map<UUID, List<PlacementRecord>> placements =
        history.placementsOverlapping(tenant, ids, from, to);
    return employees.stream()
        .map(
            employee ->
                new Member(
                    employee.id(),
                    employee.employeeNumber(),
                    employments.getOrDefault(employee.id(), List.of()).stream()
                        .map(e -> new Employment(e.id(), e.version(), e.start(), e.end()))
                        .toList(),
                    placements.getOrDefault(employee.id(), List.of()).stream()
                        .map(p -> new Placement(p.id(), p.legalEntityId(), p.start(), p.end()))
                        .toList()))
        .toList();
  }
}
