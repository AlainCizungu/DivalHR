package com.divalhr.core.people.leave.internal;

import com.divalhr.core.people.leave.domain.ApprovalRoute;
import com.divalhr.core.people.leave.domain.DecisionAuthority;
import com.divalhr.core.people.leave.domain.Employment;
import com.divalhr.core.people.leave.domain.LeaveAmendment;
import com.divalhr.core.people.leave.domain.LeaveApprovalItem;
import com.divalhr.core.people.leave.domain.LeaveCancellation;
import com.divalhr.core.people.leave.domain.LeaveDecision;
import com.divalhr.core.people.leave.domain.LeaveRequest;
import com.divalhr.core.people.leave.domain.LeaveRequestState;
import com.divalhr.core.people.leave.domain.LeaveRouting;
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
 * Leave request storage (MVP-041A, V19), decisions (MVP-041B, V20), cancellations (MVP-041C, V21),
 * amendments and decision authorities (MVP-041D/E, V22) and the employment periods and reporting
 * lines requests are checked against. Every query names the verified tenant; employee reads are
 * bound to the employee resolved from the caller's own link, approval reads to the route and, for
 * managers, to the caller's own reporting lines. Runs in the caller's transaction.
 */
@Repository
public class JdbcLeaveRequestRepository {

  private static final String SELECT =
      "SELECT r.id, r.employee_id, r.employment_id, r.policy_version_id, r.start_date,"
          + " r.end_date, r.requested_amount, r.state, r.submitted_at, v.policy_id, p.code,"
          + " v.name_en, v.name_fr, v.unit, d.id AS decision_id, d.outcome, d.approval_route,"
          + " d.decision_authority, d.manager_employee_id, d.reason_locale, d.reason_text,"
          + " d.decided_at, x.id AS cancellation_id, x.reason_locale AS cancellation_locale,"
          + " x.reason_text AS cancellation_reason, x.cancelled_at, am.id AS amendment_id,"
          + " am.replacement_request_id, am.reason_locale AS amendment_locale,"
          + " am.reason_text AS amendment_reason, am.amended_at,"
          + " ar.original_request_id AS amended_from"
          + " FROM people.leave_request r"
          + " JOIN people.leave_policy_version v"
          + " ON v.tenant_id = r.tenant_id AND v.id = r.policy_version_id"
          + " JOIN people.leave_policy p ON p.tenant_id = v.tenant_id AND p.id = v.policy_id"
          + " LEFT JOIN people.leave_request_decision d"
          + " ON d.tenant_id = r.tenant_id AND d.request_id = r.id"
          + " LEFT JOIN people.leave_request_cancellation x"
          + " ON x.tenant_id = r.tenant_id AND x.request_id = r.id"
          + " LEFT JOIN people.leave_request_amendment am"
          + " ON am.tenant_id = r.tenant_id AND am.original_request_id = r.id"
          + " LEFT JOIN people.leave_request_amendment ar"
          + " ON ar.tenant_id = r.tenant_id AND ar.replacement_request_id = r.id";

  private static final String ITEM =
      "SELECT r.id, r.submitted_at, r.employee_id, e.employee_number, e.given_names,"
          + " e.family_name, v.policy_id, r.policy_version_id, p.code, v.name_en, v.name_fr,"
          + " v.unit, r.requested_amount, r.start_date, r.end_date, v.approval_route, r.state"
          + " FROM people.leave_request r"
          + " JOIN people.leave_policy_version v"
          + " ON v.tenant_id = r.tenant_id AND v.id = r.policy_version_id"
          + " JOIN people.leave_policy p ON p.tenant_id = v.tenant_id AND p.id = v.policy_id"
          + " JOIN people.employee e ON e.tenant_id = r.tenant_id AND e.id = r.employee_id";

  private static final String ROUTING =
      "SELECT r.id, r.employee_id, r.employment_id, v.policy_id, r.policy_version_id,"
          + " v.approval_route, r.start_date, r.end_date, r.state"
          + " FROM people.leave_request r"
          + " JOIN people.leave_policy_version v"
          + " ON v.tenant_id = r.tenant_id AND v.id = r.policy_version_id"
          + " WHERE r.tenant_id = :tenant AND r.id = :id";

