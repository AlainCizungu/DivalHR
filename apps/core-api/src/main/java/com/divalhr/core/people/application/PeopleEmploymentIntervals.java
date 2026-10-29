package com.divalhr.core.people.application;

import com.divalhr.core.people.internal.JdbcEmploymentHistoryRepository;
import com.divalhr.core.people.internal.JdbcEmploymentHistoryRepository.EmploymentRecord;
import com.divalhr.core.platform.access.EmploymentIntervals;
import com.divalhr.core.platform.tenancy.TenantId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** People's implementation of the {@link EmploymentIntervals} port (MVP-093A, ADR 0009). */
@Component
public class PeopleEmploymentIntervals implements EmploymentIntervals {

  private final JdbcEmploymentHistoryRepository history;

  /**
   * Creates the adapter.
   *
   * @param history employment history repository
   */
  public PeopleEmploymentIntervals(JdbcEmploymentHistoryRepository history) {
    this.history = history;
  }

  @Override
  public Optional<List<Interval>> read(TenantId tenant, UUID employeeId) {
    if (history.employee(tenant, employeeId).isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(intervals(tenant, employeeId));
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<List<Interval>> lockShared(TenantId tenant, UUID employeeId) {
    if (history.employee(tenant, employeeId).isEmpty()) {
      return Optional.empty();
    }
    List<EmploymentRecord> rows =
        history.employments(tenant, Set.of(employeeId)).getOrDefault(employeeId, List.of());
    rows.stream()
        .map(EmploymentRecord::id)
        .sorted(Comparator.naturalOrder())
        .forEach(id -> history.shareEmployment(tenant, id));
    return Optional.of(intervals(tenant, employeeId));
  }

  private List<Interval> intervals(TenantId tenant, UUID employeeId) {
    return history
        .employments(tenant, Set.of(employeeId))
        .getOrDefault(employeeId, List.of())
        .stream()
        .map(e -> new Interval(e.id(), e.version(), e.start(), e.end()))
        .toList();
  }
}
