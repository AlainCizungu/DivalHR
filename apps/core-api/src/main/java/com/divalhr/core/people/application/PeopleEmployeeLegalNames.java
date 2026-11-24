package com.divalhr.core.people.application;

import com.divalhr.core.people.internal.JdbcEmployeeNameRepository;
import com.divalhr.core.platform.access.EmployeeLegalNames;
import com.divalhr.core.platform.tenancy.TenantId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** People's implementation of {@link EmployeeLegalNames} (MVP-120F). */
@Component
public class PeopleEmployeeLegalNames implements EmployeeLegalNames {

  private final JdbcEmployeeNameRepository names;

  /**
   * Creates the adapter.
   *
   * @param names employee name storage
   */
  public PeopleEmployeeLegalNames(JdbcEmployeeNameRepository names) {
    this.names = names;
  }

  @Override
  public Optional<String> legalName(TenantId tenant, UUID employeeId) {
    return names.legalName(tenant, employeeId);
  }
}
