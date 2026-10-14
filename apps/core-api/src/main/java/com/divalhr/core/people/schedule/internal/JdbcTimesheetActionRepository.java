package com.divalhr.core.people.schedule.internal;

import com.divalhr.core.people.schedule.domain.Substitution.Person;
import com.divalhr.core.people.schedule.domain.Timesheet.Period;
import com.divalhr.core.people.schedule.domain.Timesheet.Totals;
import com.divalhr.core.people.schedule.domain.TimesheetApproval.Action;
import com.divalhr.core.people.schedule.domain.TimesheetApproval.Kind;
import com.divalhr.core.people.schedule.domain.TimesheetApproval.Status;
import com.divalhr.core.people.schedule.internal.JdbcTimesheetRepository.Header;
import com.divalhr.core.platform.tenancy.TenantId;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * MVP-070B/C (V33) and MVP-070D (V34): a timesheet's append-only submission, decision and reopening
 * actions, the approved timesheets a correction may reopen, its approval route and the routed
 * manager's queue. Every query names the verified tenant and is driven by a key: a timesheet's
 * history by {@code timesheet_action_sequence_unique}, an action by its primary key, the queue by
 * the manager's reporting lines ({@code employment_assignment_reports}) and then {@code
 * timesheet_submitted}. Runs in the caller's transaction; every write is made under the tenant's
 * manager-graph and scheduling locks with the timesheet locked {@code FOR UPDATE}.
 */
@Repository
public class JdbcTimesheetActionRepository {

  private static final String ACTION_COLUMNS =
      "a.id, a.timesheet_id, a.sequence, a.action, a.cycle, a.submission_action_id, a.revision,"
          + " a.generation_version, a.source_digest, a.row_count, a.ready_rows, a.missing_rows,"
          + " a.open_rows, a.changed_rows, a.worked_minutes, a.employee_id, a.employment_id,"
          + " a.manager_employee_id, a.return_reason, a.occurred_at, a.superseded_action_id,"
          + " a.after_source_digest, a.correction_id";

  private final NamedParameterJdbcTemplate jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc named-parameter JDBC
   */
  public JdbcTimesheetActionRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * The facts the approval route is resolved from (T70BC-3).
   *
   * @param employed whether the bound employment is effective on the week's Sunday
   * @param managers the distinct managers effective on the Sunday over the employee's employments
   *     effective then (at most two read: one is the only valid count)
   */
  public record RouteFacts(boolean employed, List<UUID> managers) {

    /** Copies the managers. */
    public RouteFacts {
      managers = List.copyOf(managers);
    }

    @Override
    public String toString() {
      return "RouteFacts[employed=" + employed + ", managers=" + managers.size() + "]";
    }
  }

  /**
   * A submitted timesheet in a manager's queue.
   *
   * @param header the timesheet
   * @param employee the employee's number and current name
   * @param submissionActionId the current submission
   * @param submittedAt its instant
   */
  public record QueueItem(
      Header header, Person employee, UUID submissionActionId, Instant submittedAt) {}

  // ------------------------------------------------------------------------------------------
  // Actions
  // ------------------------------------------------------------------------------------------

  /**
   * A timesheet's actions, newest first.
   *
   * @param tenant verified tenant
   * @param timesheetId timesheet
   * @return the actions
   */
  public List<Action> actions(TenantId tenant, UUID timesheetId) {
    return jdbc.query(
        "SELECT "
            + ACTION_COLUMNS
            + " FROM people.timesheet_action a WHERE a.tenant_id = :tenant"
            + " AND a.timesheet_id = :timesheet ORDER BY a.sequence DESC",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("timesheet", timesheetId),
        (rs, n) -> action(rs));
  }

  /**
   * An action by ID.
   *
   * @param tenant verified tenant
   * @param id action
   * @return the action
   */
  public Optional<Action> action(TenantId tenant, UUID id) {
    return jdbc
        .query(
            "SELECT "
                + ACTION_COLUMNS
                + " FROM people.timesheet_action a WHERE a.tenant_id = :tenant AND a.id = :id",
            new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("id", id),
            (rs, n) -> action(rs))
        .stream()
        .findFirst();
  }

