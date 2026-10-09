package com.divalhr.core.people.leave.internal;

import com.divalhr.core.people.leave.domain.Employment;
import com.divalhr.core.people.leave.domain.LeaveRequest;
import com.divalhr.core.people.leave.domain.LeaveRequestState;
import com.divalhr.core.people.leave.domain.LeaveUnit;
import com.divalhr.core.platform.tenancy.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Leave request storage (MVP-041A, V19) and the employment periods requests are checked against.
 * Every query names the verified tenant and the employee resolved from the caller's own link;
 * requests are insert-only. Runs in the caller's transaction.
 */
@Repository
public class JdbcLeaveRequestRepository {

  private static final String SELECT =
      "SELECT r.id, r.employee_id, r.employment_id, r.policy_version_id, r.start_date,"
          + " r.end_date, r.requested_amount, r.state, r.submitted_at, v.policy_id, p.code,"
          + " v.name_en, v.name_fr, v.unit"
          + " FROM people.leave_request r"
          + " JOIN people.leave_policy_version v"
          + " ON v.tenant_id = r.tenant_id AND v.id = r.policy_version_id"
          + " JOIN people.leave_policy p ON p.tenant_id = v.tenant_id AND p.id = v.policy_id";

  private final NamedParameterJdbcTemplate jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc named-parameter JDBC
   */
  public JdbcLeaveRequestRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * The employments of an employee whose period overlaps an interval, earliest first (no lock: the
   * request is checked against the last committed periods; ADR 0008 lock order is kept).
   *
   * @param tenant verified tenant
   * @param employeeId the caller's own employee
   * @param start first day
   * @param end last day
   * @return employments
   */
  public List<Employment> employments(
      TenantId tenant, UUID employeeId, LocalDate start, LocalDate end) {
    return jdbc.query(
        "SELECT id, effective_from, effective_to FROM people.employment"
            + " WHERE tenant_id = :tenant AND employee_id = :employee"
            + " AND effective_from <= :end AND (effective_to IS NULL OR effective_to >= :start)"
            + " ORDER BY effective_from, id",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("employee", employeeId)
            .addValue("start", start)
            .addValue("end", end),
        (rs, n) ->
            new Employment(
                rs.getObject("id", UUID.class),
                rs.getObject("effective_from", LocalDate.class),
                rs.getObject("effective_to", LocalDate.class)));
  }

  /**
   * Inserts a pending request.
   *
   * @param tenant verified tenant
   * @param request the request
   * @param submittedBy verified subject
   * @throws org.springframework.dao.DataIntegrityViolationException when a constraint rejects it
   *     (see {@link LeaveConstraintViolations})
   */
  public void insert(TenantId tenant, LeaveRequest request, String submittedBy) {
    jdbc.update(
        "INSERT INTO people.leave_request (id, tenant_id, employee_id, employment_id,"
            + " policy_version_id, start_date, end_date, requested_amount, state, submitted_at,"
            + " submitted_by) VALUES (:id, :tenant, :employee, :employment, :version, :start,"
            + " :end, :amount, :state, :submittedAt, :submittedBy)",
        new MapSqlParameterSource()
            .addValue("id", request.id())
            .addValue("tenant", tenant.value())
            .addValue("employee", request.employeeId())
            .addValue("employment", request.employmentId())
            .addValue("version", request.policyVersionId())
            .addValue("start", request.startDate())
            .addValue("end", request.endDate())
            .addValue("amount", request.amount())
            .addValue("state", request.state().name())
            .addValue("submittedAt", Timestamp.from(request.submittedAt()))
            .addValue("submittedBy", submittedBy));
  }

  /**
   * One of the employee's own requests.
   *
   * @param tenant verified tenant
   * @param employeeId the caller's own employee
   * @param id request
   * @return the request, if it is the employee's
   */
  public Optional<LeaveRequest> find(TenantId tenant, UUID employeeId, UUID id) {
    return jdbc
        .query(
            SELECT + " WHERE r.tenant_id = :tenant AND r.employee_id = :employee AND r.id = :id",
            new MapSqlParameterSource()
                .addValue("tenant", tenant.value())
                .addValue("employee", employeeId)
                .addValue("id", id),
            (rs, n) -> request(rs))
        .stream()
        .findFirst();
  }

  /**
   * One keyset page of the employee's own requests, newest first.
   *
   * @param tenant verified tenant
   * @param employeeId the caller's own employee
   * @param afterSubmittedAt submission time of the last row of the previous page, or {@code null}
   * @param afterId id of that row, or {@code null}
   * @param limit rows to read
   * @return rows
   */
  public List<LeaveRequest> page(
      TenantId tenant, UUID employeeId, Instant afterSubmittedAt, UUID afterId, int limit) {
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("employee", employeeId)
            .addValue("limit", limit);
    StringBuilder sql =
        new StringBuilder(SELECT)
            .append(" WHERE r.tenant_id = :tenant AND r.employee_id = :employee");
    if (afterSubmittedAt != null) {
      sql.append(" AND (r.submitted_at, r.id) < (:afterAt, :afterId)");
      params.addValue("afterAt", Timestamp.from(afterSubmittedAt)).addValue("afterId", afterId);
    }
    sql.append(" ORDER BY r.submitted_at DESC, r.id DESC LIMIT :limit");
    return jdbc.query(sql.toString(), params, (rs, n) -> request(rs));
  }

  private static LeaveRequest request(ResultSet rs) throws SQLException {
    return new LeaveRequest(
        rs.getObject("id", UUID.class),
        rs.getObject("employee_id", UUID.class),
        rs.getObject("employment_id", UUID.class),
        rs.getObject("policy_id", UUID.class),
        rs.getObject("policy_version_id", UUID.class),
        rs.getString("code"),
        rs.getString("name_en"),
        rs.getString("name_fr"),
        LeaveUnit.valueOf(rs.getString("unit")),
        rs.getObject("start_date", LocalDate.class),
        rs.getObject("end_date", LocalDate.class),
        rs.getBigDecimal("requested_amount"),
        LeaveRequestState.valueOf(rs.getString("state")),
        rs.getTimestamp("submitted_at").toInstant());
  }
}
