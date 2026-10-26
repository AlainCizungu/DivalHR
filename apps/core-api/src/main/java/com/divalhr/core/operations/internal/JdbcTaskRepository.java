package com.divalhr.core.operations.internal;

import com.divalhr.core.operations.domain.Task;
import com.divalhr.core.operations.domain.TaskAction;
import com.divalhr.core.operations.domain.TaskActionType;
import com.divalhr.core.operations.domain.TaskCancelReason;
import com.divalhr.core.operations.domain.TaskDueClass;
import com.divalhr.core.operations.domain.TaskFilter;
import com.divalhr.core.operations.domain.TaskPriority;
import com.divalhr.core.operations.domain.TaskState;
import com.divalhr.core.platform.tenancy.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Task storage (MVP-080, V41). Every query names the verified tenant; employee queries are also
 * bound to the employee resolved from the caller's own link. Runs in the caller's transaction.
 * Nothing user-entered is ever logged here.
 */
@Repository
public class JdbcTaskRepository {

  private static final String SELECT =
      "SELECT t.id, t.assignee_employee_id, t.title, t.instructions, t.priority, t.due_at,"
          + " t.state, t.version, t.created_at, t.created_by, t.started_at, t.completed_at,"
          + " t.cancelled_at, t.completion_note, t.cancel_reason, t.cancel_explanation"
          + " FROM operations.task t WHERE t.tenant_id = :tenant";

  private final NamedParameterJdbcTemplate jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC template
   */
  public JdbcTaskRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Inserts a new task and its {@code CREATED} action (sequence 1).
   *
   * @param tenant verified tenant
   * @param task the task (open, version 1)
   */
  public void insert(TenantId tenant, Task task) {
    jdbc.update(
        "INSERT INTO operations.task (tenant_id, id, assignee_employee_id, title, instructions,"
            + " priority, due_at, state, version, created_at, created_by)"
            + " VALUES (:tenant, :id, :assignee, :title, :instructions, :priority, :dueAt,"
            + " 'OPEN', 1, :createdAt, :createdBy)",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("id", task.id())
            .addValue("assignee", task.assigneeEmployeeId())
            .addValue("title", task.title())
            .addValue("instructions", task.instructions())
            .addValue("priority", task.priority().name())
            .addValue("dueAt", timestamp(task.dueAt()))
            .addValue("createdAt", Timestamp.from(task.createdAt()))
            .addValue("createdBy", task.createdBy()));
    jdbc.update(
        "INSERT INTO operations.task_action (tenant_id, task_id, seq, action, occurred_at,"
            + " actor_subject, actor_role, actor_employee_id, from_state, to_state)"
            + " VALUES (:tenant, :id, 1, 'CREATED', :at, :actor, 'tenant-admin', NULL, NULL,"
            + " 'OPEN')",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("id", task.id())
            .addValue("at", Timestamp.from(task.createdAt()))
            .addValue("actor", task.createdBy()));
  }

  /**
   * A task of the tenant, without locking.
   *
   * @param tenant verified tenant
   * @param id task
   * @return the task, or empty
   */
  public Optional<Task> find(TenantId tenant, UUID id) {
    return one(SELECT + " AND t.id = :id", params(tenant, id));
  }

  /**
   * A task of the tenant assigned to the employee, without locking.
   *
   * @param tenant verified tenant
   * @param employeeId the caller's own employee
   * @param id task
   * @return the task, or empty
   */
  public Optional<Task> findOwn(TenantId tenant, UUID employeeId, UUID id) {
    return one(
        SELECT + " AND t.id = :id AND t.assignee_employee_id = :employee",
        params(tenant, id).addValue("employee", employeeId));
  }

  /**
   * A task of the tenant, locked {@code FOR UPDATE} (lock order step 5).
   *
   * @param tenant verified tenant
   * @param id task
   * @return the task, or empty
   */
  public Optional<Task> lock(TenantId tenant, UUID id) {
    return one(SELECT + " AND t.id = :id FOR UPDATE OF t", params(tenant, id));
  }

  /**
   * A task assigned to the employee, locked {@code FOR UPDATE} (lock order step 5).
   *
   * @param tenant verified tenant
   * @param employeeId the caller's own employee
   * @param id task
   * @return the task, or empty
   */
  public Optional<Task> lockOwn(TenantId tenant, UUID employeeId, UUID id) {
    return one(
        SELECT + " AND t.id = :id AND t.assignee_employee_id = :employee FOR UPDATE OF t",
        params(tenant, id).addValue("employee", employeeId));
  }

