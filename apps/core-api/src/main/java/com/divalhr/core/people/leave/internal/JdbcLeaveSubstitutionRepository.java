package com.divalhr.core.people.leave.internal;

import com.divalhr.core.people.leave.domain.DirectoryEmployee;
import com.divalhr.core.people.leave.domain.LeaveSubstitution;
import com.divalhr.core.people.leave.domain.LeaveSubstitutionEntry;
import com.divalhr.core.people.leave.domain.LeaveSubstitutionRevocation;
import com.divalhr.core.platform.tenancy.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Leave approval substitute appointments and their revocations (MVP-041G, V24), and the employee,
 * employment and reporting-line facts an appointment is checked against. Every query names the
 * verified tenant. Runs in the caller's transaction; the caller holds the tenant's manager-graph
 * lock for every write.
 */
@Repository
public class JdbcLeaveSubstitutionRepository {

  private static final String ENTRY =
      "SELECT s.id, s.manager_employee_id, s.substitute_employee_id, s.effective_from,"
          + " s.effective_to, s.reason_locale, s.reason_text, s.created_at,"
          + " m.employee_number AS manager_number, m.given_names AS manager_given,"
          + " m.family_name AS manager_family, u.employee_number AS substitute_number,"
          + " u.given_names AS substitute_given, u.family_name AS substitute_family,"
          + " x.id AS revocation_id, x.reason_locale AS revocation_locale,"
          + " x.reason_text AS revocation_reason, x.revoked_at"
          + " FROM people.leave_approval_substitution s"
          + " JOIN people.employee m ON m.tenant_id = s.tenant_id AND m.id = s.manager_employee_id"
          + " JOIN people.employee u"
          + " ON u.tenant_id = s.tenant_id AND u.id = s.substitute_employee_id"
          + " LEFT JOIN people.leave_approval_substitution_revocation x"
          + " ON x.tenant_id = s.tenant_id AND x.substitution_id = s.id";

  private static final String SUBSTITUTION =
      "SELECT s.id, s.manager_employee_id, s.substitute_employee_id, s.effective_from,"
          + " s.effective_to, s.reason_locale, s.reason_text, s.created_at"
          + " FROM people.leave_approval_substitution s"
          + " WHERE s.tenant_id = :tenant AND s.id = :id";

  private final NamedParameterJdbcTemplate jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc named-parameter JDBC
   */
  public JdbcLeaveSubstitutionRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * How many of the employees exist in the tenant.
   *
   * @param tenant verified tenant
   * @param employees distinct employee IDs
   * @return the number found
   */
  public int employeesFound(TenantId tenant, Collection<UUID> employees) {
    Integer found =
        jdbc.queryForObject(
            "SELECT count(*) FROM people.employee WHERE tenant_id = :tenant AND id IN (:ids)",
            new MapSqlParameterSource()
                .addValue("tenant", tenant.value())
                .addValue("ids", employees),
            Integer.class);
    return found == null ? 0 : found;
  }

  /**
   * Locks {@code FOR SHARE}, in UUID order, every employment of the employees that overlaps the
   * period (lock order step 2): separations, which lock an employment {@code FOR UPDATE}, wait
   * until the appointment commits, and an appointment waiting on a separation reads the separated
   * period.
   *
   * @param tenant verified tenant
   * @param employees the manager and the substitute
   * @param from first day
   * @param to last day
   */
  public void shareEmployments(
      TenantId tenant, Collection<UUID> employees, LocalDate from, LocalDate to) {
    jdbc.query(
        "SELECT id FROM people.employment WHERE tenant_id = :tenant AND employee_id IN (:ids)"
            + " AND effective_from <= :to AND (effective_to IS NULL OR effective_to >= :from)"
            + " ORDER BY id FOR SHARE",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("ids", employees)
            .addValue("from", from)
            .addValue("to", to),
        (rs, n) -> rs.getObject("id", UUID.class));
  }

  /**
   * Whether the employee's employments together cover every day of the period (the same rule as a
   * manager's reporting lines, {@code people.employment_manager_covered}, V15).
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param from first day
   * @param to last day
   * @return whether the employee is employed throughout
   */
  public boolean employedThroughout(
      TenantId tenant, UUID employeeId, LocalDate from, LocalDate to) {
    Boolean covered =
        jdbc.queryForObject(
            "SELECT people.employment_manager_covered(:tenant, :employee,"
                + " daterange(:from, :to, '[]'))",
            new MapSqlParameterSource()
                .addValue("tenant", tenant.value())
                .addValue("employee", employeeId)
                .addValue("from", from)
                .addValue("to", to),
            Boolean.class);
    return Boolean.TRUE.equals(covered);
  }