  /**
   * Appends an action (the V33 transition trigger requires it to follow the latest one from the
   * timesheet's current state; the timesheet's move follows in the same transaction).
   *
   * @param tenant verified tenant
   * @param action the action
   * @param siteId the timesheet's site
   * @param subject verified subject of the actor
   */
  public void insert(TenantId tenant, Action action, UUID siteId, String subject) {
    Totals totals = action.totals();
    jdbc.update(
        "INSERT INTO people.timesheet_action (tenant_id, id, timesheet_id, sequence, action, cycle,"
            + " submission_action_id, revision, generation_version, source_digest, row_count,"
            + " ready_rows, missing_rows, open_rows, changed_rows, worked_minutes, employee_id,"
            + " site_id, employment_id, manager_employee_id, actor_subject, return_reason,"
            + " occurred_at, superseded_action_id, after_source_digest, correction_id) VALUES"
            + " (:tenant, :id, :timesheet, :sequence, :action, :cycle,"
            + " CAST(:submission AS uuid), :revision, :version, :digest, :rows, :ready, :missing,"
            + " :open, :changed, :worked, :employee, :site, :employment, :manager, :subject,"
            + " CAST(:reason AS text), :at, CAST(:superseded AS uuid), CAST(:after AS text),"
            + " CAST(:correction AS uuid))",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("id", action.id())
            .addValue("timesheet", action.timesheetId())
            .addValue("sequence", action.sequence())
            .addValue("action", action.kind().name())
            .addValue("cycle", action.cycle())
            .addValue("submission", action.submissionActionId())
            .addValue("revision", action.revision())
            .addValue("version", action.generationVersion())
            .addValue("digest", action.sourceDigest())
            .addValue("rows", totals.rows())
            .addValue("ready", totals.ready())
            .addValue("missing", totals.missing())
            .addValue("open", totals.open())
            .addValue("changed", totals.changed())
            .addValue("worked", totals.workedMinutes())
            .addValue("employee", action.employeeId())
            .addValue("site", siteId)
            .addValue("employment", action.employmentId())
            .addValue("manager", action.managerEmployeeId())
            .addValue("subject", subject)
            .addValue("reason", action.returnReason())
            .addValue("at", Timestamp.from(action.occurredAt()))
            .addValue("superseded", action.supersededActionId())
            .addValue("after", action.afterSourceDigest())
            .addValue("correction", action.correctionId()));
  }

  /**
   * Moves the timesheet to an action's state (the V33 guard refuses anything but the matching
   * latest action).
   *
   * @param tenant verified tenant
   * @param action the new latest action
   * @param from the state it was reviewed in
   * @return rows updated (1, or 0 when the state moved)
   */
  public int moveTo(TenantId tenant, Action action, Status from) {
    return jdbc.update(
        "UPDATE people.timesheet SET status = :status, submission_cycle = :cycle,"
            + " current_action_id = :action WHERE tenant_id = :tenant AND id = :id"
            + " AND status = :from",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("id", action.timesheetId())
            .addValue("status", action.kind().state().name())
            .addValue("cycle", action.cycle())
            .addValue("action", action.id())
            .addValue("from", from.name()));
  }

  /**
   * The APPROVED timesheets of an employee whose week contains an instant (a correction's shift
   * start), in ID order, optionally locked {@code FOR UPDATE} (MVP-070D, T70D-2). Driven by the
   * employee's timesheets ({@code timesheet_employee_week}), never a scan of the tenant.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param at the instant
   * @param lock whether to lock them
   * @return the headers
   */
  public List<Header> approvedCovering(TenantId tenant, UUID employeeId, Instant at, boolean lock) {
    return jdbc.query(
        "SELECT t.id, t.generation_id, t.employee_id, t.site_id, t.week_start, t.status,"
            + " t.submission_cycle, t.current_action_id, t.current_revision, t.row_count,"
            + " t.ready_rows, t.missing_rows, t.open_rows, t.changed_rows, t.worked_minutes,"
            + " t.created_at, t.updated_at, g.site_timezone, g.period_start_at, g.period_end_at"
            + " FROM people.timesheet t JOIN people.timesheet_generation g"
            + " ON g.tenant_id = t.tenant_id AND g.id = t.generation_id"
            + " WHERE t.tenant_id = :tenant AND t.employee_id = :employee"
            + " AND t.status = 'APPROVED' AND g.period_start_at <= :at AND g.period_end_at > :at"
            + " ORDER BY t.id"
            + (lock ? " FOR UPDATE OF t" : ""),
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("employee", employeeId)
            .addValue("at", Timestamp.from(at)),
        (rs, n) -> header(rs));
  }

  // ------------------------------------------------------------------------------------------
  // The route
  // ------------------------------------------------------------------------------------------

  /**
   * The distinct qualifying employments of a revision's rows.
   *
   * @param tenant verified tenant
   * @param timesheetId timesheet
   * @param revision revision
   * @return the employments (at most two read)
   */
  public List<UUID> employments(TenantId tenant, UUID timesheetId, int revision) {
    return jdbc.query(
        "SELECT DISTINCT r.employment_id FROM people.timesheet_row r WHERE r.tenant_id = :tenant"
            + " AND r.timesheet_id = :timesheet AND r.revision = :revision"
            + " ORDER BY r.employment_id LIMIT 2",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("timesheet", timesheetId)
            .addValue("revision", revision),
        (rs, n) -> rs.getObject("employment_id", UUID.class));
  }

