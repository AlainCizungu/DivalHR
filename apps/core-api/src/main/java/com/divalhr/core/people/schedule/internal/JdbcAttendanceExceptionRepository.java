package com.divalhr.core.people.schedule.internal;

import com.divalhr.core.people.schedule.domain.AttendanceExceptions;
import com.divalhr.core.people.schedule.domain.AttendanceExceptions.Filter;
import com.divalhr.core.people.schedule.domain.AttendanceExceptions.Position;
import com.divalhr.core.people.schedule.domain.AttendanceExceptions.Row;
import com.divalhr.core.people.schedule.domain.AttendanceExceptions.Scope;
import com.divalhr.core.people.schedule.domain.AttendanceExceptions.Self;
import com.divalhr.core.people.schedule.domain.AttendanceExceptions.Team;
import com.divalhr.core.people.schedule.domain.AttendanceExceptions.Tenant;
import com.divalhr.core.people.schedule.domain.AttendanceExceptions.Type;
import com.divalhr.core.platform.tenancy.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * MVP-060C (C60C-2, C60C-6): the factual attendance exceptions, derived at one instant by one
 * read-only query; nothing is stored. Two branches, merged in the keyset order (scheduled start
 * descending, slot descending, employee descending, type rank ascending):
 *
 * <ul>
 *   <li>recorded: the attendance records checked in by the instant, with LATE_CHECK_IN,
 *       EARLY_CHECK_OUT (a check-out recorded by the instant) and MISSING_CHECK_OUT (no check-out
 *       by the instant and the inclusive deadline passed); always bound to the record's employee
 *       and employment, whatever happened to the schedule later;
 *   <li>missing: the ended slots of schedules not cancelled or replaced by the instant whose
 *       effective assignee (the approved substitute when approved before the end, otherwise the
 *       published assignee: immutable evidence, not the coverage projection) has no record for the
 *       slot by the instant.
 * </ul>
 *
 * <p>Every branch is bounded by the tenant and by an instant (or, for the tenant view, a schedule
 * date) window around the site-local date range so that an index drives it: the employee's records
 * ({@code attendance_record_history}) and slots ({@code work_schedule_slot_employee_start}), the
 * manager's reports ({@code employment_assignment_reports}), or the tenant's schedules by date
 * ({@code work_schedule_list_order}).
 */
@Repository
public class JdbcAttendanceExceptionRepository {

  private static final String TOLERANCE =
      "make_interval(mins => " + AttendanceExceptions.TOLERANCE.toMinutes() + ")";

  /** A schedule's period is at most 31 dates, so its slots start at most 30 dates after it. */
  private static final int SCHEDULE_SPAN_DAYS = 30;

  private static final String REPORTEES =
      "(SELECT ma.employee_id FROM people.employment_assignment ma WHERE ma.tenant_id = :tenant"
          + " AND ma.kind = 'MANAGER' AND ma.superseded_by_change_id IS NULL"
          + " AND ma.manager_employee_id = :manager)";

  private static final String SLOT_JOIN =
      " JOIN people.work_schedule s ON s.tenant_id = l.tenant_id AND s.id = l.schedule_id";

  private final NamedParameterJdbcTemplate jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc named-parameter JDBC
   */
  public JdbcAttendanceExceptionRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * One keyset page of the exceptions in the scope at the instant.
   *
   * @param tenant verified tenant
   * @param scope whose exceptions
   * @param filter validated filters
   * @param asOf the view's pinned instant
   * @param after the previous page's last position, or {@code null}
   * @param limit rows to read
   * @return the rows
   */
  public List<Row> page(
      TenantId tenant, Scope scope, Filter filter, Instant asOf, Position after, int limit) {
    Statement page = statement(tenant, scope, filter, asOf, after, limit);
    return jdbc.query(page.sql(), page.params(), (rs, n) -> row(rs));
  }

  /**
   * The page statement and its parameters (also explained by the query-plan tests, C60C-6).
   *
   * @param sql the statement
   * @param params its parameters
   */
  public record Statement(String sql, MapSqlParameterSource params) {}

