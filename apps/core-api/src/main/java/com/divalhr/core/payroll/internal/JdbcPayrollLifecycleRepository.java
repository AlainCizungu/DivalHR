package com.divalhr.core.payroll.internal;

import com.divalhr.core.payroll.domain.PayrollInputs.Run;
import com.divalhr.core.payroll.domain.PayrollInputs.Scope;
import com.divalhr.core.payroll.domain.PayrollLifecycle;
import com.divalhr.core.payroll.domain.PayrollLifecycle.Export;
import com.divalhr.core.payroll.domain.PayrollLifecycle.Line;
import com.divalhr.core.payroll.domain.PayrollLifecycle.Lock;
import com.divalhr.core.payroll.domain.PayrollLifecycle.Reason;
import com.divalhr.core.payroll.domain.PayrollLifecycle.State;
import com.divalhr.core.platform.tenancy.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

/**
 * Storage of the payroll-input lifecycle (MVP-092; V38): supersessions, the final lock and the
 * worked-time export with its rows. Every write is an insert; the schema refuses everything else.
 * Only the payroll module reads these tables.
 */
@Repository
public class JdbcPayrollLifecycleRepository {

  private static final String EXPORT_COLUMNS =
      "e.id, e.run_id, e.run_version, e.legal_entity_id, e.period_start, e.period_end,"
          + " r.business_timezone, e.lock_id, l.lock_version, e.employee_count, e.row_count,"
          + " e.worked_minutes, e.csv_sha256, e.created_at";
  private static final String EXPORT_FROM =
      " FROM payroll.input_export e JOIN payroll.input_run r ON r.tenant_id = e.tenant_id AND r.id"
          + " = e.run_id JOIN payroll.input_run_lock l ON l.tenant_id = e.tenant_id AND l.id ="
          + " e.lock_id";

  private final NamedParameterJdbcTemplate jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc named-parameter template
   */
  public JdbcPayrollLifecycleRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // ------------------------------------------------------------------------------------------
  // State
  // ------------------------------------------------------------------------------------------

  /**
   * The lifecycle state of runs: the chain link, the final lock, the export and whether a later
   * version exists.
   *
   * @param tenant verified tenant
   * @param runIds runs
   * @return the states by run
   */
  public Map<UUID, State> states(TenantId tenant, Collection<UUID> runIds) {
    Map<UUID, State> states = new HashMap<>();
    if (runIds.isEmpty()) {
      return states;
    }
    jdbc.query(
        "SELECT r.id, r.version, s.supersedes_run_id, s.reason_code, s.explanation, l.id AS"
            + " lock_id, l.locked_at, x.id AS export_id, NOT EXISTS (SELECT 1 FROM"
            + " payroll.input_run n WHERE n.tenant_id = r.tenant_id AND n.legal_entity_id ="
            + " r.legal_entity_id AND n.period_start = r.period_start AND n.period_end ="
            + " r.period_end AND n.version > r.version) AS latest FROM payroll.input_run r LEFT"
            + " JOIN payroll.input_run_supersession s ON s.tenant_id = r.tenant_id AND s.run_id ="
            + " r.id LEFT JOIN payroll.input_run_lock l ON l.tenant_id = r.tenant_id AND l.run_id"
            + " = r.id LEFT JOIN payroll.input_export x ON x.tenant_id = r.tenant_id AND x.run_id"
            + " = r.id WHERE r.tenant_id = :tenant AND r.id IN (:ids)",
        new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("ids", runIds),
        rs -> {
          UUID id = rs.getObject("id", UUID.class);
          UUID lockId = rs.getObject("lock_id", UUID.class);
          String reason = rs.getString("reason_code");
          states.put(
              id,
              new State(
                  id,
                  rs.getInt("version"),
                  rs.getObject("supersedes_run_id", UUID.class),
                  reason == null ? null : Reason.valueOf(reason),
                  rs.getString("explanation"),
                  lockId == null
                      ? null
                      : new Lock(lockId, id, rs.getTimestamp("locked_at").toInstant()),
                  rs.getBoolean("latest"),
                  rs.getObject("export_id", UUID.class)));
        });
    return states;
  }

  /**
   * The state of one run.
   *
   * @param tenant verified tenant
   * @param runId run
   * @return its state, or empty for an unknown or foreign run
   */
  public Optional<State> state(TenantId tenant, UUID runId) {
    return Optional.ofNullable(states(tenant, List.of(runId)).get(runId));
  }

