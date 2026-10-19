package com.divalhr.core.payroll.internal;

import com.divalhr.core.payroll.domain.PayrollInputs;
import com.divalhr.core.payroll.domain.PayrollInputs.EmployeeInput;
import com.divalhr.core.payroll.domain.PayrollInputs.Evaluation;
import com.divalhr.core.payroll.domain.PayrollInputs.Exclusion;
import com.divalhr.core.payroll.domain.PayrollInputs.Run;
import com.divalhr.core.payroll.domain.PayrollInputs.Scope;
import com.divalhr.core.payroll.domain.PayrollInputs.SourceRow;
import com.divalhr.core.payroll.domain.PayrollInputs.SourceWeek;
import com.divalhr.core.payroll.domain.PayrollInputs.Totals;
import com.divalhr.core.payroll.domain.PayrollVariance;
import com.divalhr.core.platform.tenancy.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

/**
 * Storage of payroll-input runs (MVP-090A; V37): the run, its source weeks, employee inputs and
 * source rows. Every write is an insert; the schema refuses everything else. Only the payroll
 * module reads these tables.
 */
@Repository
public class JdbcPayrollInputRepository {

  private static final String RUN_COLUMNS =
      "r.id, r.legal_entity_id, r.version, r.period_start, r.period_end, r.business_timezone,"
          + " r.week_count, r.employee_count, r.timesheet_count, r.row_count, r.worked_minutes,"
          + " r.excluded_rows, r.excluded_timesheets, r.evidence_digest, r.generated_at";

  private final NamedParameterJdbcTemplate jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc named-parameter template
   */
  public JdbcPayrollInputRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * The run of a legal entity and period at version 1, if any.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param periodStart first date
   * @param periodEnd last date
   * @return the run
   */
  public Optional<Run> byPeriod(
      TenantId tenant, UUID legalEntityId, LocalDate periodStart, LocalDate periodEnd) {
    return jdbc
        .query(
            "SELECT "
                + RUN_COLUMNS
                + " FROM payroll.input_run r WHERE r.tenant_id = :tenant AND r.legal_entity_id ="
                + " :legalEntity AND r.period_start = :start AND r.period_end = :end AND r.version"
                + " = 1",
            new MapSqlParameterSource()
                .addValue("tenant", tenant.value())
                .addValue("legalEntity", legalEntityId)
                .addValue("start", periodStart)
                .addValue("end", periodEnd),
            (rs, n) -> run(rs))
        .stream()
        .findFirst();
  }

  /**
   * One run.
   *
   * @param tenant verified tenant
   * @param id run
   * @return the run, or empty for an unknown or foreign one
   */
  public Optional<Run> run(TenantId tenant, UUID id) {
    return jdbc
        .query(
            "SELECT "
                + RUN_COLUMNS
                + " FROM payroll.input_run r WHERE r.tenant_id = :tenant AND r.id = :id",
            new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("id", id),
            (rs, n) -> run(rs))
        .stream()
        .findFirst();
  }

  /**
   * One page of the tenant's runs, newest first, optionally of one legal entity.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity filter, or {@code null}
   * @param afterGeneratedAt keyset position, or {@code null}
   * @param afterId keyset position, or {@code null}
   * @param limit rows to read
   * @return the page
   */
  public List<Run> page(
      TenantId tenant, UUID legalEntityId, Instant afterGeneratedAt, UUID afterId, int limit) {
    MapSqlParameterSource params =
        new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("limit", limit);
    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(RUN_COLUMNS)
            .append(" FROM payroll.input_run r WHERE r.tenant_id = :tenant");
    if (legalEntityId != null) {
      sql.append(" AND r.legal_entity_id = :legalEntity");
      params.addValue("legalEntity", legalEntityId);
    }
    if (afterGeneratedAt != null) {
      sql.append(" AND (r.generated_at, r.id) < (:afterAt, :afterId)");
      params.addValue("afterAt", Timestamp.from(afterGeneratedAt)).addValue("afterId", afterId);
    }
    sql.append(" ORDER BY r.generated_at DESC, r.id DESC LIMIT :limit");
    return jdbc.query(sql.toString(), params, (rs, n) -> run(rs));
  }

  /**
   * The runs of the same tenant and legal entity that end before the current run begins and cover
   * the same inclusive number of days, newest period first (MVP-091). The comparison is eligible
   * only through {@link PayrollVariance#eligible}; this query only narrows it.
   *
   * @param tenant verified tenant
   * @param current the current run
   * @param limit rows to read
   * @return the candidate runs
   */
  public List<Run> comparable(TenantId tenant, Run current, int limit) {
    return jdbc.query(
        "SELECT "
            + RUN_COLUMNS
            + " FROM payroll.input_run r WHERE r.tenant_id = :tenant AND r.legal_entity_id ="
            + " :legalEntity AND r.id <> :id AND r.period_end < :start AND (r.period_end -"
            + " r.period_start) = :span ORDER BY r.period_end DESC, r.generated_at DESC, r.id DESC"
            + " LIMIT :limit",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("legalEntity", current.scope().legalEntityId())
            .addValue("id", current.id())
            .addValue("start", current.scope().periodStart())
            .addValue("span", (int) (PayrollVariance.days(current) - 1))
            .addValue("limit", limit),
        (rs, n) -> run(rs));
  }

