package com.divalhr.core.people.schedule.internal;

import static com.divalhr.core.people.schedule.internal.JdbcAttendanceExceptionRepository.EFFECTIVE_ASSIGNEE;
import static com.divalhr.core.people.schedule.internal.JdbcAttendanceExceptionRepository.NOT_TERMINAL_AT_AS_OF;
import static com.divalhr.core.people.schedule.internal.JdbcAttendanceExceptionRepository.SCHEDULE_SPAN_DAYS;
import static com.divalhr.core.people.schedule.internal.JdbcAttendanceExceptionRepository.SLOT_JOIN;
import static com.divalhr.core.people.schedule.internal.JdbcAttendanceExceptionRepository.TOLERANCE;

import com.divalhr.core.people.schedule.domain.AttendanceExceptions;
import com.divalhr.core.people.schedule.domain.OperationalStatus;
import com.divalhr.core.people.schedule.domain.OperationalStatus.NotCheckedIn;
import com.divalhr.core.people.schedule.domain.OperationalStatus.Organization;
import com.divalhr.core.people.schedule.domain.OperationalStatus.OverdueLeave;
import com.divalhr.core.people.schedule.domain.OperationalStatus.OverdueTimesheet;
import com.divalhr.core.people.schedule.domain.OperationalStatus.Person;
import com.divalhr.core.people.schedule.domain.OperationalStatus.Scope;
import com.divalhr.core.people.schedule.domain.OperationalStatus.Shifts;
import com.divalhr.core.people.schedule.domain.OperationalStatus.Team;
import com.divalhr.core.people.schedule.domain.OperationalStatus.Waiting;
import com.divalhr.core.people.schedule.domain.Substitution.Shift;
import com.divalhr.core.platform.tenancy.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * MVP-100 (ADR 0026): the operational status of today, derived at one instant by read-only
 * statements; nothing is stored. The attendance rules are the ones of MVP-060C, defined once in
 * {@link JdbcAttendanceExceptionRepository} (tolerance, effective assignee, terminal schedules,
 * candidate slots) and {@link JdbcAttendanceRepository} (qualifying employment, reporting line);
 * the timesheet route is {@link TimesheetQueueStatement}'s. Every statement names the verified
 * tenant and is bounded by the tenant and by an instant window around the instant, so the existing
 * indexes drive it.
 *
 * <p>The pending-leave rule ({@code state = 'PENDING'}, the {@code MANAGER} route and the active
 * MANAGER line on the first day) is the one of the leave inboxes, in SQL; an integration test
 * compares the two.
 */
@Repository
public class JdbcOperationalStatusRepository {

  /** Today's slots: starting on the site-local date of the instant, or in progress at it. */
  private static final String TODAY =
      " AND (l.start_local::date = (CAST(:asOf AS timestamptz) AT TIME ZONE s.site_timezone)::date"
          + " OR (l.start_at <= :asOf AND l.end_at > :asOf))";

  private static final String GRACE =
      "make_interval(mins => " + OperationalStatus.NOT_CHECKED_IN_GRACE.toMinutes() + ")";

  private static final String LEAVE_OVERDUE =
      "make_interval(hours => " + OperationalStatus.LEAVE_OVERDUE_AFTER.toHours() + ")";

  private static final String TIMESHEET_OVERDUE =
      "make_interval(hours => " + OperationalStatus.TIMESHEET_OVERDUE_AFTER.toHours() + ")";

  private static final String AS_OF = "CAST(:asOf AS timestamptz)";

  private final NamedParameterJdbcTemplate jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc named-parameter JDBC
   */
  public JdbcOperationalStatusRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // -------------------------------------------------------------------------------------------
  // Shift occurrences
  // -------------------------------------------------------------------------------------------

  private static String shiftsSql(Scope scope) {
    return "WITH occ AS ("
        + occurrences(
            scope,
            "l.id AS slot_id, l.start_at, a.checked_in_at AS r_in, a.checked_out_at AS r_out")
        + ") SELECT count(*) AS scheduled,"
        + " count(*) FILTER (WHERE r_in IS NULL AND "
        + AS_OF
        + " <= start_at + "
        + GRACE
        + ") AS expected,"
        + " count(*) FILTER (WHERE r_in IS NULL AND "
        + AS_OF
        + " > start_at + "
        + GRACE
        + ") AS not_checked_in,"
        + " count(*) FILTER (WHERE r_in IS NOT NULL AND r_out IS NULL) AS present,"
        + " count(*) FILTER (WHERE r_in IS NOT NULL AND r_out IS NOT NULL) AS completed,"
        + " count(*) FILTER (WHERE r_in > start_at + "
        + TOLERANCE
        + ") AS late FROM occ";
  }