  // ------------------------------------------------------------------------------------------
  // Supersession and lock
  // ------------------------------------------------------------------------------------------

  /**
   * Records that a run replaces the previous version of its scope. The schema checks the exact
   * previous version, that it is the latest and not locked, and (at commit) that the chain has no
   * gap.
   *
   * @param tenant verified tenant
   * @param runId the new run (already inserted in this transaction)
   * @param supersedesRunId the replaced run
   * @param reason why
   * @param explanation the bounded explanation of OTHER, or {@code null}
   * @param at instant
   * @param by verified subject
   */
  public void insertSupersession(
      TenantId tenant,
      UUID runId,
      UUID supersedesRunId,
      Reason reason,
      String explanation,
      Instant at,
      String by) {
    jdbc.update(
        "INSERT INTO payroll.input_run_supersession (tenant_id, run_id, supersedes_run_id,"
            + " reason_code, explanation, created_at, created_by) VALUES (:tenant, :run,"
            + " :supersedes, :reason, :explanation, :at, :by)",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("run", runId)
            .addValue("supersedes", supersedesRunId)
            .addValue("reason", reason.name())
            .addValue("explanation", explanation)
            .addValue("at", Timestamp.from(at))
            .addValue("by", by));
  }

  /**
   * Records the final LOCKED action of a run. The schema checks that it is the latest version and
   * that the lock records exactly the run.
   *
   * @param tenant verified tenant
   * @param lockId the LOCKED action
   * @param run the run
   * @param at instant
   * @param by verified subject
   */
  public void insertLock(TenantId tenant, UUID lockId, Run run, Instant at, String by) {
    jdbc.update(
        "INSERT INTO payroll.input_run_lock (tenant_id, id, run_id, legal_entity_id, period_start,"
            + " period_end, run_version, action, lock_version, evidence_digest, locked_at,"
            + " locked_by) VALUES (:tenant, :id, :run, :entity, :start, :end, :version, 'LOCKED',"
            + " 1, :digest, :at, :by)",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("id", lockId)
            .addValue("run", run.id())
            .addValue("entity", run.scope().legalEntityId())
            .addValue("start", run.scope().periodStart())
            .addValue("end", run.scope().periodEnd())
            .addValue("version", run.version())
            .addValue("digest", run.evidenceDigest())
            .addValue("at", Timestamp.from(at))
            .addValue("by", by));
  }

  /**
   * Serializes the creation of one run's export: a row lock on the run's final lock.
   *
   * @param tenant verified tenant
   * @param runId run
   * @return the lock's action and version, or empty when the run is not locked
   */
  public Optional<UUID> lockForExport(TenantId tenant, UUID runId) {
    return jdbc
        .query(
            "SELECT id FROM payroll.input_run_lock WHERE tenant_id = :tenant AND run_id = :run FOR"
                + " UPDATE",
            new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("run", runId),
            (rs, n) -> rs.getObject("id", UUID.class))
        .stream()
        .findFirst();
  }

  /**
   * Runs the deferred checks now (inside the caller's transaction), so a violation is mapped like
   * any constraint instead of failing at commit.
   */
  public void checkConsistent() {
    jdbc.getJdbcTemplate()
        .execute(
            "SET CONSTRAINTS payroll.payroll_input_run_exact, payroll.payroll_input_run_chain,"
                + " payroll.payroll_input_export_exact IMMEDIATE");
  }

  // ------------------------------------------------------------------------------------------
  // Export
  // ------------------------------------------------------------------------------------------

  /**
   * The immutable source rows of a run as export lines, unordered.
   *
   * @param tenant verified tenant
   * @param runId run
   * @return the lines
   */
  public List<Line> sourceLines(TenantId tenant, UUID runId) {
    return jdbc.query(
        "SELECT r.line AS source_line, r.employee_id, r.employment_id, run.legal_entity_id,"
            + " r.site_id, r.cost_center_id, r.work_date, r.worked_minutes, r.timesheet_id,"
            + " r.timesheet_revision, r.approval_action_id, r.approval_cycle, w.week_start,"
            + " r.generation_id, w.generation_version, r.lock_action_id, w.lock_version, r.run_id,"
            + " run.version AS run_version FROM payroll.input_source_row r JOIN payroll.input_run"
            + " run ON run.tenant_id = r.tenant_id AND run.id = r.run_id JOIN"
            + " payroll.input_source_week w ON w.tenant_id = r.tenant_id AND w.run_id = r.run_id"
            + " AND w.generation_id = r.generation_id WHERE r.tenant_id = :tenant AND r.run_id ="
            + " :run",
        new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("run", runId),
        (rs, n) -> line(rs, "source_line"));
  }