  /**
   * The request's employment has an active (non-superseded) MANAGER line naming the manager on the
   * given day ({@code :manager}, {@code :day}; aliases {@code r} for the request).
   */
  private static final String REPORTS_TO =
      "EXISTS (SELECT 1 FROM people.employment_assignment a"
          + " WHERE a.tenant_id = r.tenant_id AND a.employment_id = r.employment_id"
          + " AND a.kind = 'MANAGER' AND a.superseded_by_change_id IS NULL"
          + " AND a.manager_employee_id = :manager"
          + " AND a.effective_from <= r.start_date"
          + " AND (a.effective_to IS NULL OR a.effective_to >= r.start_date))";

  /**
   * No active (non-superseded) MANAGER line covers the request's employment on its first day: a
   * routing exception (MVP-041E; alias {@code r} for the request). The same effective-dated rule as
   * {@link #REPORTS_TO}, for any manager.
   */
  private static final String NO_MANAGER =
      "NOT EXISTS (SELECT 1 FROM people.employment_assignment a"
          + " WHERE a.tenant_id = r.tenant_id AND a.employment_id = r.employment_id"
          + " AND a.kind = 'MANAGER' AND a.superseded_by_change_id IS NULL"
          + " AND a.effective_from <= r.start_date"
          + " AND (a.effective_to IS NULL OR a.effective_to >= r.start_date))";

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
   * Inserts a pending request (MVP-041A).
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

  // ------------------------------------------------------------------------------------------
  // Decisions (MVP-041B)
  // ------------------------------------------------------------------------------------------

  /**
   * A request's routing facts without a lock (to discover its employment before the lock order's
   * employment step).
   *
   * @param tenant verified tenant
   * @param id request
   * @return the routing facts, if the request exists in the tenant
   */
  public Optional<LeaveRouting> routing(TenantId tenant, UUID id) {
    return jdbc
        .query(
            ROUTING,
            new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("id", id),
            (rs, n) -> routing(rs))
        .stream()
        .findFirst();
  }

  /**
   * Locks the request {@code FOR UPDATE} (lock order step 5) and re-reads its routing facts.
   *
   * @param tenant verified tenant
   * @param id request
   * @return the routing facts
   */
  public Optional<LeaveRouting> lockRouting(TenantId tenant, UUID id) {
    return jdbc
        .query(
            ROUTING + " FOR UPDATE OF r",
            new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("id", id),
            (rs, n) -> routing(rs))
        .stream()
        .findFirst();
  }

  /**
   * Locks an employment {@code FOR SHARE} (lock order step 2) and reads its period: separations,
   * which lock it {@code FOR UPDATE}, wait until the decision commits, and a decision waiting on a
   * separation reads the separated period.
   *
   * @param tenant verified tenant
   * @param employmentId employment
   * @return the employment
   */
  public Optional<Employment> shareEmployment(TenantId tenant, UUID employmentId) {
    return jdbc
        .query(
            "SELECT id, effective_from, effective_to FROM people.employment"
                + " WHERE tenant_id = :tenant AND id = :id FOR SHARE",
            new MapSqlParameterSource()
                .addValue("tenant", tenant.value())
                .addValue("id", employmentId),
            (rs, n) ->
                new Employment(
                    rs.getObject("id", UUID.class),
                    rs.getObject("effective_from", LocalDate.class),
                    rs.getObject("effective_to", LocalDate.class)))
        .stream()
        .findFirst();
  }