  /**
   * The statement of {@link #page}.
   *
   * @param tenant verified tenant
   * @param scope whose exceptions
   * @param filter validated filters
   * @param asOf the view's pinned instant
   * @param after the previous page's last position, or {@code null}
   * @param limit rows to read
   * @return the statement
   */
  public static Statement statement(
      TenantId tenant, Scope scope, Filter filter, Instant asOf, Position after, int limit) {
    MapSqlParameterSource params = params(tenant, scope, filter, asOf).addValue("limit", limit);
    StringBuilder sql =
        new StringBuilder("SELECT x.*, p.employee_number, p.given_names, p.family_name FROM (")
            .append(union(scope, filter, null, null))
            .append(") x JOIN people.employee p ON p.tenant_id = :tenant AND p.id = x.employee_id");
    if (after != null) {
      sql.append(
          " WHERE (x.start_at, x.slot_id, x.employee_id, -x.rank)"
              + " < (:afterStart, :afterSlot, :afterEmployee, :afterRank)");
      params
          .addValue("afterStart", Timestamp.from(after.startAt()))
          .addValue("afterSlot", after.slotId())
          .addValue("afterEmployee", after.employeeId())
          .addValue("afterRank", -after.type().rank());
    }
    sql.append(
        " ORDER BY x.start_at DESC, x.slot_id DESC, x.employee_id DESC, x.rank LIMIT :limit");
    return new Statement(sql.toString(), params);
  }

  /**
   * A cursor's position, reconstructed from its type and ID (a record, or the slot of a missing
   * check-in) inside the same scope, filters and instant: a position no longer in scope (a
   * reporting line that moved, a foreign or unknown ID) is empty, so nothing about it is revealed.
   *
   * @param tenant verified tenant
   * @param scope whose exceptions
   * @param filter validated filters
   * @param asOf the view's pinned instant
   * @param type the position's type
   * @param id the record (recorded types) or the slot (missing check-in)
   * @return the position
   */
  public Optional<Position> position(
      TenantId tenant, Scope scope, Filter filter, Instant asOf, Type type, UUID id) {
    MapSqlParameterSource params = params(tenant, scope, filter, asOf).addValue("positionId", id);
    String sql =
        "SELECT x.* FROM ("
            + union(scope, filter, type, id)
            + ") x WHERE x.rank = "
            + type.rank()
            + " LIMIT 2";
    List<Position> found =
        jdbc.query(
            sql,
            params,
            (rs, n) ->
                new Position(
                    rs.getTimestamp("start_at").toInstant(),
                    rs.getObject("slot_id", UUID.class),
                    rs.getObject("employee_id", UUID.class),
                    type));
    return found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty();
  }

  private static MapSqlParameterSource params(
      TenantId tenant, Scope scope, Filter filter, Instant asOf) {
    LocalDate from;
    LocalDate to;
    if (filter.from() != null) {
      from = filter.from();
      to = filter.to();
    } else {
      LocalDate today = asOf.atZone(ZoneOffset.UTC).toLocalDate();
      from = today.minusDays(AttendanceExceptions.DEFAULT_RANGE_DAYS);
      to = today.plusDays(1);
    }
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("asOf", Timestamp.from(asOf))
            // Site-local dates lie within a day of UTC: the instant window around them.
            .addValue(
                "lo", Timestamp.from(from.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()))
            .addValue("hi", Timestamp.from(to.plusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant()))
            .addValue("loDate", from.minusDays(1L + SCHEDULE_SPAN_DAYS))
            .addValue("hiDate", to.plusDays(1))
            .addValue("defaultDays", AttendanceExceptions.DEFAULT_RANGE_DAYS - 1);
    if (filter.from() != null) {
      params.addValue("from", filter.from()).addValue("to", filter.to());
    }
    if (filter.type() != null) {
      params.addValue("type", filter.type().name());
    }
    switch (scope) {
      case Self self -> params.addValue("employee", self.employeeId());
      case Team team -> params.addValue("manager", team.managerId());
      case Tenant view -> {
        if (view.siteId() != null) {
          params.addValue("site", view.siteId());
        }
      }
    }
    return params;
  }

  /** The two branches; {@code positionType} restricts them to one position's row. */
  private static String union(Scope scope, Filter filter, Type positionType, UUID positionId) {
    boolean recorded = filter.type() != Type.MISSING_CHECK_IN;
    boolean missing = filter.type() == null || filter.type() == Type.MISSING_CHECK_IN;
    if (positionType != null) {
      recorded &= positionType != Type.MISSING_CHECK_IN;
      missing &= positionType == Type.MISSING_CHECK_IN;
    }
    StringBuilder sql = new StringBuilder();
    if (recorded) {
      sql.append(recordedBranch(scope, filter, positionId != null));
    }
    if (missing) {
      if (recorded) {
        sql.append(" UNION ALL ");
      }
      sql.append(missingBranch(scope, filter, positionId != null));
    }
    if (!recorded && !missing) {
      // An impossible combination: no row, same columns.
      sql.append(missingBranch(scope, filter, false)).append(" AND FALSE");
    }
    return sql.toString();
  }

