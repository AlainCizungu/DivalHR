package com.divalhr.core.people.schedule.internal;

import com.divalhr.core.people.schedule.domain.Timesheet.Period;
import com.divalhr.core.people.schedule.domain.TimesheetExport;
import com.divalhr.core.people.schedule.domain.TimesheetExport.Header;
import com.divalhr.core.people.schedule.domain.TimesheetExport.Line;
import com.divalhr.core.people.schedule.domain.TimesheetExport.Scope;
import com.divalhr.core.people.schedule.domain.TimesheetExport.Totals;
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
 * MVP-070E (V35): approved-time exports. The eligibility query reads, for one generation, every
 * timesheet whose current state is APPROVED and whose current action is that APPROVED action at the
 * current revision and cycle, with the READY rows of that revision and the employee number; it is
 * driven by {@code timesheet_generation_member}, the action primary key and the row primary key.
 * Exports are listed by {@code timesheet_export_history} (or {@code timesheet_export_site_week}
 * with a site filter) and found again by {@code timesheet_export_evidence_unique}. Every query
 * names the verified tenant. Runs in the caller's transaction; the caller holds the tenant's
 * scheduling lock for every write.
 */
@Repository
public class JdbcTimesheetExportRepository {

  private static final String EXPORT_COLUMNS =
      "e.id, e.legal_entity_id, e.site_id, e.generation_id, e.generation_version, e.week_start,"
          + " e.site_timezone, e.period_start_at, e.period_end_at, e.timesheet_count,"
          + " e.employee_count, e.row_count, e.worked_minutes, e.evidence_digest, e.csv_sha256,"
          + " e.created_at";

  private final NamedParameterJdbcTemplate jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc named-parameter JDBC
   */
  public JdbcTimesheetExportRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * The eligible lines of a generation (T70E-1): READY rows of the current revision of every
   * timesheet that is APPROVED now by its current action, at that action's revision and cycle; the
   * work date is the shift's start date at the site. In no particular order.
   *
   * @param tenant verified tenant
   * @param generationId generation
   * @return the lines
   */
  public List<Line> eligible(TenantId tenant, UUID generationId) {
    return jdbc.query(
        "SELECT t.id AS timesheet_id, t.current_revision, a.id AS action_id, a.cycle,"
            + " t.employee_id, p.employee_number, r.employment_id, r.cost_center_id,"
            + " (r.scheduled_start_at AT TIME ZONE g.site_timezone)::date AS work_date,"
            + " r.slot_id, r.worked_minutes"
            + " FROM people.timesheet t"
            + " JOIN people.timesheet_generation g"
            + " ON g.tenant_id = t.tenant_id AND g.id = t.generation_id"
            + " JOIN people.timesheet_action a"
            + " ON a.tenant_id = t.tenant_id AND a.id = t.current_action_id"
            + " AND a.timesheet_id = t.id AND a.action = 'APPROVED'"
            + " AND a.revision = t.current_revision AND a.cycle = t.submission_cycle"
            + " JOIN people.timesheet_row r"
            + " ON r.tenant_id = t.tenant_id AND r.timesheet_id = t.id"
            + " AND r.revision = t.current_revision AND r.state = 'READY'"
            + " JOIN people.employee p ON p.tenant_id = t.tenant_id AND p.id = t.employee_id"
            + " WHERE t.tenant_id = :tenant AND t.generation_id = :generation"
            + " AND t.status = 'APPROVED'",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("generation", generationId),
        (rs, n) ->
            new Line(
                rs.getObject("timesheet_id", UUID.class),
                rs.getInt("current_revision"),
                rs.getObject("action_id", UUID.class),
                rs.getInt("cycle"),
                rs.getObject("employee_id", UUID.class),
                rs.getString("employee_number"),
                rs.getObject("employment_id", UUID.class),
                rs.getObject("cost_center_id", UUID.class),
                rs.getObject("work_date", LocalDate.class),
                rs.getObject("slot_id", UUID.class),
                rs.getInt("worked_minutes")));
  }

  /**
   * The generation's timesheets by state (the preview's exclusions).
   *
   * @param tenant verified tenant
   * @param generationId generation
   * @return state to count
   */
  public Map<String, Integer> states(TenantId tenant, UUID generationId) {
    Map<String, Integer> states = new TreeMap<>();
    jdbc.query(
        "SELECT t.status, count(*) AS n FROM people.timesheet t"
            + " WHERE t.tenant_id = :tenant AND t.generation_id = :generation GROUP BY t.status",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("generation", generationId),
        rs -> {
          states.put(rs.getString("status"), rs.getInt("n"));
        });
    return states;
  }

