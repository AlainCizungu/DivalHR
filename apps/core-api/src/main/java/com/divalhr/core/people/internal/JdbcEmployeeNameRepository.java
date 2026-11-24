package com.divalhr.core.people.internal;

import com.divalhr.core.platform.tenancy.TenantId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads an employee's legal name (MVP-120F). Tenant-bound; the value is never logged. */
@Repository
public class JdbcEmployeeNameRepository {

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC client
   */
  public JdbcEmployeeNameRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * The employee's legal name.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @return given names, one space, family name; or empty
   */
  public Optional<String> legalName(TenantId tenant, UUID employeeId) {
    return jdbc.sql(
            "SELECT given_names || ' ' || family_name FROM people.employee"
                + " WHERE tenant_id = :tenant AND id = :id")
        .param("tenant", tenant.value())
        .param("id", employeeId)
        .query(String.class)
        .optional();
  }
}