  /**
   * The source weeks of a run, by site and week.
   *
   * @param tenant verified tenant
   * @param runId run
   * @return the weeks
   */
  public List<SourceWeek> weeks(TenantId tenant, UUID runId) {
    return jdbc.query(
        "SELECT site_id, week_start, site_timezone, generation_id, generation_version,"
            + " lock_action_id, lock_version, lock_evidence_digest, timesheet_count, row_count,"
            + " worked_minutes, (SELECT count(DISTINCT (x.employee_id, x.employment_id)) FROM"
            + " payroll.input_source_row x WHERE x.tenant_id = k.tenant_id AND x.run_id = k.run_id"
            + " AND x.generation_id = k.generation_id) AS employee_count"
            + " FROM payroll.input_source_week k WHERE k.tenant_id = :tenant AND k.run_id = :run"
            + " ORDER BY k.site_id::text COLLATE \"C\", k.week_start",
        new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("run", runId),
        (rs, n) ->
            new SourceWeek(
                rs.getObject("site_id", UUID.class),
                rs.getObject("week_start", LocalDate.class),
                rs.getString("site_timezone"),
                rs.getObject("generation_id", UUID.class),
                rs.getInt("generation_version"),
                rs.getObject("lock_action_id", UUID.class),
                rs.getInt("lock_version"),
                rs.getString("lock_evidence_digest"),
                new Totals(
                    rs.getInt("timesheet_count"),
                    rs.getInt("employee_count"),
                    rs.getInt("row_count"),
                    rs.getInt("worked_minutes"))));
  }

  /**
   * The employee inputs of a run, by employee and employment.
   *
   * @param tenant verified tenant
   * @param runId run
   * @return the inputs
   */
  public List<EmployeeInput> employees(TenantId tenant, UUID runId) {
    return jdbc.query(
        "SELECT employee_id, employment_id, first_work_date, last_work_date, timesheet_count,"
            + " row_count, worked_minutes FROM payroll.input_employee WHERE tenant_id = :tenant"
            + " AND run_id = :run ORDER BY employee_id::text COLLATE \"C\", employment_id::text"
            + " COLLATE \"C\"",
        new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("run", runId),
        (rs, n) ->
            new EmployeeInput(
                rs.getObject("employee_id", UUID.class),
                rs.getObject("employment_id", UUID.class),
                rs.getObject("first_work_date", LocalDate.class),
                rs.getObject("last_work_date", LocalDate.class),
                new Totals(
                    rs.getInt("timesheet_count"),
                    1,
                    rs.getInt("row_count"),
                    rs.getInt("worked_minutes"))));
  }