  /**
   * Whether the manager has at least one active (non-superseded) MANAGER reporting line, as the
   * manager, overlapping the period.
   *
   * @param tenant verified tenant
   * @param managerEmployeeId the manager
   * @param from first day
   * @param to last day
   * @return whether such a line exists
   */
  public boolean managesDuring(
      TenantId tenant, UUID managerEmployeeId, LocalDate from, LocalDate to) {
    Boolean found =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM people.employment_assignment a"
                + " WHERE a.tenant_id = :tenant AND a.manager_employee_id = :manager"
                + " AND a.kind = 'MANAGER' AND a.superseded_by_change_id IS NULL"
                + " AND a.period && daterange(:from, :to, '[]'))",
            new MapSqlParameterSource()
                .addValue("tenant", tenant.value())
                .addValue("manager", managerEmployeeId)
                .addValue("from", from)
                .addValue("to", to),
            Boolean.class);
    return Boolean.TRUE.equals(found);
  }

  /**
   * Whether another appointment of the manager, revoked or not, overlaps the period (the exclusion
   * constraint's rule, checked first so the answer is the stable conflict).
   *
   * @param tenant verified tenant
   * @param managerEmployeeId the manager
   * @param from first day
   * @param to last day
   * @return whether one overlaps
   */
  public boolean overlaps(TenantId tenant, UUID managerEmployeeId, LocalDate from, LocalDate to) {
    Boolean found =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM people.leave_approval_substitution s"
                + " WHERE s.tenant_id = :tenant AND s.manager_employee_id = :manager"
                + " AND daterange(s.effective_from, s.effective_to, '[]')"
                + " && daterange(:from, :to, '[]'))",
            new MapSqlParameterSource()
                .addValue("tenant", tenant.value())
                .addValue("manager", managerEmployeeId)
                .addValue("from", from)
                .addValue("to", to),
            Boolean.class);
    return Boolean.TRUE.equals(found);
  }

  /**
   * Inserts an appointment.
   *
   * @param tenant verified tenant
   * @param substitution the appointment
   * @param createdBy verified subject
   * @throws org.springframework.dao.DataIntegrityViolationException when a constraint rejects it
   */
  public void insert(TenantId tenant, LeaveSubstitution substitution, String createdBy) {
    jdbc.update(
        "INSERT INTO people.leave_approval_substitution (id, tenant_id, manager_employee_id,"
            + " substitute_employee_id, effective_from, effective_to, reason_locale, reason_text,"
            + " created_at, created_by) VALUES (:id, :tenant, :manager, :substitute, :from, :to,"
            + " :locale, :reason, :createdAt, :createdBy)",
        new MapSqlParameterSource()
            .addValue("id", substitution.id())
            .addValue("tenant", tenant.value())
            .addValue("manager", substitution.managerEmployeeId())
            .addValue("substitute", substitution.substituteEmployeeId())
            .addValue("from", substitution.effectiveFrom())
            .addValue("to", substitution.effectiveTo())
            .addValue("locale", substitution.reasonLocale())
            .addValue("reason", substitution.reason())
            .addValue("createdAt", Timestamp.from(substitution.createdAt()))
            .addValue("createdBy", createdBy));
  }

  /**
   * An appointment of the tenant, without a lock (to discover its employees before the lock order's
   * employment step).
   *
   * @param tenant verified tenant
   * @param id appointment
   * @return the appointment
   */
  public Optional<LeaveSubstitution> find(TenantId tenant, UUID id) {
    return jdbc
        .query(
            SUBSTITUTION,
            new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("id", id),
            (rs, n) -> substitution(rs))
        .stream()
        .findFirst();
  }

  /**
   * Locks an appointment {@code FOR UPDATE} (after the employments) and re-reads it.
   *
   * @param tenant verified tenant
   * @param id appointment
   * @return the appointment
   */
  public Optional<LeaveSubstitution> lock(TenantId tenant, UUID id) {
    return jdbc
        .query(
            SUBSTITUTION + " FOR UPDATE",
            new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("id", id),
            (rs, n) -> substitution(rs))
        .stream()
        .findFirst();
  }

  /**
   * Whether the appointment was revoked.
   *
   * @param tenant verified tenant
   * @param substitutionId appointment
   * @return whether a revocation exists
   */
  public boolean revoked(TenantId tenant, UUID substitutionId) {
    Boolean found =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM people.leave_approval_substitution_revocation x"
                + " WHERE x.tenant_id = :tenant AND x.substitution_id = :id)",
            new MapSqlParameterSource()
                .addValue("tenant", tenant.value())
                .addValue("id", substitutionId),
            Boolean.class);
    return Boolean.TRUE.equals(found);
  }

  /**
   * Inserts a revocation.
   *
   * @param tenant verified tenant
   * @param revocation the revocation
   * @param revokedBy verified subject
   * @throws org.springframework.dao.DataIntegrityViolationException when a constraint rejects it
   */
  public void revoke(TenantId tenant, LeaveSubstitutionRevocation revocation, String revokedBy) {
    jdbc.update(
        "INSERT INTO people.leave_approval_substitution_revocation (id, tenant_id,"
            + " substitution_id, reason_locale, reason_text, revoked_at, revoked_by) VALUES (:id,"
            + " :tenant, :substitution, :locale, :reason, :revokedAt, :revokedBy)",
        new MapSqlParameterSource()
            .addValue("id", revocation.id())
            .addValue("tenant", tenant.value())
            .addValue("substitution", revocation.substitutionId())
            .addValue("locale", revocation.reasonLocale())
            .addValue("reason", revocation.reason())
            .addValue("revokedAt", Timestamp.from(revocation.revokedAt()))
            .addValue("revokedBy", revokedBy));
  }

  /**
   * One keyset page of the tenant's appointments, newest first.
   *
   * @param tenant verified tenant
   * @param afterCreatedAt creation time of the previous page's last row, or {@code null}
   * @param afterId that row's id, or {@code null}
   * @param limit rows to read
   * @return rows
   */
  public List<LeaveSubstitutionEntry> page(
      TenantId tenant, Instant afterCreatedAt, UUID afterId, int limit) {
    MapSqlParameterSource params =
        new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("limit", limit);
    StringBuilder sql = new StringBuilder(ENTRY).append(" WHERE s.tenant_id = :tenant");
    if (afterCreatedAt != null) {
      sql.append(" AND (s.created_at, s.id) < (:afterAt, :afterId)");
      params.addValue("afterAt", Timestamp.from(afterCreatedAt)).addValue("afterId", afterId);
    }
    sql.append(" ORDER BY s.created_at DESC, s.id DESC LIMIT :limit");
    return jdbc.query(sql.toString(), params, (rs, n) -> entry(rs));
  }

  /**
   * The creation time of an appointment of the tenant (a signed cursor's last row).
   *
   * @param tenant verified tenant
   * @param id appointment
   * @return its creation time
   */
  public Optional<Instant> createdAt(TenantId tenant, UUID id) {
    return jdbc
        .query(
            "SELECT created_at FROM people.leave_approval_substitution"
                + " WHERE tenant_id = :tenant AND id = :id",
            new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("id", id),
            (rs, n) -> rs.getTimestamp("created_at").toInstant())
        .stream()
        .findFirst();
  }

  private static LeaveSubstitution substitution(ResultSet rs) throws SQLException {
    return new LeaveSubstitution(
        rs.getObject("id", UUID.class),
        rs.getObject("manager_employee_id", UUID.class),
        rs.getObject("substitute_employee_id", UUID.class),
        rs.getObject("effective_from", LocalDate.class),
        rs.getObject("effective_to", LocalDate.class),
        rs.getString("reason_locale"),
        rs.getString("reason_text"),
        rs.getTimestamp("created_at").toInstant());
  }

  private static LeaveSubstitutionEntry entry(ResultSet rs) throws SQLException {
    LeaveSubstitution substitution = substitution(rs);
    UUID revocationId = rs.getObject("revocation_id", UUID.class);
    return new LeaveSubstitutionEntry(
        substitution,
        new DirectoryEmployee(
            substitution.managerEmployeeId(),
            rs.getString("manager_number"),
            rs.getString("manager_given"),
            rs.getString("manager_family")),
        new DirectoryEmployee(
            substitution.substituteEmployeeId(),
            rs.getString("substitute_number"),
            rs.getString("substitute_given"),
            rs.getString("substitute_family")),
        revocationId == null
            ? null
            : new LeaveSubstitutionRevocation(
                revocationId,
                substitution.id(),
                rs.getString("revocation_locale"),
                rs.getString("revocation_reason"),
                rs.getTimestamp("revoked_at").toInstant()));
  }
}
