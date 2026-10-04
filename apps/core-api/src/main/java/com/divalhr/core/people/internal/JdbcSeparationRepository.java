package com.divalhr.core.people.internal;

import com.divalhr.core.people.domain.history.Assignment;
import com.divalhr.core.people.domain.history.AssignmentKind;
import com.divalhr.core.people.domain.history.AssignmentValue;
import com.divalhr.core.people.domain.separation.AccessTiming;
import com.divalhr.core.people.domain.separation.ReportAction;
import com.divalhr.core.people.domain.separation.SeparationPlanner.ReportRow;
import com.divalhr.core.people.domain.separation.SeparationReason;
import com.divalhr.core.people.domain.separation.SeparationState;
import com.divalhr.core.people.domain.separation.TaskCode;
import com.divalhr.core.people.domain.separation.TaskStatus;
import com.divalhr.core.platform.tenancy.CrossTenantAccess;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Separation persistence (MVP-022): separations, their follow-up tasks, the direct-report rows a
 * separation affects and the changes bound to it. Every method takes the verified {@link TenantId}
 * except the effective-date job's claim. Rows are inserted, or move state once as the V15 guards
 * allow; nothing is deleted.
 */
@Repository
public class JdbcSeparationRepository {

  private static final String SEPARATION_COLUMNS =
      "id, employee_id, employment_id, change_id, last_day, reason_code, access_timing,"
          + " report_action, replacement_manager_id, report_count, interval_count, state,"
          + " effective_at, recorded_at, cancelled_at, version, tenant_id";

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC client
   */
  public JdbcSeparationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * A separation (Restricted HR).
   *
   * @param id separation
   * @param employeeId employee
   * @param employmentId employment
   * @param changeId its SEPARATION change
   * @param lastDay inclusive last day
   * @param reason reason
   * @param accessTiming access timing
   * @param reportAction direct-report action, or {@code null}
   * @param replacementManagerId replacement manager, or {@code null}
   * @param reportCount reports affected
   * @param intervalCount intervals affected
   * @param state state
   * @param effectiveAt start of the day after the last day
   * @param recordedAt when recorded
   * @param cancelledAt when cancelled, or {@code null}
   * @param version version
   * @param tenant tenant
   */
  public record SeparationRecord(
      UUID id,
      UUID employeeId,
      UUID employmentId,
      UUID changeId,
      LocalDate lastDay,
      SeparationReason reason,
      AccessTiming accessTiming,
      ReportAction reportAction,
      UUID replacementManagerId,
      int reportCount,
      int intervalCount,
      SeparationState state,
      Instant effectiveAt,
      Instant recordedAt,
      Instant cancelledAt,
      long version,
      TenantId tenant) {}

  /**
   * A follow-up task.
   *
   * @param id task
   * @param separationId separation
   * @param code code
   * @param status status
   * @param dueDate due date
   * @param updatedAt last change
   * @param version version
   */
  public record TaskRecord(
      UUID id,
      UUID separationId,
      TaskCode code,
      TaskStatus status,
      LocalDate dueDate,
      Instant updatedAt,
      long version) {}

  /**
   * A change bound to a separation.
   *
   * @param id change
   * @param employeeId its employee
   * @param employmentId its employment
   * @param effectiveFrom effective date
   * @param separation whether it is the SEPARATION change itself
   */
  public record BoundChange(
      UUID id, UUID employeeId, UUID employmentId, LocalDate effectiveFrom, boolean separation) {}

  // -------------------------------------------------------------------------------------------
  // Separations
  // -------------------------------------------------------------------------------------------