  /**
   * The export rows in CSV order.
   *
   * @param tenant verified tenant
   * @param exportId export
   * @return the lines
   */
  public List<Line> lines(TenantId tenant, UUID exportId) {
    return jdbc.query(
        "SELECT source_line, employee_id, employment_id, legal_entity_id, site_id, cost_center_id,"
            + " work_date, approved_worked_minutes AS worked_minutes, timesheet_id,"
            + " timesheet_revision, approval_action_id, approval_cycle, source_week_start AS"
            + " week_start, source_generation_id AS generation_id, source_generation_version AS"
            + " generation_version, source_lock_action_id AS lock_action_id, source_lock_version AS"
            + " lock_version, payroll_input_run_id AS run_id, payroll_input_run_version AS"
            + " run_version FROM payroll.input_export_row WHERE tenant_id = :tenant AND export_id"
            + " = :export ORDER BY line",
        new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("export", exportId),
        (rs, n) -> line(rs, "source_line"));
  }

  /**
   * Records an export with its rows in the CSV order (the deferred exactness check proves that they
   * are all and only the run's source rows, at commit or through {@link #checkConsistent}).
   *
   * @param tenant verified tenant
   * @param export the export
   * @param ordered the lines in CSV order
   * @param by verified subject
   */
  public void insertExport(TenantId tenant, Export export, List<Line> ordered, String by) {
    jdbc.update(
        "INSERT INTO payroll.input_export (tenant_id, id, run_id, lock_id, schema_version,"
            + " legal_entity_id, period_start, period_end, run_version, employee_count, row_count,"
            + " worked_minutes, csv_sha256, created_at, created_by) VALUES (:tenant, :id, :run,"
            + " :lock, :schema, :entity, :start, :end, :version, :employees, :rows, :minutes,"
            + " :sha, :at, :by)",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("id", export.id())
            .addValue("run", export.runId())
            .addValue("lock", export.lockId())
            .addValue("schema", PayrollLifecycle.EXPORT_SCHEMA_VERSION)
            .addValue("entity", export.scope().legalEntityId())
            .addValue("start", export.scope().periodStart())
            .addValue("end", export.scope().periodEnd())
            .addValue("version", export.runVersion())
            .addValue("employees", export.employees())
            .addValue("rows", export.rows())
            .addValue("minutes", export.workedMinutes())
            .addValue("sha", export.csvSha256())
            .addValue("at", Timestamp.from(export.createdAt()))
            .addValue("by", by));
    List<SqlParameterSource> rows = new ArrayList<>();
    int number = 0;
    for (Line line : ordered) {
      number++;
      rows.add(
          new MapSqlParameterSource()
              .addValue("tenant", tenant.value())
              .addValue("export", export.id())
              .addValue("line", number)
              .addValue("sourceLine", line.sourceLine())
              .addValue("employee", line.employeeId())
              .addValue("employment", line.employmentId())
              .addValue("entity", line.legalEntityId())
              .addValue("site", line.siteId())
              .addValue("costCenter", line.costCenterId())
              .addValue("date", line.workDate())
              .addValue("minutes", line.workedMinutes())
              .addValue("timesheet", line.timesheetId())
              .addValue("revision", line.timesheetRevision())
              .addValue("action", line.approvalActionId())
              .addValue("cycle", line.approvalCycle())
              .addValue("week", line.sourceWeekStart())
              .addValue("generation", line.sourceGenerationId())
              .addValue("generationVersion", line.sourceGenerationVersion())
              .addValue("lockAction", line.sourceLockActionId())
              .addValue("lockVersion", line.sourceLockVersion())
              .addValue("run", line.runId())
              .addValue("runVersion", line.runVersion()));
    }
    jdbc.batchUpdate(
        "INSERT INTO payroll.input_export_row (tenant_id, export_id, line, source_line,"
            + " employee_id, employment_id, legal_entity_id, site_id, cost_center_id, work_date,"
            + " approved_worked_minutes, timesheet_id, timesheet_revision, approval_action_id,"
            + " approval_cycle, source_week_start, source_generation_id, source_generation_version,"
            + " source_lock_action_id, source_lock_version, payroll_input_run_id,"
            + " payroll_input_run_version) VALUES (:tenant, :export, :line, :sourceLine, :employee,"
            + " :employment, :entity, :site, CAST(:costCenter AS uuid), :date, :minutes,"
            + " :timesheet, :revision, :action, :cycle, :week, :generation, :generationVersion,"
            + " :lockAction, :lockVersion, :run, :runVersion)",
        rows.toArray(SqlParameterSource[]::new));
  }