  /** The site-local start date inside the explicit range, or the site's default range. */
  private static String dates(Filter filter) {
    return filter.from() != null
        ? " AND l.start_local::date BETWEEN :from AND :to"
        : " AND l.start_local::date BETWEEN"
            + " (CAST(:asOf AS timestamptz) AT TIME ZONE s.site_timezone)::date - :defaultDays"
            + " AND (CAST(:asOf AS timestamptz) AT TIME ZONE s.site_timezone)::date";
  }

  private static String tenantWindow(Tenant view) {
    return " AND s.first_date BETWEEN :loDate AND :hiDate"
        + (view.siteId() != null ? " AND s.site_id = :site" : "");
  }

  /**
   * The tenant's slots of the schedules in the date window (optionally of one site), driven from
   * the schedules ({@code work_schedule_list_order}) to their slots ({@code
   * work_schedule_slot_position}); the fence ({@code OFFSET 0}) keeps the planner from reading the
   * tenant's whole slot history instead.
   */
  private static String tenantSlots(Tenant view) {
    return "(SELECT l2.* FROM people.work_schedule s2 CROSS JOIN LATERAL"
        + " (SELECT l3.* FROM people.work_schedule_slot l3 WHERE l3.tenant_id = s2.tenant_id"
        + " AND l3.schedule_id = s2.id OFFSET 0) l2 WHERE s2.tenant_id = :tenant"
        + " AND s2.first_date BETWEEN :loDate AND :hiDate"
        + (view.siteId() != null ? " AND s2.site_id = :site" : "")
        + ") l";
  }

  /** The records of the branch: by employee (self, manager) or by the tenant's slots. */
  private static String recordedFrom(Scope scope) {
    if (scope instanceof Tenant view) {
      return " FROM "
          + tenantSlots(view)
          + " JOIN people.attendance_record a ON a.tenant_id = l.tenant_id AND a.slot_id = l.id";
    }
    return " FROM people.attendance_record a"
        + " JOIN people.work_schedule_slot l ON l.tenant_id = a.tenant_id"
        + " AND l.id = a.slot_id";
  }