  /**
   * Applies one legal transition to a locked task: the projection moves to the next version and the
   * action is appended (the database re-checks the transition, sequence and exact match).
   *
   * @param tenant verified tenant
   * @param before the locked task
   * @param action the action
   * @param at the server instant
   * @param actorSubject verified subject
   * @param actorEmployeeId the assignee for an employee action, {@code null} for an administrator
   * @param note completion note or {@code null}
   * @param reason cancellation reason or {@code null}
   * @param explanation cancellation explanation or {@code null}
   */
  public void transition(
      TenantId tenant,
      Task before,
      TaskActionType action,
      Instant at,
      String actorSubject,
      UUID actorEmployeeId,
      String note,
      TaskCancelReason reason,
      String explanation) {
    TaskState next =
        switch (action) {
          case STARTED -> TaskState.IN_PROGRESS;
          case COMPLETED -> TaskState.COMPLETED;
          case CANCELLED -> TaskState.CANCELLED;
          case CREATED -> throw new IllegalArgumentException("not a transition");
        };
    MapSqlParameterSource params =
        params(tenant, before.id())
            .addValue("version", before.version())
            .addValue("next", next.name())
            .addValue("at", Timestamp.from(at))
            .addValue("note", note)
            .addValue("reason", reason == null ? null : reason.name())
            .addValue("explanation", explanation);
    String column =
        switch (action) {
          case STARTED -> "started_at = :at";
          case COMPLETED -> "completed_at = :at, completion_note = :note";
          case CANCELLED ->
              "cancelled_at = :at, cancel_reason = :reason, cancel_explanation = :explanation";
          case CREATED -> throw new IllegalArgumentException("not a transition");
        };
    int moved =
        jdbc.update(
            "UPDATE operations.task SET state = :next, version = version + 1, "
                + column
                + " WHERE tenant_id = :tenant AND id = :id AND version = :version",
            params);
    if (moved != 1) {
      throw new IllegalStateException("task changed under its lock");
    }
    jdbc.update(
        "INSERT INTO operations.task_action (tenant_id, task_id, seq, action, occurred_at,"
            + " actor_subject, actor_role, actor_employee_id, from_state, to_state,"
            + " completion_note, reason_code, explanation)"
            + " VALUES (:tenant, :id, :seq, :action, :at, :actor, :role, :actorEmployee,"
            + " :from, :next, :note, :reason, :explanation)",
        params
            .addValue("seq", before.version() + 1)
            .addValue("action", action.name())
            .addValue("actor", actorSubject)
            .addValue("role", actorEmployeeId == null ? "tenant-admin" : "employee")
            .addValue("actorEmployee", actorEmployeeId)
            .addValue("from", before.state().name()));
  }

  /**
   * Runs the deferred V41 checks now, so a violation surfaces inside the command's transaction.
   *
   * @param tenant verified tenant whose transaction it is
   */
  public void checkConsistency(TenantId tenant) {
    java.util.Objects.requireNonNull(tenant, "tenant");
    jdbc.getJdbcOperations()
        .execute(
            "SET CONSTRAINTS operations.operations_task_exact,"
                + " operations.operations_task_action_exact IMMEDIATE");
  }

  /**
   * The immutable history of a task, in sequence order (bounded by the legal transitions).
   *
   * @param tenant verified tenant
   * @param id task
   * @return the actions
   */
  public List<TaskAction> history(TenantId tenant, UUID id) {
    return jdbc.query(
        "SELECT seq, action, occurred_at, actor_subject, actor_role, from_state, to_state"
            + " FROM operations.task_action WHERE tenant_id = :tenant AND task_id = :id"
            + " ORDER BY seq LIMIT 8",
        params(tenant, id),
        (rs, n) ->
            new TaskAction(
                rs.getInt("seq"),
                TaskActionType.valueOf(rs.getString("action")),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("actor_subject"),
                rs.getString("actor_role"),
                rs.getString("from_state") == null
                    ? null
                    : TaskState.valueOf(rs.getString("from_state")),
                TaskState.valueOf(rs.getString("to_state"))));
  }