  /**
   * One export.
   *
   * @param tenant verified tenant
   * @param id export
   * @return the export, or empty for an unknown or foreign one
   */
  public Optional<Export> export(TenantId tenant, UUID id) {
    return jdbc
        .query(
            "SELECT "
                + EXPORT_COLUMNS
                + EXPORT_FROM
                + " WHERE e.tenant_id = :tenant AND e.id = :id",
            new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("id", id),
            (rs, n) -> export(rs))
        .stream()
        .findFirst();
  }

  /**
   * The export of a run, if one exists.
   *
   * @param tenant verified tenant
   * @param runId run
   * @return the export
   */
  public Optional<Export> exportOfRun(TenantId tenant, UUID runId) {
    return jdbc
        .query(
            "SELECT "
                + EXPORT_COLUMNS
                + EXPORT_FROM
                + " WHERE e.tenant_id = :tenant AND e.run_id = :run",
            new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("run", runId),
            (rs, n) -> export(rs))
        .stream()
        .findFirst();
  }

  /**
   * One page of the tenant's exports, newest first, optionally of one legal entity.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity filter, or {@code null}
   * @param afterCreatedAt keyset position, or {@code null}
   * @param afterId keyset position, or {@code null}
   * @param limit rows to read
   * @return the page
   */
  public List<Export> page(
      TenantId tenant, UUID legalEntityId, Instant afterCreatedAt, UUID afterId, int limit) {
    MapSqlParameterSource params =
        new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("limit", limit);
    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(EXPORT_COLUMNS)
            .append(EXPORT_FROM)
            .append(" WHERE e.tenant_id = :tenant");
    if (legalEntityId != null) {
      sql.append(" AND e.legal_entity_id = :legalEntity");
      params.addValue("legalEntity", legalEntityId);
    }
    if (afterCreatedAt != null) {
      sql.append(" AND (e.created_at, e.id) < (:afterAt, :afterId)");
      params.addValue("afterAt", Timestamp.from(afterCreatedAt)).addValue("afterId", afterId);
    }
    sql.append(" ORDER BY e.created_at DESC, e.id DESC LIMIT :limit");
    return jdbc.query(sql.toString(), params, (rs, n) -> export(rs));
  }

  // ------------------------------------------------------------------------------------------

  private static Line line(ResultSet rs, String sourceLine) throws SQLException {
    return new Line(
        rs.getInt(sourceLine),
        rs.getObject("employee_id", UUID.class),
        rs.getObject("employment_id", UUID.class),
        rs.getObject("legal_entity_id", UUID.class),
        rs.getObject("site_id", UUID.class),
        rs.getObject("cost_center_id", UUID.class),
        rs.getObject("work_date", LocalDate.class),
        rs.getInt("worked_minutes"),
        rs.getObject("timesheet_id", UUID.class),
        rs.getInt("timesheet_revision"),
        rs.getObject("approval_action_id", UUID.class),
        rs.getInt("approval_cycle"),
        rs.getObject("week_start", LocalDate.class),
        rs.getObject("generation_id", UUID.class),
        rs.getInt("generation_version"),
        rs.getObject("lock_action_id", UUID.class),
        rs.getInt("lock_version"),
        rs.getObject("run_id", UUID.class),
        rs.getInt("run_version"));
  }

  private static Export export(ResultSet rs) throws SQLException {
    return new Export(
        rs.getObject("id", UUID.class),
        rs.getObject("run_id", UUID.class),
        rs.getInt("run_version"),
        new Scope(
            rs.getObject("legal_entity_id", UUID.class),
            rs.getObject("period_start", LocalDate.class),
            rs.getObject("period_end", LocalDate.class),
            rs.getString("business_timezone")),
        rs.getObject("lock_id", UUID.class),
        rs.getInt("lock_version"),
        rs.getInt("employee_count"),
        rs.getInt("row_count"),
        rs.getInt("worked_minutes"),
        rs.getString("csv_sha256"),
        rs.getTimestamp("created_at").toInstant());
  }
}
