package com.divalhr.core.people.application;

import com.divalhr.core.platform.access.ReportingLines;
import com.divalhr.core.platform.tenancy.TenantId;
import java.sql.Array;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * People's implementation of the {@link ReportingLines} port (MVP-081, ADR 0031): the effective-
 * dated MANAGER lines of the employment history, with the same rule as the weekly timesheet route
 * (active, non-superseded lines that cover the day, over the employee's employments effective that
 * day). Every query names the verified tenant and is driven by a key.
 */
@Component
public class PeopleReportingLines implements ReportingLines {

  private final NamedParameterJdbcTemplate jdbc;
  private final ManagerGraphLock graph;

  /**
   * Creates the adapter.
   *
   * @param jdbc named-parameter JDBC
   * @param graph the manager-graph lock
   */
  public PeopleReportingLines(NamedParameterJdbcTemplate jdbc, ManagerGraphLock graph) {
    this.jdbc = jdbc;
    this.graph = graph;
  }

  @Override
  public RouteFacts route(TenantId tenant, UUID employeeId, LocalDate on) {
    return jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM people.employment e WHERE e.tenant_id = :tenant"
            + " AND e.employee_id = :employee AND e.effective_from <= :day"
            + " AND (e.effective_to IS NULL OR e.effective_to >= :day)) AS employed,"
            + " ARRAY(SELECT DISTINCT ma.manager_employee_id FROM people.employment e"
            + " JOIN people.employment_assignment ma ON ma.tenant_id = e.tenant_id"
            + " AND ma.employment_id = e.id AND ma.kind = 'MANAGER'"
            + " AND ma.superseded_by_change_id IS NULL AND ma.manager_employee_id IS NOT NULL"
            + " AND ma.effective_from <= :day"
            + " AND (ma.effective_to IS NULL OR ma.effective_to >= :day)"
            + " WHERE e.tenant_id = :tenant AND e.employee_id = :employee"
            + " AND e.effective_from <= :day AND (e.effective_to IS NULL OR e.effective_to >= :day)"
            + " ORDER BY 1 LIMIT 2) AS managers",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("employee", employeeId)
            .addValue("day", day(on)),
        (rs, n) -> new RouteFacts(rs.getBoolean("employed"), uuids(rs.getArray("managers"))));
  }

  @Override
  public List<UUID> reportsOf(TenantId tenant, UUID managerEmployeeId, int limit) {
    return jdbc.query(
        "SELECT DISTINCT e.employee_id FROM people.employment_assignment ma"
            + " JOIN people.employment e ON e.tenant_id = ma.tenant_id AND e.id = ma.employment_id"
            + " WHERE ma.tenant_id = :tenant AND ma.manager_employee_id = :manager"
            + " AND ma.kind = 'MANAGER' AND ma.superseded_by_change_id IS NULL"
            + " AND e.employee_id <> :manager ORDER BY 1 LIMIT :limit",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("manager", managerEmployeeId)
            .addValue("limit", limit),
        (rs, n) -> rs.getObject(1, UUID.class));
  }

  @Override
  public List<Person> describe(TenantId tenant, Collection<UUID> employeeIds) {
    if (employeeIds.isEmpty()) {
      return List.of();
    }
    return jdbc.query(
        "SELECT p.id, p.employee_number, p.given_names, p.family_name FROM people.employee p"
            + " WHERE p.tenant_id = :tenant AND p.id IN (:ids)",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("ids", List.copyOf(employeeIds)),
        (rs, n) ->
            new Person(
                rs.getObject("id", UUID.class),
                rs.getString("employee_number"),
                rs.getString("given_names"),
                rs.getString("family_name")));
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockManagerGraph(TenantId tenant) {
    graph.lock(tenant);
  }

  private static LocalDate day(LocalDate on) {
    return java.util.Objects.requireNonNull(on, "day");
  }

  private static List<UUID> uuids(Array array) throws SQLException {
    List<UUID> out = new ArrayList<>();
    if (array != null) {
      for (Object value : (Object[]) array.getArray()) {
        out.add((UUID) value);
      }
    }
    return out;
  }
}
