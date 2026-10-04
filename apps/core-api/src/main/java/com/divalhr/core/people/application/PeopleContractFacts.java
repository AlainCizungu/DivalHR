package com.divalhr.core.people.application;

import com.divalhr.core.people.domain.history.Assignment;
import com.divalhr.core.people.domain.history.AssignmentValue;
import com.divalhr.core.people.internal.JdbcEmploymentHistoryRepository;
import com.divalhr.core.people.internal.JdbcEmploymentHistoryRepository.EmployeeRecord;
import com.divalhr.core.people.internal.JdbcEmploymentHistoryRepository.EmploymentRecord;
import com.divalhr.core.people.internal.JdbcSeparationRepository;
import com.divalhr.core.platform.access.EmploymentContractFacts;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** People's implementation of the {@link EmploymentContractFacts} port (MVP-030, ADR 0009). */
@Component
public class PeopleContractFacts implements EmploymentContractFacts {

  private final JdbcEmploymentHistoryRepository history;
  private final JdbcSeparationRepository separations;

  /**
   * Creates the adapter.
   *
   * @param history employment history repository
   * @param separations separation repository
   */
  public PeopleContractFacts(
      JdbcEmploymentHistoryRepository history, JdbcSeparationRepository separations) {
    this.history = history;
    this.separations = separations;
  }

  @Override
  public Optional<ContractFacts> read(TenantId tenant, UUID employeeId, LocalDate on) {
    return facts(tenant, employeeId, on, false);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<ContractFacts> lockForContract(TenantId tenant, UUID employeeId, LocalDate on) {
    return facts(tenant, employeeId, on, true);
  }

  private Optional<ContractFacts> facts(
      TenantId tenant, UUID employeeId, LocalDate on, boolean lock) {
    Optional<EmployeeRecord> employee = history.employee(tenant, employeeId);
    if (employee.isEmpty()) {
      return Optional.empty();
    }
    Optional<EmploymentRecord> located = history.employment(tenant, employeeId, on);
    if (located.isEmpty()) {
      return Optional.empty();
    }
    EmploymentRecord employment =
        lock ? history.shareEmployment(tenant, located.get().id()).orElseThrow() : located.get();
    UUID legalEntity = null;
    UUID site = null;
    String classification = null;
    List<Assignment> rows = history.assignments(tenant, employment.id());
    for (Assignment row : rows) {
      if (!row.active() || !row.covers(on)) {
        continue;
      }
      if (row.value() instanceof AssignmentValue.Placement placement) {
        legalEntity = placement.legalEntityId();
        site = placement.siteId();
      } else if (row.value() instanceof AssignmentValue.Contract contract) {
        classification = contract.classification().name();
      }
    }
    EmployeeRecord found = employee.get();
    return Optional.of(
        new ContractFacts(
            found.id(),
            found.employeeNumber(),
            found.givenNames(),
            found.familyName(),
            employment.id(),
            employment.version(),
            employment.start(),
            employment.end(),
            separations.open(tenant, employeeId),
            legalEntity,
            site,
            classification));
  }
}