  /**
   * The route facts on a date under the current employment history: whether the bound employment of
   * the employee is effective, and the distinct managers of the employee's employments effective
   * then.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param employmentId the bound employment
   * @param date the site-local Sunday
   * @return the facts
   */
  public RouteFacts route(TenantId tenant, UUID employeeId, UUID employmentId, LocalDate date) {
    return jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM people.employment e WHERE e.tenant_id = :tenant"
            + " AND e.id = :employment AND e.employee_id = :employee AND e.effective_from <= :day"
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
            .addValue("employment", employmentId)
            .addValue("day", date),
        (rs, n) -> new RouteFacts(rs.getBoolean("employed"), uuids(rs.getArray("managers"))));
  }

  // ------------------------------------------------------------------------------------------
  // The manager's queue
  // ------------------------------------------------------------------------------------------

  /**
   * One page of the currently submitted timesheets whose route resolves now to the manager: the
   * submission's employment is effective on the week's site-local Sunday, the employee's
   * employments effective then have exactly one manager and it is this one (never the employee);
   * newest submission first, after a keyset position. Driven by the manager's reporting lines, then
   * each report's submitted timesheets: never a scan of the tenant's timesheets.
   *
   * @param tenant verified tenant
   * @param managerId the caller's linked employee
   * @param siteId site filter, or {@code null}
   * @param weekStart week filter, or {@code null}
   * @param afterAt the previous page's last submission instant, or {@code null}
   * @param afterId the previous page's last submission, or {@code null}
   * @param limit rows to read
   * @return the items
   */
  public List<QueueItem> queue(
      TenantId tenant,
      UUID managerId,
      UUID siteId,
      LocalDate weekStart,
      Instant afterAt,
      UUID afterId,
      int limit) {
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("manager", managerId)
            .addValue("limit", limit);
    if (siteId != null) {
      params.addValue("site", siteId);
    }
    if (weekStart != null) {
      params.addValue("week", weekStart);
    }
    if (afterAt != null) {
      params.addValue("afterAt", Timestamp.from(afterAt)).addValue("afterId", afterId);
    }
    return jdbc.query(
        TimesheetQueueStatement.sql(siteId != null, weekStart != null, afterAt != null),
        params,
        (rs, n) ->
            new QueueItem(
                header(rs),
                new Person(
                    rs.getObject("employee_id", UUID.class),
                    rs.getString("employee_number"),
                    rs.getString("given_names"),
                    rs.getString("family_name")),
                rs.getObject("action_id", UUID.class),
                instant(rs, "submitted_at")));
  }

  // ------------------------------------------------------------------------------------------
  // Mapping
  // ------------------------------------------------------------------------------------------

  private static Action action(ResultSet rs) throws SQLException {
    return new Action(
        rs.getObject("id", UUID.class),
        rs.getObject("timesheet_id", UUID.class),
        rs.getInt("sequence"),
        Kind.valueOf(rs.getString("action")),
        rs.getInt("cycle"),
        rs.getObject("submission_action_id", UUID.class),
        rs.getInt("revision"),
        rs.getInt("generation_version"),
        rs.getString("source_digest"),
        totals(rs),
        rs.getObject("employee_id", UUID.class),
        rs.getObject("employment_id", UUID.class),
        rs.getObject("manager_employee_id", UUID.class),
        rs.getString("return_reason"),
        instant(rs, "occurred_at"),
        rs.getObject("superseded_action_id", UUID.class),
        rs.getString("after_source_digest"),
        rs.getObject("correction_id", UUID.class));
  }

  private static Header header(ResultSet rs) throws SQLException {
    return new Header(
        rs.getObject("id", UUID.class),
        rs.getObject("generation_id", UUID.class),
        rs.getObject("employee_id", UUID.class),
        new Period(
            rs.getObject("site_id", UUID.class),
            rs.getString("site_timezone"),
            rs.getObject("week_start", LocalDate.class),
            instant(rs, "period_start_at"),
            instant(rs, "period_end_at")),
        rs.getString("status"),
        rs.getInt("submission_cycle"),
        rs.getObject("current_action_id", UUID.class),
        rs.getInt("current_revision"),
        totals(rs),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  private static Totals totals(ResultSet rs) throws SQLException {
    return new Totals(
        rs.getInt("row_count"),
        rs.getInt("ready_rows"),
        rs.getInt("missing_rows"),
        rs.getInt("open_rows"),
        rs.getInt("changed_rows"),
        rs.getInt("worked_minutes"));
  }

  private static List<UUID> uuids(Array array) throws SQLException {
    List<UUID> values = new ArrayList<>();
    if (array != null) {
      for (Object value : (Object[]) array.getArray()) {
        values.add((UUID) value);
      }
    }
    return values;
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