  /**
   * The partition of today's effective assigned shift occurrences and the late subset.
   *
   * @param tenant verified tenant
   * @param scope whose status
   * @param asOf the pinned instant
   * @return the counts
   */
  public Shifts shifts(TenantId tenant, Scope scope, Instant asOf) {
    String sql = shiftsSql(scope);
    return jdbc.queryForObject(
        sql,
        params(tenant, scope, asOf),
        (rs, n) ->
            new Shifts(
                rs.getInt("scheduled"),
                rs.getInt("expected"),
                rs.getInt("not_checked_in"),
                rs.getInt("present"),
                rs.getInt("completed"),
                rs.getInt("late")));
  }

  private static String notCheckedInSql(Scope scope) {
    return "WITH occ AS ("
        + occurrences(
            scope,
            JdbcAttendanceRepository.SHIFT_COLUMNS + ", tg.employee_id, a.checked_in_at AS r_in")
        + ") SELECT occ.*, p.employee_number, p.given_names, p.family_name FROM occ"
        + " JOIN people.employee p ON p.tenant_id = :tenant AND p.id = occ.employee_id"
        + " WHERE occ.r_in IS NULL AND "
        + AS_OF
        + " > occ.start_at + "
        + GRACE
        + " ORDER BY occ.start_at, occ.slot_id, occ.employee_id LIMIT "
        + (OperationalStatus.DETAIL_LIMIT + 1);
  }

  /**
   * The shift occurrences with no check-in after the grace, oldest start first, one row more than
   * the detail limit so truncation is explicit.
   *
   * @param tenant verified tenant
   * @param scope whose status
   * @param asOf the pinned instant
   * @return the rows
   */
  public List<NotCheckedIn> notCheckedIn(TenantId tenant, Scope scope, Instant asOf) {
    String sql = notCheckedInSql(scope);
    return jdbc.query(
        sql,
        params(tenant, scope, asOf),
        (rs, n) -> {
          Shift shift = JdbcAttendanceRepository.shift(rs);
          return new NotCheckedIn(
              shift,
              person(rs, rs.getObject("employee_id", UUID.class)),
              Math.max(0, Duration.between(shift.startAt(), asOf).toMinutes()));
        });
  }

  /**
   * The slots of today (see {@link #TODAY}) of the verified tenant's schedules that are operational
   * at the instant, with the effective assignee of each, in the scope.
   */
  private static String occurrences(Scope scope, String columns) {
    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(columns)
            .append(" FROM ")
            .append(
                switch (scope) {
                  case Organization org ->
                      JdbcAttendanceExceptionRepository.tenantSlots(
                          new AttendanceExceptions.Tenant(org.siteId()));
                  case Team team ->
                      JdbcAttendanceExceptionRepository.candidates(
                          new AttendanceExceptions.Team(team.managerId()));
                })
            .append(SLOT_JOIN)
            .append(EFFECTIVE_ASSIGNEE)
            .append(
                " LEFT JOIN LATERAL (SELECT x.checked_in_at, CASE WHEN x.checked_out_at <= :asOf"
                    + " THEN x.checked_out_at END AS checked_out_at FROM people.attendance_record x"
                    + " WHERE x.tenant_id = l.tenant_id AND x.slot_id = l.id"
                    + " AND x.employee_id = tg.employee_id AND x.checked_in_at <= :asOf LIMIT 1) a"
                    + " ON true")
            .append(
                " WHERE l.tenant_id = :tenant AND l.assigned_employee_id IS NOT NULL"
                    + " AND l.start_at >= :lo AND l.start_at < :hi")
            .append(TODAY)
            .append(NOT_TERMINAL_AT_AS_OF);
    switch (scope) {
      case Organization org -> {
        if (org.legalEntityId() != null) {
          sql.append(" AND").append(placement("tg.employee_id", "l.start_local::date", null));
        }
      }
      case Team team ->
          // The target's qualifying employment names the manager on the slot's first date (the
          // MVP-060C team rule); LIMIT keeps the planner from hashing the employment table.
          sql.append(
                  " AND tg.employee_id <> :manager AND (SELECT true FROM people.employment e"
                      + " WHERE e.tenant_id = l.tenant_id AND e.employee_id = tg.employee_id AND")
              .append(JdbcAttendanceRepository.QUALIFIES)
              .append(" AND")
              .append(JdbcAttendanceRepository.reports("e.id"))
              .append(" LIMIT 1)");
    }
    return sql.toString();
  }

