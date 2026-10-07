package com.divalhr.core.people.internal;

import com.divalhr.core.platform.access.EmploymentExpirationScope.EmployeeLabel;
import com.divalhr.core.platform.access.EmploymentExpirationScope.RelevantEmployment;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only employment scope of the contract expiration queue (MVP-031A, Issue #73, A31A-2). One
 * row per employment; the placement of an employment is read on its placement day: the business
 * date, or the first day of an employment that has not started yet. Queries name people tables only
 * and run in the caller's transaction.
 */
@Repository
public class JdbcEmploymentScopeRepository {

  /** The placement row in force on the employment's placement day (active rows never overlap). */
  private static final String PLACEMENT_ON_DAY =
      "a.tenant_id = em.tenant_id AND a.employment_id = em.id AND a.kind = 'PLACEMENT'"
          + " AND a.superseded_by_change_id IS NULL"
          + " AND a.period @> GREATEST(CAST(:day AS date), em.effective_from)";

  private final NamedParameterJdbcTemplate jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc named-parameter JDBC
   */
  public JdbcEmploymentScopeRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Employments whose last day is not before the day, optionally narrowed.
   *
   * @param tenant verified tenant
   * @param day business date
   * @param numberPrefix upper-case employee-number prefix, or {@code null}
   * @param words normalized name words (each a word prefix), or empty
   * @param searching whether a search narrows the employees
   * @param unitId department or cost center of the placement, or {@code null}
   * @return one row per employment
   */
  public List<RelevantEmployment> relevant(
      TenantId tenant,
      LocalDate day,
      String numberPrefix,
      List<String> words,
      boolean searching,
      UUID unitId) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT em.id, em.employee_id, em.effective_to FROM people.employment em"
                + " JOIN people.employee e ON e.tenant_id = em.tenant_id AND e.id = em.employee_id"
                + " WHERE em.tenant_id = :tenant"
                + " AND (em.effective_to IS NULL OR em.effective_to >= :day)");
    MapSqlParameterSource params =
        new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("day", day);
    if (searching && (numberPrefix != null || !words.isEmpty())) {
      List<String> alternatives = new ArrayList<>();
      if (numberPrefix != null) {
        alternatives.add("e.employee_number LIKE :number ESCAPE '\\'");
        params.addValue("number", like(numberPrefix) + "%");
      }
      if (!words.isEmpty()) {
        List<String> all = new ArrayList<>();
        for (int i = 0; i < words.size(); i++) {
          all.add("e.search_key LIKE :w" + i + " ESCAPE '\\'");
          params.addValue("w" + i, "% " + like(words.get(i)) + "%");
        }
        alternatives.add("(" + String.join(" AND ", all) + ")");
      }
      sql.append(" AND (").append(String.join(" OR ", alternatives)).append(')');
    }
    if (unitId != null) {
      sql.append(" AND EXISTS (SELECT 1 FROM people.employment_assignment a WHERE ")
          .append(PLACEMENT_ON_DAY)
          .append(" AND (a.department_id = :unit OR a.cost_center_id = :unit))");
      params.addValue("unit", unitId);
    }
    return jdbc.query(
        sql.toString(),
        params,
        (rs, row) ->
            new RelevantEmployment(
                rs.getObject("id", UUID.class),
                rs.getObject("employee_id", UUID.class),
                rs.getObject("effective_to", LocalDate.class)));
  }

  /**
   * Employee labels by ID.
   *
   * @param tenant verified tenant
   * @param ids employees
   * @return labels found, by employee ID
   */
  public Map<UUID, EmployeeLabel> labels(TenantId tenant, Set<UUID> ids) {
    Map<UUID, EmployeeLabel> found = new HashMap<>();
    if (ids.isEmpty()) {
      return found;
    }
    jdbc.query(
        "SELECT id, employee_number, given_names, family_name FROM people.employee"
            + " WHERE tenant_id = :tenant AND id = ANY(:ids)",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("ids", ids.toArray(UUID[]::new)),
        rs -> {
          found.put(
              rs.getObject("id", UUID.class),
              new EmployeeLabel(
                  rs.getString("employee_number"),
                  rs.getString("given_names"),
                  rs.getString("family_name")));
        });
    return found;
  }

  /**
   * The department or cost center of each employment's placement on its placement day.
   *
   * @param tenant verified tenant
   * @param day business date
   * @param ids employments
   * @return unit per employment
   */
  public Map<UUID, UUID> units(TenantId tenant, LocalDate day, Set<UUID> ids) {
    Map<UUID, UUID> found = new HashMap<>();
    if (ids.isEmpty()) {
      return found;
    }
    jdbc.query(
        "SELECT em.id, COALESCE(a.department_id, a.cost_center_id) AS unit_id"
            + " FROM people.employment em JOIN people.employment_assignment a ON "
            + PLACEMENT_ON_DAY
            + " WHERE em.tenant_id = :tenant AND em.id = ANY(:ids)"
            + " AND COALESCE(a.department_id, a.cost_center_id) IS NOT NULL",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("day", day)
            .addValue("ids", ids.toArray(UUID[]::new)),
        rs -> {
          found.put(rs.getObject("id", UUID.class), rs.getObject("unit_id", UUID.class));
        });
    return found;
  }

  private static String like(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