  /**
   * The export of identical evidence for a site's week, if any.
   *
   * @param tenant verified tenant
   * @param siteId site
   * @param weekStart local Monday
   * @param evidenceDigest evidence digest
   * @return the export
   */
  public Optional<Header> byEvidence(
      TenantId tenant, UUID siteId, LocalDate weekStart, String evidenceDigest) {
    return jdbc
        .query(
            "SELECT "
                + EXPORT_COLUMNS
                + " FROM people.timesheet_export e WHERE e.tenant_id = :tenant"
                + " AND e.site_id = :site AND e.week_start = :week"
                + " AND e.evidence_digest = :evidence",
            new MapSqlParameterSource()
                .addValue("tenant", tenant.value())
                .addValue("site", siteId)
                .addValue("week", weekStart)
                .addValue("evidence", evidenceDigest),
            (rs, n) -> header(rs))
        .stream()
        .findFirst();
  }

  /**
   * An export by ID.
   *
   * @param tenant verified tenant
   * @param id export
   * @return the export, or empty for an unknown or foreign one
   */
  public Optional<Header> export(TenantId tenant, UUID id) {
    return jdbc
        .query(
            "SELECT "
                + EXPORT_COLUMNS
                + " FROM people.timesheet_export e WHERE e.tenant_id = :tenant AND e.id = :id",
            new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("id", id),
            (rs, n) -> header(rs))
        .stream()
        .findFirst();
  }

  /**
   * An export's lines in CSV order.
   *
   * @param tenant verified tenant
   * @param exportId export
   * @return the lines
   */
  public List<Line> lines(TenantId tenant, UUID exportId) {
    return jdbc.query(
        "SELECT x.timesheet_id, x.timesheet_revision, x.approval_action_id, x.approval_cycle,"
            + " x.employee_id, x.employee_number, x.employment_id, x.cost_center_id, x.work_date,"
            + " x.shift_id, x.worked_minutes FROM people.timesheet_export_row x"
            + " WHERE x.tenant_id = :tenant AND x.export_id = :export ORDER BY x.line",
        new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("export", exportId),
        (rs, n) ->
            new Line(
                rs.getObject("timesheet_id", UUID.class),
                rs.getInt("timesheet_revision"),
                rs.getObject("approval_action_id", UUID.class),
                rs.getInt("approval_cycle"),
                rs.getObject("employee_id", UUID.class),
                rs.getString("employee_number"),
                rs.getObject("employment_id", UUID.class),
                rs.getObject("cost_center_id", UUID.class),
                rs.getObject("work_date", LocalDate.class),
                rs.getObject("shift_id", UUID.class),
                rs.getInt("worked_minutes")));
  }

  /**
   * One page of the tenant's exports, newest first, optionally of one site and one week.
   *
   * @param tenant verified tenant
   * @param siteId site filter, or {@code null}
   * @param weekStart week filter, or {@code null}
   * @param afterCreatedAt keyset position, or {@code null}
   * @param afterId keyset position, or {@code null}
   * @param limit rows to read
   * @return the page
   */
  public List<Header> page(
      TenantId tenant,
      UUID siteId,
      LocalDate weekStart,
      Instant afterCreatedAt,
      UUID afterId,
      int limit) {
    MapSqlParameterSource params =
        new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("limit", limit);
    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(EXPORT_COLUMNS)
            .append(" FROM people.timesheet_export e WHERE e.tenant_id = :tenant");
    if (siteId != null) {
      sql.append(" AND e.site_id = :site");
      params.addValue("site", siteId);
    }
    if (weekStart != null) {
      sql.append(" AND e.week_start = :week");
      params.addValue("week", weekStart);
    }
    if (afterCreatedAt != null) {
      sql.append(" AND (e.created_at, e.id) < (:afterAt, :afterId)");
      params.addValue("afterAt", Timestamp.from(afterCreatedAt)).addValue("afterId", afterId);
    }
    sql.append(" ORDER BY e.created_at DESC, e.id DESC LIMIT :limit");
    return jdbc.query(sql.toString(), params, (rs, n) -> header(rs));
  }