  /**
   * Whether the manager is the request's active manager on its first day, for its employment.
   *
   * @param tenant verified tenant
   * @param requestId request
   * @param managerEmployeeId the caller's linked employee
   * @return whether the request reports to the manager
   */
  public boolean reportsTo(TenantId tenant, UUID requestId, UUID managerEmployeeId) {
    Boolean found =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM people.leave_request r WHERE r.tenant_id = :tenant"
                + " AND r.id = :id AND "
                + REPORTS_TO
                + ")",
            new MapSqlParameterSource()
                .addValue("tenant", tenant.value())
                .addValue("id", requestId)
                .addValue("manager", managerEmployeeId),
            Boolean.class);
    return Boolean.TRUE.equals(found);
  }

  /**
   * One keyset page of the pending {@code MANAGER} requests reporting to the manager on their first
   * day, newest first.
   *
   * @param tenant verified tenant
   * @param managerEmployeeId the caller's linked employee
   * @param afterSubmittedAt submission time of the previous page's last row, or {@code null}
   * @param afterId that row's id, or {@code null}
   * @param limit rows to read
   * @return rows
   */
  public List<LeaveApprovalItem> managerQueue(
      TenantId tenant, UUID managerEmployeeId, Instant afterSubmittedAt, UUID afterId, int limit) {
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("manager", managerEmployeeId)
            .addValue("limit", limit);
    String keyset = "";
    if (afterSubmittedAt != null) {
      keyset = " AND (r.submitted_at, r.id) < (:afterAt, :afterId)";
      params.addValue("afterAt", Timestamp.from(afterSubmittedAt)).addValue("afterId", afterId);
    }
    // The manager's active reporting lines (employment_assignment_reports), then, per line, the
    // newest pending MANAGER requests of that employment whose first day the line covers
    // (leave_request_employment_pending), merged newest first. Active lines of one employment
    // never overlap, so a request matches at most one line.
    String sql =
        "WITH lines AS MATERIALIZED (SELECT a.employment_id, a.effective_from, a.effective_to"
            + " FROM people.employment_assignment a"
            + " WHERE a.tenant_id = :tenant AND a.manager_employee_id = :manager"
            + " AND a.kind = 'MANAGER' AND a.superseded_by_change_id IS NULL),"
            + " queue AS (SELECT q.id FROM lines l CROSS JOIN LATERAL ("
            + "SELECT r.id FROM people.leave_request r"
            + " JOIN people.leave_policy_version v"
            + " ON v.tenant_id = r.tenant_id AND v.id = r.policy_version_id"
            + " WHERE r.tenant_id = :tenant AND r.employment_id = l.employment_id"
            + " AND r.state = 'PENDING' AND v.approval_route = 'MANAGER'"
            + " AND l.effective_from <= r.start_date"
            + " AND (l.effective_to IS NULL OR l.effective_to >= r.start_date)"
            + keyset
            + " ORDER BY r.submitted_at DESC, r.id DESC LIMIT :limit) q) "
            + ITEM
            + " JOIN queue ON queue.id = r.id WHERE r.tenant_id = :tenant"
            + " ORDER BY r.submitted_at DESC, r.id DESC LIMIT :limit";
    return jdbc.query(sql, params, (rs, n) -> item(rs));
  }

  /**
   * One keyset page of the pending {@code TENANT_ADMIN} requests, newest first.
   *
   * @param tenant verified tenant
   * @param afterSubmittedAt submission time of the previous page's last row, or {@code null}
   * @param afterId that row's id, or {@code null}
   * @param limit rows to read
   * @return rows
   */
  public List<LeaveApprovalItem> adminQueue(
      TenantId tenant, Instant afterSubmittedAt, UUID afterId, int limit) {
    MapSqlParameterSource params =
        new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("limit", limit);
    StringBuilder sql =
        new StringBuilder(ITEM)
            .append(" WHERE r.tenant_id = :tenant AND r.state = 'PENDING'")
            .append(" AND v.approval_route = 'TENANT_ADMIN'");
    return page(sql, params, afterSubmittedAt, afterId);
  }

  private List<LeaveApprovalItem> page(
      StringBuilder sql, MapSqlParameterSource params, Instant afterSubmittedAt, UUID afterId) {
    if (afterSubmittedAt != null) {
      sql.append(" AND (r.submitted_at, r.id) < (:afterAt, :afterId)");
      params.addValue("afterAt", Timestamp.from(afterSubmittedAt)).addValue("afterId", afterId);
    }
    sql.append(" ORDER BY r.submitted_at DESC, r.id DESC LIMIT :limit");
    return jdbc.query(sql.toString(), params, (rs, n) -> item(rs));
  }

  /**
   * The submission time of a request in the tenant (a signed cursor's last row).
   *
   * @param tenant verified tenant
   * @param id request
   * @return its submission time
   */
  public Optional<Instant> submittedAt(TenantId tenant, UUID id) {
    return jdbc
        .query(
            "SELECT submitted_at FROM people.leave_request WHERE tenant_id = :tenant AND id = :id",
            new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("id", id),
            (rs, n) -> rs.getTimestamp("submitted_at").toInstant())
        .stream()
        .findFirst();
  }

  /**
   * Records a decision and moves its pending request to the decision's outcome.
   *
   * @param tenant verified tenant
   * @param decision the decision
   * @param decidedBy verified subject
   * @throws IllegalStateException when the request was no longer pending (the caller holds its row
   *     lock, so this never happens)
   */
  public void decide(TenantId tenant, LeaveDecision decision, String decidedBy) {
    jdbc.update(
        "INSERT INTO people.leave_request_decision (id, tenant_id, request_id, outcome,"
            + " approval_route, decision_authority, manager_employee_id, reason_locale,"
            + " reason_text, decided_at, decided_by) VALUES (:id, :tenant, :request, :outcome,"
            + " :route, :authority, :manager, :locale, :reason, :decidedAt, :decidedBy)",
        new MapSqlParameterSource()
            .addValue("id", decision.id())
            .addValue("tenant", tenant.value())
            .addValue("request", decision.requestId())
            .addValue("outcome", decision.outcome().name())
            .addValue("route", decision.route().name())
            .addValue("authority", decision.authority().name())
            .addValue("manager", decision.managerEmployeeId())
            .addValue("locale", decision.reasonLocale())
            .addValue("reason", decision.reason())
            .addValue("decidedAt", Timestamp.from(decision.decidedAt()))
            .addValue("decidedBy", decidedBy));
    int moved =
        jdbc.update(
            "UPDATE people.leave_request SET state = :state"
                + " WHERE tenant_id = :tenant AND id = :id AND state = 'PENDING'",
            new MapSqlParameterSource()
                .addValue("state", decision.outcome().name())
                .addValue("tenant", tenant.value())
                .addValue("id", decision.requestId()));
    if (moved != 1) {
      throw new IllegalStateException("leave request was not pending under its row lock");
    }
  }

  /**
   * Runs the deferred terminal-evidence checks now ({@code leave_request_decided}: exactly one
   * matching kind of evidence per request, V20 extended by V21 and V22; {@code
   * leave_request_decision_consistent}, V20/V22; {@code leave_request_cancellation_consistent},
   * V21; {@code leave_request_amendment_consistent}, V22), so that a violation surfaces inside the
   * business work, where it is mapped by constraint name, before the audit and outbox records are
   * written.
   *
   * @param tenant verified tenant whose transaction it is
   */
  public void checkTerminalConsistency(TenantId tenant) {
    java.util.Objects.requireNonNull(tenant, "tenant");
    jdbc.getJdbcOperations()
        .execute(
            "SET CONSTRAINTS people.leave_request_decided,"
                + " people.leave_request_decision_consistent,"
                + " people.leave_request_cancellation_consistent,"
                + " people.leave_request_amendment_consistent IMMEDIATE");
  }

  // ------------------------------------------------------------------------------------------
  // Cancellations (MVP-041C)
  // ------------------------------------------------------------------------------------------

  /**
   * Locks one of the employee's own requests {@code FOR UPDATE} (lock order step 5) and reads its
   * routing facts. Another employee's or another tenant's request is not found.
   *
   * @param tenant verified tenant
   * @param employeeId the employee resolved from the caller's own active link
   * @param id request
   * @return the routing facts, if the request is the employee's
   */
  public Optional<LeaveRouting> lockOwn(TenantId tenant, UUID employeeId, UUID id) {
    return jdbc
        .query(
            ROUTING + " AND r.employee_id = :employee FOR UPDATE OF r",
            new MapSqlParameterSource()
                .addValue("tenant", tenant.value())
                .addValue("id", id)
                .addValue("employee", employeeId),
            (rs, n) -> routing(rs))
        .stream()
        .findFirst();
  }

  /**
   * Records a cancellation and moves its pending request to {@code CANCELLED}, which releases its
   * dates in the same transaction (the overlap exclusion covers PENDING and APPROVED only).
   *
   * @param tenant verified tenant
   * @param cancellation the cancellation
   * @param cancelledBy verified subject
   * @throws IllegalStateException when the request was no longer pending (the caller holds its row
   *     lock, so this never happens)
   */
  public void cancel(TenantId tenant, LeaveCancellation cancellation, String cancelledBy) {
    jdbc.update(
        "INSERT INTO people.leave_request_cancellation (id, tenant_id, request_id, reason_locale,"
            + " reason_text, cancelled_at, cancelled_by) VALUES (:id, :tenant, :request, :locale,"
            + " :reason, :cancelledAt, :cancelledBy)",
        new MapSqlParameterSource()
            .addValue("id", cancellation.id())
            .addValue("tenant", tenant.value())
            .addValue("request", cancellation.requestId())
            .addValue("locale", cancellation.reasonLocale())
            .addValue("reason", cancellation.reason())
            .addValue("cancelledAt", Timestamp.from(cancellation.cancelledAt()))
            .addValue("cancelledBy", cancelledBy));
    int moved =
        jdbc.update(
            "UPDATE people.leave_request SET state = 'CANCELLED'"
                + " WHERE tenant_id = :tenant AND id = :id AND state = 'PENDING'",
            new MapSqlParameterSource()
                .addValue("tenant", tenant.value())
                .addValue("id", cancellation.requestId()));
    if (moved != 1) {
      throw new IllegalStateException("leave request was not pending under its row lock");
    }
  }

  // ------------------------------------------------------------------------------------------
  // Amendments (MVP-041D)
  // ------------------------------------------------------------------------------------------

  /**
   * Replaces a pending request: moves the original to {@code AMENDED} first (which releases its
   * dates: the overlap exclusion covers PENDING and APPROVED only), then inserts the pending
   * replacement and the amendment evidence, all in the caller's transaction.
   *
   * @param tenant verified tenant
   * @param amendment the amendment
   * @param replacement the pending replacement
   * @param amendedBy verified subject
   * @throws IllegalStateException when the original was no longer pending (the caller holds its row
   *     lock, so this never happens)
   * @throws org.springframework.dao.DataIntegrityViolationException when a constraint rejects the
   *     replacement (see {@link LeaveConstraintViolations})
   */
  public void amend(
      TenantId tenant, LeaveAmendment amendment, LeaveRequest replacement, String amendedBy) {
    int moved =
        jdbc.update(
            "UPDATE people.leave_request SET state = 'AMENDED'"
                + " WHERE tenant_id = :tenant AND id = :id AND state = 'PENDING'",
            new MapSqlParameterSource()
                .addValue("tenant", tenant.value())
                .addValue("id", amendment.originalRequestId()));
    if (moved != 1) {
      throw new IllegalStateException("leave request was not pending under its row lock");
    }
    insert(tenant, replacement, amendedBy);
    jdbc.update(
        "INSERT INTO people.leave_request_amendment (id, tenant_id, original_request_id,"
            + " replacement_request_id, reason_locale, reason_text, amended_at, amended_by)"
            + " VALUES (:id, :tenant, :original, :replacement, :locale, :reason, :amendedAt,"
            + " :amendedBy)",
        new MapSqlParameterSource()
            .addValue("id", amendment.id())
            .addValue("tenant", tenant.value())
            .addValue("original", amendment.originalRequestId())
            .addValue("replacement", amendment.replacementRequestId())
            .addValue("locale", amendment.reasonLocale())
            .addValue("reason", amendment.reason())
            .addValue("amendedAt", Timestamp.from(amendment.amendedAt()))
            .addValue("amendedBy", amendedBy));
  }

  // ------------------------------------------------------------------------------------------
  // Routing exceptions (MVP-041E)
  // ------------------------------------------------------------------------------------------

  /**
   * One keyset page of the routing exceptions, newest first: the pending {@code MANAGER} requests
   * that no active, non-superseded MANAGER line covers on their first day. Derived on every read;
   * never stored.
   *
   * @param tenant verified tenant
   * @param afterSubmittedAt submission time of the previous page's last row, or {@code null}
   * @param afterId that row's id, or {@code null}
   * @param limit rows to read
   * @return rows
   */
  public List<LeaveApprovalItem> exceptionQueue(
      TenantId tenant, Instant afterSubmittedAt, UUID afterId, int limit) {
    MapSqlParameterSource params =
        new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("limit", limit);
    StringBuilder sql =
        new StringBuilder(ITEM)
            .append(" WHERE r.tenant_id = :tenant AND r.state = 'PENDING'")
            .append(" AND v.approval_route = 'MANAGER' AND ")
            .append(NO_MANAGER);
    return page(sql, params, afterSubmittedAt, afterId);
  }

  /**
   * Whether no active, non-superseded MANAGER line covers the request's employment on its first day
   * (read under the tenant's manager-graph lock by a routing-exception decision).
   *
   * @param tenant verified tenant
   * @param requestId request
   * @return whether the request has no qualifying manager
   */
  public boolean noQualifyingManager(TenantId tenant, UUID requestId) {
    Boolean found =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM people.leave_request r WHERE r.tenant_id = :tenant"
                + " AND r.id = :id AND "
                + NO_MANAGER
                + ")",
            new MapSqlParameterSource()
                .addValue("tenant", tenant.value())
                .addValue("id", requestId),
            Boolean.class);
    return Boolean.TRUE.equals(found);
  }

  /**
   * The authority a request's decision was taken under (an override's replay).
   *
   * @param tenant verified tenant
   * @param requestId request
   * @return the authority, if the request has a decision in the tenant
   */
  public Optional<DecisionAuthority> decisionAuthority(TenantId tenant, UUID requestId) {
    return jdbc
        .query(
            "SELECT decision_authority FROM people.leave_request_decision"
                + " WHERE tenant_id = :tenant AND request_id = :id",
            new MapSqlParameterSource()
                .addValue("tenant", tenant.value())
                .addValue("id", requestId),
            (rs, n) -> DecisionAuthority.valueOf(rs.getString("decision_authority")))
        .stream()
        .findFirst();
  }

  private static LeaveRouting routing(ResultSet rs) throws SQLException {
    return new LeaveRouting(
        rs.getObject("id", UUID.class),
        rs.getObject("employee_id", UUID.class),
        rs.getObject("employment_id", UUID.class),
        rs.getObject("policy_id", UUID.class),
        rs.getObject("policy_version_id", UUID.class),
        ApprovalRoute.valueOf(rs.getString("approval_route")),
        rs.getObject("start_date", LocalDate.class),
        rs.getObject("end_date", LocalDate.class),
        LeaveRequestState.valueOf(rs.getString("state")));
  }

  private static LeaveApprovalItem item(ResultSet rs) throws SQLException {
    return new LeaveApprovalItem(
        rs.getObject("id", UUID.class),
        rs.getTimestamp("submitted_at").toInstant(),
        rs.getObject("employee_id", UUID.class),
        rs.getString("employee_number"),
        rs.getString("given_names"),
        rs.getString("family_name"),
        rs.getObject("policy_id", UUID.class),
        rs.getObject("policy_version_id", UUID.class),
        rs.getString("code"),
        rs.getString("name_en"),
        rs.getString("name_fr"),
        LeaveUnit.valueOf(rs.getString("unit")),
        rs.getBigDecimal("requested_amount"),
        rs.getObject("start_date", LocalDate.class),
        rs.getObject("end_date", LocalDate.class),
        ApprovalRoute.valueOf(rs.getString("approval_route")),
        LeaveRequestState.valueOf(rs.getString("state")));
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
        rs.getTimestamp("submitted_at").toInstant(),
        decision(rs),
        cancellation(rs),
        amendment(rs),
        rs.getObject("amended_from", UUID.class));
  }

  private static LeaveAmendment amendment(ResultSet rs) throws SQLException {
    UUID id = rs.getObject("amendment_id", UUID.class);
    if (id == null) {
      return null;
    }
    return new LeaveAmendment(
        id,
        rs.getObject("id", UUID.class),
        rs.getObject("replacement_request_id", UUID.class),
        rs.getString("amendment_locale"),
        rs.getString("amendment_reason"),
        rs.getTimestamp("amended_at").toInstant());
  }

  private static LeaveCancellation cancellation(ResultSet rs) throws SQLException {
    UUID id = rs.getObject("cancellation_id", UUID.class);
    if (id == null) {
      return null;
    }
    return new LeaveCancellation(
        id,
        rs.getObject("id", UUID.class),
        rs.getString("cancellation_locale"),
        rs.getString("cancellation_reason"),
        rs.getTimestamp("cancelled_at").toInstant());
  }

  private static LeaveDecision decision(ResultSet rs) throws SQLException {
    UUID id = rs.getObject("decision_id", UUID.class);
    if (id == null) {
      return null;
    }
    return new LeaveDecision(
        id,
        rs.getObject("id", UUID.class),
        LeaveRequestState.valueOf(rs.getString("outcome")),
        ApprovalRoute.valueOf(rs.getString("approval_route")),
        DecisionAuthority.valueOf(rs.getString("decision_authority")),
        rs.getObject("manager_employee_id", UUID.class),
        rs.getString("reason_locale"),
        rs.getString("reason_text"),
        rs.getTimestamp("decided_at").toInstant());
  }
}
