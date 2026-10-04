package com.divalhr.core.people.application;

import com.divalhr.core.people.internal.JdbcSeparationRepository;
import com.divalhr.core.platform.access.EmployeeRecords;
import com.divalhr.core.platform.tenancy.TenantId;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** People's implementation of the {@link EmployeeRecords} port for identity (MVP-022). */
@Component
public class PeopleEmployeeRecords implements EmployeeRecords {

  private final JdbcSeparationRepository separations;

  /**
   * Creates the adapter.
   *
   * @param separations separation repository
   */
  public PeopleEmployeeRecords(JdbcSeparationRepository separations) {
    this.separations = separations;
  }

  @Override
  public boolean exists(TenantId tenant, UUID employeeId) {
    return separations.employeeExists(tenant, employeeId);
  }

  @Override
  public boolean separated(TenantId tenant, UUID employeeId) {
    return separations.open(tenant, employeeId);
  }
}