  /**
   * Inserts a separation: SCHEDULED while its instant is ahead on the database clock, else
   * EFFECTIVE at once (a retroactive separation).
   *
   * @param tenant verified tenant
   * @param record what to insert (state, cancellation and version are ignored)
   * @param recordedBy verified subject
   * @return the state it was recorded in
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public SeparationState insert(TenantId tenant, SeparationRecord record, String recordedBy) {
    return SeparationState.valueOf(
        jdbc.sql(
                "INSERT INTO people.employment_separation (id, tenant_id, employee_id,"
                    + " employment_id, change_id, last_day, reason_code, access_timing,"
                    + " report_action, replacement_manager_id, report_count, interval_count, state,"
                    + " effective_at, effective_marked_at, recorded_at, recorded_by)"
                    + " SELECT :id, :tenant, :employee, :employment, :change, :lastDay, :reason,"
                    + " :timing, :action, :replacement, :reports, :intervals,"
                    + " CASE WHEN due THEN 'EFFECTIVE' ELSE 'SCHEDULED' END, :effectiveAt,"
                    + " CASE WHEN due THEN statement_timestamp() END, :at, :by"
                    + " FROM (SELECT CAST(:effectiveAt AS timestamptz) <= statement_timestamp()"
                    + " AS due) t RETURNING state")
            .param("id", record.id())
            .param("tenant", tenant.value())
            .param("employee", record.employeeId())
            .param("employment", record.employmentId())
            .param("change", record.changeId())
            .param("lastDay", record.lastDay())
            .param("reason", record.reason().name())
            .param("timing", record.accessTiming().name())
            .param("action", record.reportAction() == null ? null : record.reportAction().name())
            .param("replacement", record.replacementManagerId())
            .param("reports", record.reportCount())
            .param("intervals", record.intervalCount())
            .param("effectiveAt", Timestamp.from(record.effectiveAt()))
            .param("at", Timestamp.from(record.recordedAt()))
            .param("by", recordedBy)
            .query(String.class)
            .single());
  }

  /**
   * One separation of an employee.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param separationId separation
   * @return the separation, if it belongs to that employee in the tenant
   */
  public Optional<SeparationRecord> find(TenantId tenant, UUID employeeId, UUID separationId) {
    return jdbc.sql(
            "SELECT "
                + SEPARATION_COLUMNS
                + " FROM people.employment_separation WHERE tenant_id = :tenant"
                + " AND employee_id = :employee AND id = :id")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .param("id", separationId)
        .query(JdbcSeparationRepository::separation)
        .optional();
  }

  /**
   * Locks one separation of an employee (step 5 of the ADR 0008 lock order).
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param separationId separation
   * @return the locked separation, if it belongs to that employee in the tenant
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<SeparationRecord> lock(TenantId tenant, UUID employeeId, UUID separationId) {
    return jdbc.sql(
            "SELECT "
                + SEPARATION_COLUMNS
                + " FROM people.employment_separation WHERE tenant_id = :tenant"
                + " AND employee_id = :employee AND id = :id FOR UPDATE")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .param("id", separationId)
        .query(JdbcSeparationRepository::separation)
        .optional();
  }

  /**
   * An employee's separations, newest first (at most 50).
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @return separations
   */
  public List<SeparationRecord> ofEmployee(TenantId tenant, UUID employeeId) {
    return jdbc.sql(
            "SELECT "
                + SEPARATION_COLUMNS
                + " FROM people.employment_separation WHERE tenant_id = :tenant"
                + " AND employee_id = :employee ORDER BY recorded_at DESC, id DESC LIMIT 50")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .query(JdbcSeparationRepository::separation)
        .list();
  }