  /**
   * Records a run with its source weeks, employee inputs and source rows (the deferred exactness
   * check proves the counts at commit, or earlier through {@link #checkConsistent}).
   *
   * @param tenant verified tenant
   * @param run the run
   * @param evaluation the evaluated evidence
   * @param generatedBy verified subject
   */
  public void insert(TenantId tenant, Run run, Evaluation evaluation, String generatedBy) {
    Scope scope = run.scope();
    Totals totals = run.totals();
    jdbc.update(
        "INSERT INTO payroll.input_run (tenant_id, id, legal_entity_id, version, status,"
            + " schema_version, period_start, period_end, business_timezone, week_count,"
            + " employee_count, timesheet_count, row_count, worked_minutes, excluded_rows,"
            + " excluded_timesheets, evidence_digest, generated_at, generated_by) VALUES (:tenant,"
            + " :id, :legalEntity, :version, 'GENERATED', :schema, :start, :end, :zone, :weeks,"
            + " :employees, :timesheets, :rows, :minutes, :excludedRows, :excludedTimesheets,"
            + " :evidence, :at, :by)",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("id", run.id())
            .addValue("legalEntity", scope.legalEntityId())
            .addValue("version", run.version())
            .addValue("schema", PayrollInputs.SCHEMA_VERSION)
            .addValue("start", scope.periodStart())
            .addValue("end", scope.periodEnd())
            .addValue("zone", scope.businessTimezone())
            .addValue("weeks", run.weekCount())
            .addValue("employees", totals.employees())
            .addValue("timesheets", totals.timesheets())
            .addValue("rows", totals.rows())
            .addValue("minutes", totals.workedMinutes())
            .addValue(
                "excludedRows", run.exclusions().getOrDefault(Exclusion.ROW_OUTSIDE_PERIOD, 0))
            .addValue(
                "excludedTimesheets",
                run.exclusions().getOrDefault(Exclusion.TIMESHEET_WITHOUT_TIME, 0))
            .addValue("evidence", run.evidenceDigest())
            .addValue("at", Timestamp.from(run.generatedAt()))
            .addValue("by", generatedBy));
    List<SqlParameterSource> weeks = new ArrayList<>();
    for (SourceWeek week : evaluation.sourceWeeks()) {
      weeks.add(
          new MapSqlParameterSource()
              .addValue("tenant", tenant.value())
              .addValue("run", run.id())
              .addValue("generation", week.generationId())
              .addValue("site", week.siteId())
              .addValue("week", week.weekStart())
              .addValue("zone", week.timezone())
              .addValue("generationVersion", week.generationVersion())
              .addValue("lockAction", week.lockActionId())
              .addValue("lockVersion", week.lockVersion())
              .addValue("lockEvidence", week.lockEvidenceDigest())
              .addValue("timesheets", week.totals().timesheets())
              .addValue("rows", week.totals().rows())
              .addValue("minutes", week.totals().workedMinutes()));
    }
    jdbc.batchUpdate(
        "INSERT INTO payroll.input_source_week (tenant_id, run_id, generation_id, site_id,"
            + " week_start, site_timezone, generation_version, lock_action_id, lock_version,"
            + " lock_evidence_digest, timesheet_count, row_count, worked_minutes) VALUES (:tenant,"
            + " :run, :generation, :site, :week, :zone, :generationVersion, :lockAction,"
            + " :lockVersion, :lockEvidence, :timesheets, :rows, :minutes)",
        weeks.toArray(SqlParameterSource[]::new));
    List<SqlParameterSource> employees = new ArrayList<>();
    for (EmployeeInput input : evaluation.employees()) {
      employees.add(
          new MapSqlParameterSource()
              .addValue("tenant", tenant.value())
              .addValue("run", run.id())
              .addValue("employee", input.employeeId())
              .addValue("employment", input.employmentId())
              .addValue("first", input.firstWorkDate())
              .addValue("last", input.lastWorkDate())
              .addValue("timesheets", input.totals().timesheets())
              .addValue("rows", input.totals().rows())
              .addValue("minutes", input.totals().workedMinutes()));
    }
    jdbc.batchUpdate(
        "INSERT INTO payroll.input_employee (tenant_id, run_id, employee_id, employment_id,"
            + " first_work_date, last_work_date, timesheet_count, row_count, worked_minutes) VALUES"
            + " (:tenant, :run, :employee, :employment, :first, :last, :timesheets, :rows,"
            + " :minutes)",
        employees.toArray(SqlParameterSource[]::new));
    List<SqlParameterSource> rows = new ArrayList<>();
    int line = 0;
    for (SourceRow row : evaluation.rows()) {
      line++;
      rows.add(
          new MapSqlParameterSource()
              .addValue("tenant", tenant.value())
              .addValue("run", run.id())
              .addValue("line", line)
              .addValue("employee", row.employeeId())
              .addValue("employment", row.employmentId())
              .addValue("generation", row.generationId())
              .addValue("site", row.siteId())
              .addValue("costCenter", row.costCenterId())
              .addValue("date", row.workDate())
              .addValue("shift", row.shiftId())
              .addValue("timesheet", row.timesheetId())
              .addValue("revision", row.timesheetRevision())
              .addValue("action", row.approvalActionId())
              .addValue("cycle", row.approvalCycle())
              .addValue("lockAction", row.lockActionId())
              .addValue("minutes", row.workedMinutes()));
    }
    jdbc.batchUpdate(
        "INSERT INTO payroll.input_source_row (tenant_id, run_id, line, employee_id,"
            + " employment_id, generation_id, site_id, cost_center_id, work_date, shift_id,"
            + " timesheet_id, timesheet_revision, approval_action_id, approval_cycle,"
            + " lock_action_id, worked_minutes) VALUES (:tenant, :run, :line, :employee,"
            + " :employment, :generation, :site, CAST(:costCenter AS uuid), :date, :shift,"
            + " :timesheet, :revision, :action, :cycle, :lockAction, :minutes)",
        rows.toArray(SqlParameterSource[]::new));
  }

  /**
   * Runs the deferred exactness check now (inside the caller's transaction), so a violation is
   * mapped like any constraint instead of failing at commit.
   */
  public void checkConsistent() {
    jdbc.getJdbcTemplate().execute("SET CONSTRAINTS payroll.payroll_input_run_exact IMMEDIATE");
  }

  private static Run run(ResultSet rs) throws SQLException {
    Map<Exclusion, Integer> exclusions = new TreeMap<>();
    exclusions.put(Exclusion.ROW_OUTSIDE_PERIOD, rs.getInt("excluded_rows"));
    exclusions.put(Exclusion.TIMESHEET_WITHOUT_TIME, rs.getInt("excluded_timesheets"));
    return new Run(
        rs.getObject("id", UUID.class),
        new Scope(
            rs.getObject("legal_entity_id", UUID.class),
            rs.getObject("period_start", LocalDate.class),
            rs.getObject("period_end", LocalDate.class),
            rs.getString("business_timezone")),
        rs.getInt("version"),
        rs.getInt("week_count"),
        new Totals(
            rs.getInt("timesheet_count"),
            rs.getInt("employee_count"),
            rs.getInt("row_count"),
            rs.getInt("worked_minutes")),
        exclusions,
        rs.getString("evidence_digest"),
        rs.getTimestamp("generated_at").toInstant());
  }
}