  /**
   * Records an export and its lines (the binding triggers check each line against the timesheet
   * that is APPROVED now; the deferred check proves the counts and the CSV order at commit).
   *
   * @param tenant verified tenant
   * @param header the export
   * @param lines its lines in CSV order
   * @param createdBy verified subject
   */
  public void insert(TenantId tenant, Header header, List<Line> lines, String createdBy) {
    Scope scope = header.scope();
    Period period = scope.period();
    Totals totals = header.totals();
    jdbc.update(
        "INSERT INTO people.timesheet_export (tenant_id, id, schema_version, status,"
            + " legal_entity_id, site_id, generation_id, generation_version, week_start, week_end,"
            + " site_timezone, period_start_at, period_end_at, timesheet_count, employee_count,"
            + " row_count, worked_minutes, evidence_digest, csv_sha256, created_at, created_by)"
            + " VALUES (:tenant, :id, :schema, 'READY', :legalEntity, :site, :generation,"
            + " :generationVersion, :week, :weekEnd, :zone, :from, :to, :timesheets, :employees,"
            + " :rows, :minutes, :evidence, :csv, :at, :by)",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("id", header.id())
            .addValue("schema", TimesheetExport.SCHEMA_VERSION)
            .addValue("legalEntity", scope.legalEntityId())
            .addValue("site", period.siteId())
            .addValue("generation", scope.generationId())
            .addValue("generationVersion", scope.generationVersion())
            .addValue("week", period.weekStart())
            .addValue("weekEnd", period.weekEnd())
            .addValue("zone", period.timezone())
            .addValue("from", Timestamp.from(period.startAt()))
            .addValue("to", Timestamp.from(period.endAt()))
            .addValue("timesheets", totals.timesheets())
            .addValue("employees", totals.employees())
            .addValue("rows", totals.rows())
            .addValue("minutes", totals.workedMinutes())
            .addValue("evidence", header.evidenceDigest())
            .addValue("csv", header.csvSha256())
            .addValue("at", Timestamp.from(header.createdAt()))
            .addValue("by", createdBy));
    List<SqlParameterSource> batch = new ArrayList<>(lines.size());
    int number = 0;
    for (Line line : lines) {
      number++;
      batch.add(
          new MapSqlParameterSource()
              .addValue("tenant", tenant.value())
              .addValue("export", header.id())
              .addValue("line", number)
              .addValue("timesheet", line.timesheetId())
              .addValue("revision", line.revision())
              .addValue("action", line.approvalActionId())
              .addValue("cycle", line.approvalCycle())
              .addValue("employee", line.employeeId())
              .addValue("number", line.employeeNumber())
              .addValue("employment", line.employmentId())
              .addValue("costCenter", line.costCenterId())
              .addValue("date", line.workDate())
              .addValue("shift", line.shiftId())
              .addValue("minutes", line.workedMinutes()));
    }
    jdbc.batchUpdate(
        "INSERT INTO people.timesheet_export_row (tenant_id, export_id, line, timesheet_id,"
            + " timesheet_revision, approval_action_id, approval_cycle, employee_id,"
            + " employee_number, employment_id, cost_center_id, work_date, shift_id,"
            + " worked_minutes) VALUES (:tenant, :export, :line, :timesheet, :revision, :action,"
            + " :cycle, :employee, :number, :employment, CAST(:costCenter AS uuid), :date, :shift,"
            + " :minutes)",
        batch.toArray(SqlParameterSource[]::new));
  }

  /**
   * Runs the deferred checks now (inside the caller's transaction), so a violation is mapped like
   * any constraint instead of failing at commit.
   *
   * @param tenant verified tenant (the checks are per export)
   */
  public void checkConsistent(TenantId tenant) {
    jdbc.getJdbcTemplate()
        .execute(
            "SET CONSTRAINTS people.timesheet_export_exact, people.timesheet_export_row_exact"
                + " IMMEDIATE");
  }

  private static Header header(ResultSet rs) throws SQLException {
    return new Header(
        rs.getObject("id", UUID.class),
        new Scope(
            rs.getObject("legal_entity_id", UUID.class),
            new Period(
                rs.getObject("site_id", UUID.class),
                rs.getString("site_timezone"),
                rs.getObject("week_start", LocalDate.class),
                rs.getTimestamp("period_start_at").toInstant(),
                rs.getTimestamp("period_end_at").toInstant()),
            rs.getObject("generation_id", UUID.class),
            rs.getInt("generation_version")),
        new Totals(
            rs.getInt("timesheet_count"),
            rs.getInt("employee_count"),
            rs.getInt("row_count"),
            rs.getInt("worked_minutes")),
        rs.getString("evidence_digest"),
        rs.getString("csv_sha256"),
        rs.getTimestamp("created_at").toInstant());
  }
}