  /**
   * The effective-dated legal-entity placement of an employee on a date ({@code :le}, {@code
   * :tenant}); a site is matched too when the caller's site filter is on the placement ({@code
   * siteParameter}).
   */
  private static String placement(String employee, String date, String siteParameter) {
    return " EXISTS (SELECT 1 FROM people.employment_assignment pa WHERE pa.tenant_id = :tenant"
        + " AND pa.employee_id = "
        + employee
        + " AND pa.kind = 'PLACEMENT' AND pa.superseded_by_change_id IS NULL"
        + " AND pa.legal_entity_id = :le"
        + (siteParameter == null ? "" : " AND pa.site_id = " + siteParameter)
        + " AND pa.effective_from <= "
        + date
        + " AND (pa.effective_to IS NULL OR pa.effective_to >= "
        + date
        + "))";
  }

  // -------------------------------------------------------------------------------------------
  // Uncovered shifts (administrator)
  // -------------------------------------------------------------------------------------------

  private static String uncoveredFrom(Organization org) {
    StringBuilder sql =
        new StringBuilder(" FROM ")
            .append(
                JdbcAttendanceExceptionRepository.tenantSlots(
                    new AttendanceExceptions.Tenant(org.siteId())))
            .append(SLOT_JOIN)
            .append(
                " WHERE l.tenant_id = :tenant AND l.assigned_employee_id IS NULL"
                    + " AND l.start_at >= :lo AND l.start_at < :hi")
            .append(TODAY)
            .append(NOT_TERMINAL_AT_AS_OF);
    if (org.legalEntityId() != null) {
      // No employee, hence no placement: the legal entity of the site that holds the schedule.
      sql.append(org.legalEntitySites().isEmpty() ? " AND FALSE" : " AND s.site_id IN (:leSites)");
    }
    return sql.toString();
  }