  /**
   * One page in the stable order of D80-8 (unfinished first by due instant, null last, then newest
   * creation, then id; terminal tasks newest terminal action first, then id).
   *
   * @param tenant verified tenant
   * @param employeeId restricts to the employee's own tasks, or {@code null} for all (admin)
   * @param filter filters
   * @param now the server instant (due classification)
   * @param afterId the last task of the previous page, or {@code null}
   * @param limit rows to read (the page size plus one)
   * @return the rows
   */
  public List<Task> page(
      TenantId tenant, UUID employeeId, TaskFilter filter, Instant now, UUID afterId, int limit) {
    MapSqlParameterSource params =
        new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("limit", limit);
    StringBuilder sql = new StringBuilder(SELECT);
    if (employeeId != null) {
      sql.append(" AND t.assignee_employee_id = :employee");
      params.addValue("employee", employeeId);
    }
    if (filter.assigneeId() != null) {
      sql.append(" AND t.assignee_employee_id = :assignee");
      params.addValue("assignee", filter.assigneeId());
    }
    if (filter.state() != null) {
      sql.append(" AND t.state = :state");
      params.addValue("state", filter.state().name());
    }
    if (filter.due() != null) {
      sql.append(" AND t.state IN ('OPEN', 'IN_PROGRESS')");
      Timestamp instant = Timestamp.from(now);
      Timestamp soon = Timestamp.from(now.plus(Duration.ofHours(TaskDueClass.SOON_HOURS)));
      params.addValue("now", instant).addValue("soon", soon);
      sql.append(
          switch (filter.due()) {
            case OVERDUE -> " AND t.due_at < :now";
            case DUE_SOON -> " AND t.due_at >= :now AND t.due_at <= :soon";
            case LATER -> " AND t.due_at > :soon";
            case NONE -> " AND t.due_at IS NULL";
          });
    }
    if (afterId != null) {
      sql.append(
          " AND (t.sort_group, t.sort_key_1, t.sort_key_2, t.id) >"
              + " (SELECT a.sort_group, a.sort_key_1, a.sort_key_2, a.id FROM operations.task a"
              + " WHERE a.tenant_id = :tenant AND a.id = :after)");
      params.addValue("after", afterId);
    }
    sql.append(" ORDER BY t.sort_group, t.sort_key_1, t.sort_key_2, t.id LIMIT :limit");
    return jdbc.query(sql.toString(), params, (rs, n) -> task(rs));
  }

  private Optional<Task> one(String sql, MapSqlParameterSource params) {
    return jdbc.query(sql, params, (rs, n) -> task(rs)).stream().findFirst();
  }

  /**
   * The subject's recent use of a quota: how many and since when (the oldest in the window).
   *
   * @param count how many in the window
   * @param oldest the oldest instant in the window, or {@code null} when none
   */
  public record Usage(int count, Instant oldest) {}

  /**
   * The tasks the subject created since an instant (the per-subject hourly creation quota).
   *
   * @param tenant verified tenant
   * @param subject verified subject
   * @param since window start
   * @return the usage
   */
  public Usage createdSince(TenantId tenant, String subject, Instant since) {
    return jdbc.queryForObject(
        "SELECT count(*) AS n, min(created_at) AS oldest FROM operations.task"
            + " WHERE tenant_id = :tenant AND created_by = :subject AND created_at > :since",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("subject", subject)
            .addValue("since", Timestamp.from(since)),
        (rs, n) -> new Usage(rs.getInt("n"), instant(rs, "oldest")));
  }

  /**
   * The start, complete and cancel actions the subject took since an instant (the per-subject
   * hourly transition quota).
   *
   * @param tenant verified tenant
   * @param subject verified subject
   * @param since window start
   * @return the usage
   */
  public Usage transitionsSince(TenantId tenant, String subject, Instant since) {
    return jdbc.queryForObject(
        "SELECT count(*) AS n, min(occurred_at) AS oldest FROM operations.task_action"
            + " WHERE tenant_id = :tenant AND actor_subject = :subject AND action <> 'CREATED'"
            + " AND occurred_at > :since",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("subject", subject)
            .addValue("since", Timestamp.from(since)),
        (rs, n) -> new Usage(rs.getInt("n"), instant(rs, "oldest")));
  }

  private static MapSqlParameterSource params(TenantId tenant, UUID id) {
    return new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("id", id);
  }

  private static Timestamp timestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static Task task(ResultSet rs) throws SQLException {
    String reason = rs.getString("cancel_reason");
    return new Task(
        rs.getObject("id", UUID.class),
        rs.getObject("assignee_employee_id", UUID.class),
        rs.getString("title"),
        rs.getString("instructions"),
        TaskPriority.valueOf(rs.getString("priority")),
        instant(rs, "due_at"),
        TaskState.valueOf(rs.getString("state")),
        rs.getInt("version"),
        instant(rs, "created_at"),
        rs.getString("created_by"),
        instant(rs, "started_at"),
        instant(rs, "completed_at"),
        instant(rs, "cancelled_at"),
        rs.getString("completion_note"),
        reason == null ? null : TaskCancelReason.valueOf(reason),
        rs.getString("cancel_explanation"));
  }
}
