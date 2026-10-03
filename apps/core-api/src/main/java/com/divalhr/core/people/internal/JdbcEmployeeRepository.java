package com.divalhr.core.people.internal;

import com.divalhr.core.people.domain.NewEmployee;
import com.divalhr.core.platform.tenancy.TenantId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Employee and employment persistence (MVP-020). Every method takes the verified {@link TenantId}.
 * MVP-020 only creates; the tenant-unique employee number is enforced by {@code
 * employee_number_unique}, the final authority under concurrency (E8).
 */
@Repository
public class JdbcEmployeeRepository {

  /** The unique constraint on (tenant, employee number). */
  public static final String NUMBER_CONSTRAINT = "employee_number_unique";

  private final JdbcClient jdbc;
  private final NamedParameterJdbcTemplate batch;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC client
   * @param batch batch template
   */
  public JdbcEmployeeRepository(JdbcClient jdbc, NamedParameterJdbcTemplate batch) {
    this.jdbc = jdbc;
    this.batch = batch;
  }

  /**
   * Which of the employee numbers already exist in the tenant.
   *
   * @param tenant verified tenant
   * @param numbers upper-case employee numbers
   * @return the existing ones
   */
  public Set<String> existingNumbers(TenantId tenant, Set<String> numbers) {
    if (numbers.isEmpty()) {
      return Set.of();
    }
    return new HashSet<>(
        jdbc.sql(
                "SELECT employee_number FROM people.employee WHERE tenant_id = :tenant"
                    + " AND employee_number = ANY(:numbers)")
            .param("tenant", tenant.value())
            .param("numbers", numbers.toArray(String[]::new))
            .query(String.class)
            .list());
  }

  /**
   * Inserts employees and their first employments, in the given order.
   *
   * @param tenant verified tenant
   * @param employees new employees, ordered by employee number
   * @param createdBy verified subject
   * @param now creation time
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertAll(
      TenantId tenant, List<NewEmployee> employees, String createdBy, Instant now) {
    Timestamp at = Timestamp.from(now);
    MapSqlParameterSource[] people = new MapSqlParameterSource[employees.size()];
    MapSqlParameterSource[] employments = new MapSqlParameterSource[employees.size()];
    for (int i = 0; i < employees.size(); i++) {
      NewEmployee e = employees.get(i);
      people[i] =
          new MapSqlParameterSource()
              .addValue("id", e.employeeId())
              .addValue("tenant", tenant.value())
              .addValue("number", e.employeeNumber())
              .addValue("given", e.givenNames())
              .addValue("family", e.familyName())
              .addValue("at", at)
              .addValue("by", createdBy);
      employments[i] =
          new MapSqlParameterSource()
              .addValue("id", e.employmentId())
              .addValue("tenant", tenant.value())
              .addValue("employee", e.employeeId())
              .addValue("le", e.legalEntityId())
              .addValue("site", e.siteId())
              .addValue("dept", e.departmentId())
              .addValue("cc", e.costCenterId())
              .addValue("team", e.teamId())
              .addValue("start", e.startDate())
              .addValue("at", at)
              .addValue("by", createdBy);
    }
    batch.batchUpdate(
        "INSERT INTO people.employee (id, tenant_id, employee_number, given_names, family_name,"
            + " created_at, created_by) VALUES (:id, :tenant, :number, :given, :family, :at, :by)",
        people);
    batch.batchUpdate(
        "INSERT INTO people.employment (id, tenant_id, employee_id, legal_entity_id, site_id,"
            + " department_id, cost_center_id, team_id, effective_from, effective_to, created_at,"
            + " created_by) VALUES (:id, :tenant, :employee, :le, :site, :dept, :cc, :team,"
            + " :start, NULL, :at, :by)",
        employments);
  }
}