  /**
   * How many of today's operational slots have no effective assignee.
   *
   * @param tenant verified tenant
   * @param org the administrator's scope
   * @param asOf the pinned instant
   * @return the count
   */
  public int uncovered(TenantId tenant, Organization org, Instant asOf) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*)" + uncoveredFrom(org), params(tenant, org, asOf), Integer.class);
    return count == null ? 0 : count;
  }

  /**
   * The uncovered slots, oldest start first, one row more than the detail limit.
   *
   * @param tenant verified tenant
   * @param org the administrator's scope
   * @param asOf the pinned instant
   * @return the shifts
   */
  public List<Shift> uncoveredShifts(TenantId tenant, Organization org, Instant asOf) {
    return jdbc.query(
        "SELECT"
            + JdbcAttendanceRepository.SHIFT_COLUMNS
            + uncoveredFrom(org)
            + " ORDER BY l.start_at, l.id LIMIT "
            + (OperationalStatus.DETAIL_LIMIT + 1),
        params(tenant, org, asOf),
        (rs, n) -> JdbcAttendanceRepository.shift(rs));
  }

  // -------------------------------------------------------------------------------------------
  // Leave
  // -------------------------------------------------------------------------------------------

  private static String leaveFrom(Scope scope, boolean names) {
    StringBuilder sql =
        new StringBuilder(
            " FROM people.leave_request r JOIN people.leave_policy_version v"
                + " ON v.tenant_id = r.tenant_id AND v.id = r.policy_version_id"
                + (names
                    ? " JOIN people.employee p ON p.tenant_id = r.tenant_id AND p.id ="
                        + " r.employee_id"
                    : "")
                + " WHERE r.tenant_id = :tenant AND r.state = 'PENDING'");
    switch (scope) {
      case Organization org -> {
        if (org.legalEntityId() != null || org.siteId() != null) {
          sql.append(" AND")
              .append(
                  placementOfEmployment(
                      "r.employment_id",
                      "r.start_date",
                      org.legalEntityId() != null,
                      org.siteId() != null));
        }
      }
      case Team team ->
          sql.append(
              " AND v.approval_route = 'MANAGER' AND r.employee_id <> :manager"
                  + " AND EXISTS (SELECT 1 FROM people.employment_assignment a"
                  + " WHERE a.tenant_id = r.tenant_id AND a.employment_id = r.employment_id"
                  + " AND a.kind = 'MANAGER' AND a.superseded_by_change_id IS NULL"
                  + " AND a.manager_employee_id = :manager AND a.effective_from <= r.start_date"
                  + " AND (a.effective_to IS NULL OR a.effective_to >= r.start_date))");
    }
    return sql.toString();
  }

  private static String placementOfEmployment(
      String employment, String date, boolean legalEntity, boolean site) {
    return " EXISTS (SELECT 1 FROM people.employment_assignment pa WHERE pa.tenant_id = :tenant"
        + " AND pa.employment_id = "
        + employment
        + " AND pa.kind = 'PLACEMENT' AND pa.superseded_by_change_id IS NULL"
        + (legalEntity ? " AND pa.legal_entity_id = :le" : "")
        + (site ? " AND pa.site_id = :site" : "")
        + " AND pa.effective_from <= "
        + date
        + " AND (pa.effective_to IS NULL OR pa.effective_to >= "
        + date
        + "))";
  }

  /**
   * The pending leave requests in the scope and those pending for strictly more than the threshold.
   *
   * @param tenant verified tenant
   * @param scope whose status
   * @param asOf the pinned instant
   * @return the counts
   */
  public Waiting leave(TenantId tenant, Scope scope, Instant asOf) {
    return jdbc.queryForObject(
        leaveCountSql(scope),
        params(tenant, scope, asOf),
        (rs, n) -> new Waiting(rs.getInt("pending"), rs.getInt("overdue")));
  }

  private static String leaveCountSql(Scope scope) {
    return "SELECT count(*) AS pending, count(*) FILTER (WHERE "
        + AS_OF
        + " - r.submitted_at > "
        + LEAVE_OVERDUE
        + ") AS overdue"
        + leaveFrom(scope, false);
  }

  /**
   * A manager's overdue leave requests, oldest first, one row more than the detail limit.
   *
   * @param tenant verified tenant
   * @param team the manager's scope
   * @param asOf the pinned instant
   * @return the rows
   */
  public List<OverdueLeave> overdueLeave(TenantId tenant, Team team, Instant asOf) {
    return jdbc.query(
        "SELECT r.id, r.submitted_at, p.id AS employee_id, p.employee_number, p.given_names,"
            + " p.family_name"
            + leaveFrom(team, true)
            + " AND "
            + AS_OF
            + " - r.submitted_at > "
            + LEAVE_OVERDUE
            + " ORDER BY r.submitted_at, r.id LIMIT "
            + (OperationalStatus.DETAIL_LIMIT + 1),
        params(tenant, team, asOf),
        (rs, n) -> {
          Instant submitted = rs.getTimestamp("submitted_at").toInstant();
          return new OverdueLeave(
              rs.getObject("id", UUID.class),
              person(rs, rs.getObject("employee_id", UUID.class)),
              submitted,
              Duration.between(submitted, asOf).toHours());
        });
  }

  // -------------------------------------------------------------------------------------------
  // Timesheets
  // -------------------------------------------------------------------------------------------

  private static String timesheetFrom(Scope scope, boolean names) {
    String person =
        names
            ? " JOIN people.employee p ON p.tenant_id = t.tenant_id AND p.id = t.employee_id"
            : "";
    return switch (scope) {
      case Organization org -> {
        StringBuilder sql =
            new StringBuilder(
                " FROM people.timesheet t JOIN people.timesheet_action a"
                    + " ON a.tenant_id = t.tenant_id AND a.id = t.current_action_id"
                    + " WHERE t.tenant_id = :tenant AND t.status = 'SUBMITTED'");
        if (org.siteId() != null) {
          sql.append(" AND t.site_id = :site");
        }
        if (org.legalEntityId() != null) {
          sql.append(" AND")
              .append(placementOfEmployment("a.employment_id", "t.week_start + 6", true, false));
        }
        yield sql.toString();
      }
      case Team team ->
          " FROM reports r JOIN people.timesheet t ON t.tenant_id = :tenant"
              + " AND t.employee_id = r.employee_id AND t.status = 'SUBMITTED'"
              + " JOIN people.timesheet_action a ON a.tenant_id = t.tenant_id"
              + " AND a.id = t.current_action_id"
              + person
              + " WHERE t.employee_id <> :manager"
              + TimesheetQueueStatement.ROUTED_TO_MANAGER;
    };
  }

  private static String timesheetPrefix(Scope scope) {
    return scope instanceof Team ? TimesheetQueueStatement.REPORTS_CTE + " " : "";
  }

  /**
   * The submitted timesheets in the scope and those waiting for strictly more than the threshold.
   *
   * @param tenant verified tenant
   * @param scope whose status
   * @param asOf the pinned instant
   * @return the counts
   */
  public Waiting timesheets(TenantId tenant, Scope scope, Instant asOf) {
    return jdbc.queryForObject(
        timesheetCountSql(scope),
        params(tenant, scope, asOf),
        (rs, n) -> new Waiting(rs.getInt("pending"), rs.getInt("overdue")));
  }

  private static String timesheetCountSql(Scope scope) {
    return timesheetPrefix(scope)
        + "SELECT count(*) AS pending, count(*) FILTER (WHERE "
        + AS_OF
        + " - a.occurred_at > "
        + TIMESHEET_OVERDUE
        + ") AS overdue"
        + timesheetFrom(scope, false);
  }

  /**
   * The count and not-checked-in statements the status issues for a scope, with their parameters,
   * so the plan test analyzes exactly what runs.
   *
   * @param tenant verified tenant
   * @param scope whose status
   * @param asOf the pinned instant
   * @return the statements
   */
  public static List<JdbcAttendanceExceptionRepository.Statement> statements(
      TenantId tenant, Scope scope, Instant asOf) {
    MapSqlParameterSource params = params(tenant, scope, asOf);
    List<JdbcAttendanceExceptionRepository.Statement> all = new java.util.ArrayList<>();
    all.add(new JdbcAttendanceExceptionRepository.Statement(shiftsSql(scope), params));
    all.add(new JdbcAttendanceExceptionRepository.Statement(notCheckedInSql(scope), params));
    all.add(new JdbcAttendanceExceptionRepository.Statement(leaveCountSql(scope), params));
    all.add(new JdbcAttendanceExceptionRepository.Statement(timesheetCountSql(scope), params));
    if (scope instanceof Organization org) {
      all.add(
          new JdbcAttendanceExceptionRepository.Statement(
              "SELECT count(*)" + uncoveredFrom(org), params));
    }
    return all;
  }

  /**
   * A manager's overdue timesheets, oldest first, one row more than the detail limit.
   *
   * @param tenant verified tenant
   * @param team the manager's scope
   * @param asOf the pinned instant
   * @return the rows
   */
  public List<OverdueTimesheet> overdueTimesheets(TenantId tenant, Team team, Instant asOf) {
    return jdbc.query(
        timesheetPrefix(team)
            + "SELECT t.id, t.site_id, t.week_start, a.occurred_at AS submitted_at,"
            + " p.id AS employee_id, p.employee_number, p.given_names, p.family_name"
            + timesheetFrom(team, true)
            + " AND "
            + AS_OF
            + " - a.occurred_at > "
            + TIMESHEET_OVERDUE
            + " ORDER BY a.occurred_at, t.id LIMIT "
            + (OperationalStatus.DETAIL_LIMIT + 1),
        params(tenant, team, asOf),
        (rs, n) -> {
          Instant submitted = rs.getTimestamp("submitted_at").toInstant();
          return new OverdueTimesheet(
              rs.getObject("id", UUID.class),
              person(rs, rs.getObject("employee_id", UUID.class)),
              rs.getObject("site_id", UUID.class),
              rs.getObject("week_start", LocalDate.class),
              submitted,
              Duration.between(submitted, asOf).toHours());
        });
  }

  // -------------------------------------------------------------------------------------------
  // Shared
  // -------------------------------------------------------------------------------------------

  private static Person person(ResultSet rs, UUID id) throws SQLException {
    return new Person(
        id,
        rs.getString("employee_number"),
        rs.getString("given_names"),
        rs.getString("family_name"));
  }

  /**
   * The parameters of every statement; unused named parameters are harmless. The instant windows
   * are those of MVP-060C around the single UTC date of the instant: site-local dates lie within a
   * day of it, and a schedule's slots start at most {@code SCHEDULE_SPAN_DAYS} after its first
   * date.
   */
  private static MapSqlParameterSource params(TenantId tenant, Scope scope, Instant asOf) {
    LocalDate today = asOf.atZone(ZoneOffset.UTC).toLocalDate();
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("asOf", Timestamp.from(asOf.truncatedTo(ChronoUnit.MICROS)))
            .addValue(
                "lo", Timestamp.from(today.minusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant()))
            .addValue(
                "hi", Timestamp.from(today.plusDays(3).atStartOfDay(ZoneOffset.UTC).toInstant()))
            .addValue("loDate", today.minusDays(2L + SCHEDULE_SPAN_DAYS))
            .addValue("hiDate", today.plusDays(2));
    switch (scope) {
      case Organization org -> {
        if (org.siteId() != null) {
          params.addValue("site", org.siteId());
        }
        if (org.legalEntityId() != null) {
          params.addValue("le", org.legalEntityId());
          if (!org.legalEntitySites().isEmpty()) {
            params.addValue("leSites", org.legalEntitySites());
          }
        }
      }
      case Team team -> params.addValue("manager", team.managerId());
    }
    return params;
  }
}