  private static String recordedBranch(Scope scope, Filter filter, boolean position) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT t.type, t.rank, a.employee_id, a.id AS record_id, a.checked_in_at AS r_in,"
                + " CASE WHEN a.checked_out_at <= :asOf THEN a.checked_out_at END AS r_out,"
                + " t.variance,"
                + JdbcAttendanceRepository.SHIFT_COLUMNS
                + recordedFrom(scope)
                + SLOT_JOIN
                + " CROSS JOIN LATERAL (VALUES"
                + " ('LATE_CHECK_IN', 1, a.checked_in_at > a.slot_start_at + "
                + TOLERANCE
                + ", CEIL(EXTRACT(EPOCH FROM a.checked_in_at - a.slot_start_at) / 60)::int),"
                + " ('EARLY_CHECK_OUT', 2, a.checked_out_at IS NOT NULL"
                + " AND a.checked_out_at <= :asOf AND a.checked_out_at < a.slot_end_at - "
                + TOLERANCE
                + ", CEIL(EXTRACT(EPOCH FROM a.slot_end_at - a.checked_out_at) / 60)::int),"
                + " ('MISSING_CHECK_OUT', 3, (a.checked_out_at IS NULL OR a.checked_out_at > :asOf)"
                + " AND a.check_out_until < :asOf, NULL::int)) t(type, rank, hit, variance)"
                + " WHERE a.tenant_id = :tenant AND a.checked_in_at <= :asOf AND t.hit"
                + " AND a.slot_start_at >= :lo AND a.slot_start_at < :hi"
                + dates(filter));
    if (filter.type() != null) {
      sql.append(" AND t.type = :type");
    }
    if (position) {
      sql.append(" AND a.id = :positionId");
    }
    switch (scope) {
      case Self self -> sql.append(" AND a.employee_id = :employee");
      case Team team ->
          sql.append(" AND a.employee_id <> :manager AND a.employee_id IN ")
              .append(REPORTEES)
              .append(" AND")
              .append(JdbcAttendanceRepository.reports("a.employment_id"));
      case Tenant view -> sql.append(tenantWindow(view));
    }
    return sql.toString();
  }

  /**
   * The candidate slots of the missing branch: for the employee and the manager, the union of the
   * slots published to the employees in scope ({@code work_schedule_slot_employee_start}) and those
   * approved to them as substitutes (the tenant's approvals, {@code
   * work_schedule_substitution_decision_one_approval}), each source driven by its own index; for
   * the tenant, its slots.
   */
  private static String candidates(Scope scope) {
    String who =
        switch (scope) {
          case Self self -> "= :employee";
          case Team team -> "IN " + REPORTEES;
          case Tenant view -> null;
        };
    if (who == null) {
      return tenantSlots((Tenant) scope);
    }
    return "(SELECT l0.* FROM people.work_schedule_slot l0 WHERE l0.tenant_id = :tenant"
        + " AND l0.assigned_employee_id "
        + who
        + " AND l0.start_at >= :lo AND l0.start_at < :hi"
        + " UNION SELECT l1.* FROM people.work_schedule_substitution_decision d1"
        + " JOIN people.work_schedule_slot l1 ON l1.tenant_id = d1.tenant_id AND l1.id = d1.slot_id"
        + " WHERE d1.tenant_id = :tenant AND d1.outcome = 'APPROVED'"
        + " AND d1.replacement_employee_id "
        + who
        + " AND l1.start_at >= :lo AND l1.start_at < :hi) l";
  }

  private static String missingBranch(Scope scope, Filter filter, boolean position) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT 'MISSING_CHECK_IN' AS type, 0 AS rank, tg.employee_id, NULL::uuid AS record_id,"
                + " NULL::timestamptz AS r_in, NULL::timestamptz AS r_out, NULL::int AS variance,"
                + JdbcAttendanceRepository.SHIFT_COLUMNS
                + " FROM "
                + candidates(scope)
                + SLOT_JOIN
                + " CROSS JOIN LATERAL (SELECT COALESCE((SELECT d.replacement_employee_id FROM"
                + " people.work_schedule_substitution_decision d WHERE d.tenant_id = l.tenant_id"
                + " AND d.slot_id = l.id AND d.outcome = 'APPROVED' AND d.decided_at < l.end_at),"
                + " l.assigned_employee_id) AS employee_id) tg WHERE l.tenant_id = :tenant AND"
                + " l.assigned_employee_id IS NOT NULL AND l.end_at <= :asOf AND l.start_at >= :lo"
                + " AND l.start_at < :hi"
                + dates(filter)
                + " AND NOT EXISTS (SELECT 1 FROM people.work_schedule_cancellation c"
                + " WHERE c.tenant_id = s.tenant_id AND c.schedule_id = s.id"
                + " AND c.cancelled_at <= :asOf)"
                + " AND NOT EXISTS (SELECT 1 FROM people.work_schedule_replacement r"
                + " WHERE r.tenant_id = s.tenant_id AND r.schedule_id = s.id"
                + " AND r.replaced_at <= :asOf)"
                + " AND NOT EXISTS (SELECT 1 FROM people.attendance_record x"
                + " WHERE x.tenant_id = l.tenant_id AND x.slot_id = l.id"
                + " AND x.employee_id = tg.employee_id AND x.checked_in_at <= :asOf)");
    if (position) {
      sql.append(" AND l.id = :positionId");
    }
    switch (scope) {
      case Self self -> sql.append(" AND tg.employee_id = :employee");
      case Team team ->
          // A per-slot lookup of the target's employments (employment_employee); LIMIT keeps the
          // planner from hashing the tenant's whole employment table for this check.
          sql.append(
                  " AND tg.employee_id <> :manager AND (SELECT true FROM people.employment e"
                      + " WHERE e.tenant_id = l.tenant_id AND e.employee_id = tg.employee_id AND")
              .append(JdbcAttendanceRepository.QUALIFIES)
              .append(" AND")
              .append(JdbcAttendanceRepository.reports("e.id"))
              .append(" LIMIT 1)");
      case Tenant view -> sql.append(tenantWindow(view));
    }
    return sql.toString();
  }

  private static Row row(ResultSet rs) throws SQLException {
    Timestamp in = rs.getTimestamp("r_in");
    Timestamp out = rs.getTimestamp("r_out");
    int minutes = rs.getInt("variance");
    Integer variance = rs.wasNull() ? null : minutes;
    return new Row(
        Type.valueOf(rs.getString("type")),
        JdbcAttendanceRepository.shift(rs),
        rs.getObject("employee_id", UUID.class),
        rs.getString("employee_number"),
        rs.getString("given_names"),
        rs.getString("family_name"),
        rs.getObject("record_id", UUID.class),
        in == null ? null : in.toInstant(),
        out == null ? null : out.toInstant(),
        variance);
  }
}
