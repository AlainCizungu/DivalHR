package com.divalhr.core.people.application;

import com.divalhr.core.people.internal.JdbcEmploymentHistoryRepository;
import com.divalhr.core.platform.access.SalaryAdvanceEmployment;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * People's implementation of the {@link SalaryAdvanceEmployment} port (MVP-120A, ADR 0041): dates
 * and identifiers only, never a name or a pay figure.
 */
@Component
public class PeopleSalaryAdvanceEmployment implements SalaryAdvanceEmployment {

  private final JdbcEmploymentHistoryRepository history;

  /**
   * Creates the adapter.
   *
   * @param history employment history repository
   */
  public PeopleSalaryAdvanceEmployment(JdbcEmploymentHistoryRepository history) {
    this.history = history;
  }

  @Override
  public Optional<LocalDate> employmentStart(TenantId tenant, UUID employeeId, LocalDate on) {
    return history.employmentStartOn(tenant, employeeId, on);
  }

  @Override
  public List<UUID> placementEntities(TenantId tenant, UUID employeeId, LocalDate on) {
    return history.placementEntitiesOn(tenant, employeeId, on);
  }
}
