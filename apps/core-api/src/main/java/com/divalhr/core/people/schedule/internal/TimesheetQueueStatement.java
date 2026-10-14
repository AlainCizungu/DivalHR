package com.divalhr.core.people.schedule.internal;

/**
 * MVP-070B/C: the routed manager's queue statement of {@link JdbcTimesheetActionRepository#queue},
 * kept apart so the query-plan test explains exactly the statement the repository runs. The route
 * predicate is the one of {@code TimesheetRouting}, in SQL: the submission's employment is
 * effective on the week's site-local Sunday, and the employee's employments effective then have
 * exactly one manager, the caller (never the employee).
 */
public final class TimesheetQueueStatement {

  /**
   * Employment {@code e} of the timesheet {@code t}'s employee is effective on the week's
   * site-local Sunday.
   */
  private static final String EMPLOYED_ON_SUNDAY =
      " e.tenant_id = t.tenant_id AND e.employee_id = t.employee_id"
          + " AND e.effective_from <= t.week_start + 6"
          + " AND (e.effective_to IS NULL OR e.effective_to >= t.week_start + 6)";

  /** An active MANAGER line {@code ma} of employment {@code e} on the week's Sunday. */
  private static final String MANAGES_ON_SUNDAY =
      " ma.tenant_id = e.tenant_id AND ma.employment_id = e.id AND ma.kind = 'MANAGER'"
          + " AND ma.superseded_by_change_id IS NULL AND ma.manager_employee_id IS NOT NULL"
          + " AND ma.effective_from <= t.week_start + 6"
          + " AND (ma.effective_to IS NULL OR ma.effective_to >= t.week_start + 6)";

  private TimesheetQueueStatement() {}

  /**
   * The queue's statement (named parameters {@code :tenant}, {@code :manager}, {@code :limit} and,
   * when filtered or paged, {@code :site}, {@code :week}, {@code :afterAt} and {@code :afterId});
   * shared with the query-plan test.
   *
   * @param site whether the site filter applies
   * @param week whether the week filter applies
   * @param after whether a keyset position applies
   * @return the SQL
   */
  public static String sql(boolean site, boolean week, boolean after) {
    StringBuilder filters = new StringBuilder();
    if (site) {
      filters.append(" AND t.site_id = :site");
    }
    if (week) {
      filters.append(" AND t.week_start = :week");
    }
    if (after) {
      filters.append(" AND (a.occurred_at, a.id) < (:afterAt, :afterId)");
    }
    return "WITH reports AS MATERIALIZED (SELECT DISTINCT ma.employee_id"
        + " FROM people.employment_assignment ma WHERE ma.tenant_id = :tenant"
        + " AND ma.kind = 'MANAGER' AND ma.superseded_by_change_id IS NULL"
        + " AND ma.manager_employee_id = :manager)"
        + " SELECT t.id, t.generation_id, t.employee_id, t.site_id, t.week_start, t.status,"
        + " t.submission_cycle, t.current_action_id, t.current_revision, t.row_count,"
        + " t.ready_rows, t.missing_rows, t.open_rows, t.changed_rows, t.worked_minutes,"
        + " t.created_at, t.updated_at, g.site_timezone, g.period_start_at, g.period_end_at,"
        + " p.employee_number, p.given_names, p.family_name, a.id AS action_id,"
        + " a.occurred_at AS submitted_at"
        + " FROM reports r JOIN people.timesheet t ON t.tenant_id = :tenant"
        + " AND t.employee_id = r.employee_id AND t.status = 'SUBMITTED'"
        + " JOIN people.timesheet_generation g ON g.tenant_id = t.tenant_id"
        + " AND g.id = t.generation_id"
        + " JOIN people.timesheet_action a ON a.tenant_id = t.tenant_id"
        + " AND a.id = t.current_action_id"
        + " JOIN people.employee p ON p.tenant_id = t.tenant_id AND p.id = t.employee_id"
        + " WHERE t.employee_id <> :manager"
        + filters
        + " AND EXISTS (SELECT 1 FROM people.employment e WHERE e.id = a.employment_id AND"
        + EMPLOYED_ON_SUNDAY
        + ") AND EXISTS (SELECT 1 FROM people.employment e"
        + " JOIN people.employment_assignment ma ON"
        + MANAGES_ON_SUNDAY
        + " WHERE"
        + EMPLOYED_ON_SUNDAY
        + " AND ma.manager_employee_id = :manager)"
        + " AND NOT EXISTS (SELECT 1 FROM people.employment e"
        + " JOIN people.employment_assignment ma ON"
        + MANAGES_ON_SUNDAY
        + " WHERE"
        + EMPLOYED_ON_SUNDAY
        + " AND ma.manager_employee_id <> :manager)"
        + " ORDER BY a.occurred_at DESC, a.id DESC LIMIT :limit";
  }
}
