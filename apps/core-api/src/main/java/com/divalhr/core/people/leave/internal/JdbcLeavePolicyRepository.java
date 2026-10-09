package com.divalhr.core.people.leave.internal;

import com.divalhr.core.people.leave.domain.ApprovalRoute;
import com.divalhr.core.people.leave.domain.BalanceMode;
import com.divalhr.core.people.leave.domain.LeavePolicy;
import com.divalhr.core.people.leave.domain.LeaveUnit;
import com.divalhr.core.people.leave.domain.PayrollEffect;
import com.divalhr.core.platform.tenancy.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Leave policy storage (MVP-040A, V18). Every query names the verified tenant; rows are
 * insert-only. Runs in the caller's transaction.
 */
@Repository
public class JdbcLeavePolicyRepository {

  private static final String SELECT =
      "SELECT p.id, p.code, p.created_at, v.id AS version_id, v.version_number, v.name_en,"
          + " v.name_fr, v.unit, v.balance_mode, v.annual_entitlement, v.minimum_service_days,"
          + " v.approval_route, v.payroll_effect, v.effective_from, v.effective_to"
          + " FROM people.leave_policy p"
          + " CROSS JOIN LATERAL (SELECT * FROM people.leave_policy_version x"
          + " WHERE x.tenant_id = p.tenant_id AND x.policy_id = p.id"
          + " ORDER BY x.version_number DESC LIMIT 1) v";

  private final NamedParameterJdbcTemplate jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc named-parameter JDBC
   */
  public JdbcLeavePolicyRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Inserts a policy and its version 1.
   *
   * @param tenant verified tenant
   * @param policy the policy
   * @param createdBy verified subject
   * @throws DataIntegrityViolationException when a constraint rejects it (see {@link
   *     LeaveConstraintViolations})
   */
  public void insert(TenantId tenant, LeavePolicy policy, String createdBy) {
    Timestamp createdAt = Timestamp.from(policy.createdAt());
    jdbc.update(
        "INSERT INTO people.leave_policy (id, tenant_id, code, created_at, created_by)"
            + " VALUES (:id, :tenant, :code, :createdAt, :createdBy)",
        new MapSqlParameterSource()
            .addValue("id", policy.id())
            .addValue("tenant", tenant.value())
            .addValue("code", policy.code())
            .addValue("createdAt", createdAt)
            .addValue("createdBy", createdBy));
    jdbc.update(
        "INSERT INTO people.leave_policy_version (id, tenant_id, policy_id, version_number,"
            + " name_en, name_fr, unit, balance_mode, annual_entitlement, minimum_service_days,"
            + " approval_route, payroll_effect, effective_from, effective_to, created_at,"
            + " created_by) VALUES (:id, :tenant, :policy, :number, :nameEn, :nameFr, :unit,"
            + " :mode, :entitlement, :serviceDays, :route, :payroll, :from, :to, :createdAt,"
            + " :createdBy)",
        new MapSqlParameterSource()
            .addValue("id", policy.versionId())
            .addValue("tenant", tenant.value())
            .addValue("policy", policy.id())
            .addValue("number", policy.versionNumber())
            .addValue("nameEn", policy.nameEn())
            .addValue("nameFr", policy.nameFr())
            .addValue("unit", policy.unit().name())
            .addValue("mode", policy.balanceMode().name())
            .addValue("entitlement", policy.annualEntitlement(), java.sql.Types.NUMERIC)
            .addValue("serviceDays", policy.minimumServiceDays())
            .addValue("route", policy.approvalRoute().name())
            .addValue("payroll", policy.payrollEffect().name())
            .addValue("from", policy.effectiveFrom())
            .addValue("to", policy.effectiveTo(), java.sql.Types.DATE)
            .addValue("createdAt", createdAt)
            .addValue("createdBy", createdBy));
  }

  /**
   * One keyset page ordered by code, then id.
   *
   * @param tenant verified tenant
   * @param afterCode code of the last row of the previous page, or {@code null}
   * @param afterId id of that row, or {@code null}
   * @param limit rows to read
   * @return rows
   */
  public List<LeavePolicy> page(TenantId tenant, String afterCode, UUID afterId, int limit) {
    MapSqlParameterSource params =
        new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("limit", limit);
    StringBuilder sql = new StringBuilder(SELECT).append(" WHERE p.tenant_id = :tenant");
    if (afterCode != null) {
      sql.append(" AND (p.code, p.id) > (:afterCode, :afterId)");
      params.addValue("afterCode", afterCode).addValue("afterId", afterId);
    }
    sql.append(" ORDER BY p.code, p.id LIMIT :limit");
    return jdbc.query(sql.toString(), params, (rs, n) -> policy(rs));
  }

  /**
   * The immutable code of a policy of the tenant (cursor continuation).
   *
   * @param tenant verified tenant
   * @param id policy id
   * @return the code, if the policy exists in the tenant
   */
  public Optional<String> code(TenantId tenant, UUID id) {
    return jdbc
        .query(
            "SELECT code FROM people.leave_policy WHERE tenant_id = :tenant AND id = :id",
            new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("id", id),
            (rs, n) -> rs.getString(1))
        .stream()
        .findFirst();
  }

  private static LeavePolicy policy(ResultSet rs) throws SQLException {
    LocalDate to = rs.getObject("effective_to", LocalDate.class);
    return new LeavePolicy(
        rs.getObject("id", UUID.class),
        rs.getObject("version_id", UUID.class),
        rs.getString("code"),
        rs.getInt("version_number"),
        rs.getString("name_en"),
        rs.getString("name_fr"),
        LeaveUnit.valueOf(rs.getString("unit")),
        BalanceMode.valueOf(rs.getString("balance_mode")),
        rs.getBigDecimal("annual_entitlement"),
        rs.getInt("minimum_service_days"),
        ApprovalRoute.valueOf(rs.getString("approval_route")),
        PayrollEffect.valueOf(rs.getString("payroll_effect")),
        rs.getObject("effective_from", LocalDate.class),
        to,
        rs.getTimestamp("created_at").toInstant());
  }
}