  /**
   * Whether the employee has a separation that is not cancelled.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @return true when separated or scheduled to be
   */
  public boolean open(TenantId tenant, UUID employeeId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM people.employment_separation WHERE tenant_id = :tenant"
                + " AND employee_id = :employee AND state <> 'CANCELLED')")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .query(Boolean.class)
        .single();
  }

  /**
   * Whether an employee exists in the tenant.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @return true when it exists
   */
  public boolean employeeExists(TenantId tenant, UUID employeeId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM people.employee WHERE tenant_id = :tenant"
                + " AND id = :id)")
        .param("tenant", tenant.value())
        .param("id", employeeId)
        .query(Boolean.class)
        .single();
  }

  /**
   * Cancels a scheduled separation (its SEPARATION change must already be cancelled; the database
   * also requires its instant to be ahead).
   *
   * @param tenant verified tenant
   * @param separationId separation
   * @param version expected version
   * @param by verified subject
   * @param at cancellation time
   * @return rows updated
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int cancel(TenantId tenant, UUID separationId, long version, String by, Instant at) {
    return jdbc.sql(
            "UPDATE people.employment_separation SET state = 'CANCELLED', cancelled_at = :at,"
                + " cancelled_by = :by, version = version + 1 WHERE tenant_id = :tenant"
                + " AND id = :id AND version = :version AND state = 'SCHEDULED'")
        .param("at", Timestamp.from(at))
        .param("by", by)
        .param("tenant", tenant.value())
        .param("id", separationId)
        .param("version", version)
        .update();
  }

  /**
   * Marks due scheduled separations effective (the effective-date job; several instances never
   * claim the same row).
   *
   * @param limit batch size
   * @return the separations marked
   */
  @CrossTenantAccess("background effective-date transition of separations across tenants")
  @Transactional(propagation = Propagation.MANDATORY)
  public List<SeparationRecord> markDueEffective(int limit) {
    return jdbc.sql(
            "UPDATE people.employment_separation SET state = 'EFFECTIVE',"
                + " effective_marked_at = statement_timestamp(), version = version + 1"
                + " WHERE id IN (SELECT id FROM people.employment_separation"
                + " WHERE state = 'SCHEDULED' AND effective_at <= statement_timestamp()"
                + " ORDER BY effective_at LIMIT :limit FOR UPDATE SKIP LOCKED) RETURNING "
                + SEPARATION_COLUMNS)
        .param("limit", limit)
        .query(JdbcSeparationRepository::separation)
        .list();
  }

  private static SeparationRecord separation(ResultSet rs, int row) throws SQLException {
    String action = rs.getString("report_action");
    Timestamp cancelled = rs.getTimestamp("cancelled_at");
    return new SeparationRecord(
        rs.getObject("id", UUID.class),
        rs.getObject("employee_id", UUID.class),
        rs.getObject("employment_id", UUID.class),
        rs.getObject("change_id", UUID.class),
        rs.getObject("last_day", LocalDate.class),
        SeparationReason.valueOf(rs.getString("reason_code")),
        AccessTiming.valueOf(rs.getString("access_timing")),
        action == null ? null : ReportAction.valueOf(action),
        rs.getObject("replacement_manager_id", UUID.class),
        rs.getInt("report_count"),
        rs.getInt("interval_count"),
        SeparationState.valueOf(rs.getString("state")),
        rs.getTimestamp("effective_at").toInstant(),
        rs.getTimestamp("recorded_at").toInstant(),
        cancelled == null ? null : cancelled.toInstant(),
        rs.getLong("version"),
        new TenantId(rs.getObject("tenant_id", UUID.class)));
  }

  // -------------------------------------------------------------------------------------------
  // Direct reports and bound changes
  // -------------------------------------------------------------------------------------------

  /**
   * Active MANAGER rows naming a manager that extend past a day (the direct reports a separation
   * affects; read under the manager-graph lock by the coordinator).
   *
   * @param tenant verified tenant
   * @param managerId the separated employee
   * @param lastDay inclusive last day
   * @return the rows, with their employee and employment
   */
  public List<ReportRow> reportRows(TenantId tenant, UUID managerId, LocalDate lastDay) {
    return jdbc.sql(
            "SELECT id, employee_id, employment_id, effective_from, effective_to,"
                + " manager_employee_id, created_by_change_id, origin_change_id,"
                + " restores_assignment_id FROM people.employment_assignment"
                + " WHERE tenant_id = :tenant AND kind = 'MANAGER'"
                + " AND manager_employee_id = :manager AND superseded_by_change_id IS NULL"
                + " AND (effective_to IS NULL OR effective_to > :lastDay)"
                + " ORDER BY employee_id, employment_id, effective_from")
        .param("tenant", tenant.value())
        .param("manager", managerId)
        .param("lastDay", lastDay)
        .query(
            (rs, row) ->
                new ReportRow(
                    rs.getObject("employee_id", UUID.class),
                    rs.getObject("employment_id", UUID.class),
                    new Assignment(
                        rs.getObject("id", UUID.class),
                        AssignmentKind.MANAGER,
                        rs.getObject("effective_from", LocalDate.class),
                        rs.getObject("effective_to", LocalDate.class),
                        new AssignmentValue.Manager(
                            rs.getObject("manager_employee_id", UUID.class)),
                        rs.getObject("created_by_change_id", UUID.class),
                        rs.getObject("origin_change_id", UUID.class),
                        rs.getObject("restores_assignment_id", UUID.class),
                        null,
                        null)))
        .list();
  }

  /**
   * The active changes bound to a separation: its SEPARATION change and the direct-report changes
   * it generated.
   *
   * @param tenant verified tenant
   * @param separationId separation
   * @return the changes, the SEPARATION change last
   */
  public List<BoundChange> boundChanges(TenantId tenant, UUID separationId) {
    return jdbc.sql(
            "SELECT id, employee_id, employment_id, effective_from, type = 'SEPARATION' AS sep"
                + " FROM people.employment_change WHERE tenant_id = :tenant"
                + " AND separation_id = :separation AND type IN ('SEPARATION', 'CHANGE')"
                + " AND state = 'ACTIVE'"
                + " ORDER BY (type = 'SEPARATION'), employee_id, effective_from DESC, id")
        .param("tenant", tenant.value())
        .param("separation", separationId)
        .query(
            (rs, row) ->
                new BoundChange(
                    rs.getObject("id", UUID.class),
                    rs.getObject("employee_id", UUID.class),
                    rs.getObject("employment_id", UUID.class),
                    rs.getObject("effective_from", LocalDate.class),
                    rs.getBoolean("sep")))
        .list();
  }

  // -------------------------------------------------------------------------------------------
  // Tasks
  // -------------------------------------------------------------------------------------------

  /**
   * Creates the separation's follow-up tasks (OPEN).
   *
   * @param tenant verified tenant
   * @param separationId separation
   * @param employeeId employee
   * @param dueDate due date
   * @param at creation time
   * @param by verified subject
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertTasks(
      TenantId tenant,
      UUID separationId,
      UUID employeeId,
      LocalDate dueDate,
      Instant at,
      String by) {
    for (TaskCode code : TaskCode.values()) {
      jdbc.sql(
              "INSERT INTO people.separation_task (id, tenant_id, employee_id, separation_id, code,"
                  + " status, due_date, updated_at, updated_by) VALUES (:id, :tenant, :employee,"
                  + " :separation, :code, 'OPEN', :due, :at, :by)")
          .param("id", UUID.randomUUID())
          .param("tenant", tenant.value())
          .param("employee", employeeId)
          .param("separation", separationId)
          .param("code", code.name())
          .param("due", dueDate)
          .param("at", Timestamp.from(at))
          .param("by", by)
          .update();
    }
  }

  /**
   * The tasks of separations.
   *
   * @param tenant verified tenant
   * @param separationIds separations
   * @return tasks, by separation then code order
   */
  public List<TaskRecord> tasks(TenantId tenant, Collection<UUID> separationIds) {
    if (separationIds.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            "SELECT id, separation_id, code, status, due_date, updated_at, version"
                + " FROM people.separation_task WHERE tenant_id = :tenant"
                + " AND separation_id = ANY(:ids)"
                + " ORDER BY separation_id, array_position(ARRAY['RETURN_ASSIGNED_ASSETS',"
                + " 'COLLECT_OR_ARCHIVE_DOCUMENTS'], code)")
        .param("tenant", tenant.value())
        .param("ids", separationIds.toArray(UUID[]::new))
        .query(JdbcSeparationRepository::task)
        .list();
  }

  /**
   * Locks one task of a separation (step 5).
   *
   * @param tenant verified tenant
   * @param separationId separation
   * @param taskId task
   * @return the locked task, if it belongs to that separation
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<TaskRecord> lockTask(TenantId tenant, UUID separationId, UUID taskId) {
    return jdbc.sql(
            "SELECT id, separation_id, code, status, due_date, updated_at, version"
                + " FROM people.separation_task WHERE tenant_id = :tenant"
                + " AND separation_id = :separation AND id = :id FOR UPDATE")
        .param("tenant", tenant.value())
        .param("separation", separationId)
        .param("id", taskId)
        .query(JdbcSeparationRepository::task)
        .optional();
  }

  /**
   * Sets a task's status at its expected version (the database records the history itself).
   *
   * @param tenant verified tenant
   * @param taskId task
   * @param status new status
   * @param version expected version
   * @param at change time
   * @param by verified subject
   * @return rows updated
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int setStatus(
      TenantId tenant, UUID taskId, TaskStatus status, long version, Instant at, String by) {
    return jdbc.sql(
            "UPDATE people.separation_task SET status = :status, updated_at = :at,"
                + " updated_by = :by, version = version + 1 WHERE tenant_id = :tenant"
                + " AND id = :id AND version = :version")
        .param("status", status.name())
        .param("at", Timestamp.from(at))
        .param("by", by)
        .param("tenant", tenant.value())
        .param("id", taskId)
        .param("version", version)
        .update();
  }

  /**
   * Closes every open, done or not-applicable task of a cancelled separation.
   *
   * @param tenant verified tenant
   * @param separationId separation (already cancelled)
   * @param at change time
   * @param by verified subject
   * @return tasks closed
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int closeTasks(TenantId tenant, UUID separationId, Instant at, String by) {
    return jdbc.sql(
            "UPDATE people.separation_task SET status = 'CANCELLED', updated_at = :at,"
                + " updated_by = :by, version = version + 1 WHERE tenant_id = :tenant"
                + " AND separation_id = :separation AND status <> 'CANCELLED'")
        .param("at", Timestamp.from(at))
        .param("by", by)
        .param("tenant", tenant.value())
        .param("separation", separationId)
        .update();
  }

  private static TaskRecord task(ResultSet rs, int row) throws SQLException {
    return new TaskRecord(
        rs.getObject("id", UUID.class),
        rs.getObject("separation_id", UUID.class),
        TaskCode.valueOf(rs.getString("code")),
        TaskStatus.valueOf(rs.getString("status")),
        rs.getObject("due_date", LocalDate.class),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getLong("version"));
  }
}
