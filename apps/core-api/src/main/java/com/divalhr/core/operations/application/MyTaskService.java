package com.divalhr.core.operations.application;

import com.divalhr.core.operations.api.CompleteTaskRequest;
import com.divalhr.core.operations.api.StartTaskRequest;
import com.divalhr.core.operations.api.TaskResponses.MyDetailView;
import com.divalhr.core.operations.api.TaskResponses.MyPage;
import com.divalhr.core.operations.api.TaskResponses.MyTaskView;
import com.divalhr.core.operations.api.TaskResponses.Receipt;
import com.divalhr.core.operations.domain.Task;
import com.divalhr.core.operations.domain.TaskActionType;
import com.divalhr.core.operations.domain.TaskFilter;
import com.divalhr.core.operations.domain.TaskState;
import com.divalhr.core.operations.internal.JdbcTaskRepository;
import com.divalhr.core.platform.access.EmployeeAccessLinks;
import com.divalhr.core.platform.access.EmployeeAccessLinks.SelfLink;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.security.ScopeAuthorizationInterceptor;
import com.divalhr.core.platform.tenancy.TenantId;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The assignee's own tasks (MVP-080, Issue #167): list, read, start and complete.
 *
 * <p>Authorization (role {@code employee}, verified tenant, active membership) has run before any
 * of this. Every operation then resolves the caller's own employee through the identity port's
 * active link ({@code FOR SHARE}, lock order step 4, ADR 0008): loss of the link or the membership
 * fails closed ({@code 403 EMPLOYEE_LINK_REQUIRED}, counted on the bounded self-service counter,
 * never durable privileged-denial evidence). No request names an employee: a task that is unknown,
 * foreign or assigned to someone else is the same safe {@code 404 TASK_NOT_FOUND}. A command locks
 * the task row (step 5) before validating the version and the transition; projection, action, audit
 * and outbox event commit atomically.
 */
@Service
public class MyTaskService {

  /** Start operation (idempotency operation). */
  public static final String START = "task.self-start";

  /** Complete operation (idempotency operation). */
  public static final String COMPLETE = "task.self-complete";

  /** List operation. */
  public static final String LIST = "task.self-list";

  /** Read operation. */
  public static final String READ = "task.self-read";

  /** Audit action of a start. */
  public static final String START_AUDIT = "task.start";

  /** Audit action of a completion. */
  public static final String COMPLETE_AUDIT = "task.complete";

  /** Event of a start. */
  public static final String STARTED_EVENT = "operations.task.started.v1";

  /** Event of a completion. */
  public static final String COMPLETED_EVENT = "operations.task.completed.v1";

  /** Per-subject self-service read bucket (60 per minute). */
  public static final String SUBJECT_READ_BUCKET = "task-self-read";

  private static final String EMPLOYEE_ROLE = "employee";
  private static final Logger LOG = LoggerFactory.getLogger("divalhr.operations");
  private static final IdempotentOperation.Spec START_SPEC =
      new IdempotentOperation.Spec(START, "task", "start", "started", 200);
  private static final IdempotentOperation.Spec COMPLETE_SPEC =
      new IdempotentOperation.Spec(COMPLETE, "task", "complete", "completed", 200);

  private final IdempotentOperation operations;
  private final JdbcTaskRepository tasks;
  private final EmployeeAccessLinks links;
  private final OperationsCalendar calendar;
  private final CursorCodec cursors;
  private final TaskRecorder recorder;
  private final TaskQuotas quotas;
  private final OperationMetrics metrics;
  private final MeterRegistry meters;
  private final PlatformTransactionManager transactionManager;

  /**
   * Creates the service.
   *
   * @param operations shared idempotent command flow
   * @param tasks task storage
   * @param links the identity port (joins this module's transactions)
   * @param calendar server instant
   * @param cursors signed cursors
   * @param recorder audit and event evidence
   * @param quotas durable hourly per-subject quotas
   * @param metrics operation metrics
   * @param meters meter registry (self-service denial counter)
   * @param transactionManager transaction manager
   */
  MyTaskService(
      IdempotentOperation operations,
      JdbcTaskRepository tasks,
      EmployeeAccessLinks links,
      OperationsCalendar calendar,
      CursorCodec cursors,
      TaskRecorder recorder,
      TaskQuotas quotas,
      OperationMetrics metrics,
      MeterRegistry meters,
      PlatformTransactionManager transactionManager) {
    this.operations = operations;
    this.tasks = tasks;
    this.links = links;
    this.calendar = calendar;
    this.cursors = cursors;
    this.recorder = recorder;
    this.quotas = quotas;
    this.metrics = metrics;
    this.meters = meters;
    this.transactionManager = transactionManager;
  }

  // ------------------------------------------------------------------------------------------
  // Start and complete
  // ------------------------------------------------------------------------------------------

  /**
   * Starts the caller's own open task, or replays an earlier identical start while the task is
   * still the caller's.
   *
   * @param caller verified employee
   * @param taskId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body expected version
   * @return the receipt
   */
  public IdempotentOperation.Result<Receipt> start(
      OperationsCaller caller, String taskId, String idempotencyKey, StartTaskRequest body) {
    TaskCommands.Start command =
        operations.validated(
            START_SPEC, () -> TaskCommands.Start.from(idempotencyKey, taskId, body));
    return counted(
        START,
        () ->
            operations.execute(
                START_SPEC,
                caller.subject(),
                idempotencyKey,
                command.canonical(caller.tenant()),
                Receipt.class,
                TaskTransitions.TIMEOUT,
                resource -> replayable(caller, resource),
                () ->
                    transition(
                        caller,
                        command.taskId(),
                        command.expectedVersion(),
                        TaskActionType.STARTED,
                        null)));
  }

  /**
   * Completes the caller's own open or in-progress task, or replays an earlier identical completion
   * while the task is still the caller's.
   *
   * @param caller verified employee
   * @param taskId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body expected version and optional note
   * @return the receipt
   */
  public IdempotentOperation.Result<Receipt> complete(
      OperationsCaller caller, String taskId, String idempotencyKey, CompleteTaskRequest body) {
    TaskCommands.Complete command =
        operations.validated(
            COMPLETE_SPEC, () -> TaskCommands.Complete.from(idempotencyKey, taskId, body));
    return counted(
        COMPLETE,
        () ->
            operations.execute(
                COMPLETE_SPEC,
                caller.subject(),
                idempotencyKey,
                command.canonical(caller.tenant()),
                Receipt.class,
                TaskTransitions.TIMEOUT,
                resource -> replayable(caller, resource),
                () ->
                    transition(
                        caller,
                        command.taskId(),
                        command.expectedVersion(),
                        TaskActionType.COMPLETED,
                        command.note())));
  }

  /**
   * A stored receipt is returned only to the employee the task is assigned to, through the caller's
   * current active link: no link is {@code 403 EMPLOYEE_LINK_REQUIRED}; otherwise {@code 404
   * TASK_NOT_FOUND}, with no stored response or identifier.
   */
  private void replayable(OperationsCaller caller, java.util.UUID taskId) {
    SelfLink link = link(caller);
    if (taskId == null || tasks.findOwn(caller.tenant(), link.employeeId(), taskId).isEmpty()) {
      throw TaskCommands.notFound();
    }
  }

  private IdempotentOperation.Completed<Receipt> transition(
      OperationsCaller caller,
      java.util.UUID taskId,
      int expectedVersion,
      TaskActionType action,
      String note) {
    TenantId tenant = caller.tenant();
    // The link is checked before anything about the task is answered (lock order step 4).
    SelfLink link = link(caller);
    Task before =
        tasks.lockOwn(tenant, link.employeeId(), taskId).orElseThrow(TaskCommands::notFound);
    if (before.version() != expectedVersion) {
      throw new ApiException(ErrorCode.TASK_VERSION_CONFLICT, Map.of());
    }
    boolean legal =
        action == TaskActionType.STARTED
            ? before.state() == TaskState.OPEN
            : before.state().unfinished();
    if (!legal) {
      throw new ApiException(ErrorCode.TASK_TRANSITION_NOT_ALLOWED, Map.of());
    }
    Instant now = calendar.now();
    quotas.requireTransition(tenant, caller.subject(), now);
    Task after;
    try {
      tasks.transition(
          tenant, before, action, now, caller.subject(), link.employeeId(), note, null, null);
      tasks.checkConsistency(tenant);
      after = tasks.find(tenant, before.id()).orElseThrow();
    } catch (DataIntegrityViolationException violated) {
      throw TaskTransitions.inconsistent(violated);
    }
    boolean started = action == TaskActionType.STARTED;
    recorder.command(
        caller,
        started ? START_AUDIT : COMPLETE_AUDIT,
        started ? STARTED_EVENT : COMPLETED_EVENT,
        before,
        after,
        EMPLOYEE_ROLE,
        now);
    LOG.atInfo()
        .addKeyValue("operation", started ? START : COMPLETE)
        .addKeyValue("outcome", started ? "started" : "completed")
        .log(started ? "task_started" : "task_completed");
    return new IdempotentOperation.Completed<>(
        new Receipt(after.id(), after.state(), after.version(), now), after.id(), Outcome.UPDATED);
  }

  // ------------------------------------------------------------------------------------------
  // Reads
  // ------------------------------------------------------------------------------------------

  /**
   * One page of the caller's own tasks in the stable order of D80-8.
   *
   * @param caller verified employee
   * @param cursor opaque cursor or {@code null}
   * @param limit page size (1 to 100, default 25)
   * @param state state filter or {@code null}
   * @param due due classification filter or {@code null}
   * @return the page, after its disclosure audit committed
   */
  public MyPage list(
      OperationsCaller caller, String cursor, String limit, String state, String due) {
    return recorded(
        LIST,
        () -> {
          TenantId tenant = caller.tenant();
          int size = TaskPaging.limit(limit);
          TaskFilter filter = TaskPaging.filter(null, state, due);
          CursorScope scope = TaskPaging.scope(LIST, caller, "self", filter, size);
          KeysetPosition after = cursor == null ? null : cursors.decode(cursor, scope);
          if (after != null && !TaskPaging.CURSOR_CODE.equals(after.code())) {
            throw TaskPaging.invalidCursor();
          }
          String page = after == null ? "first" : "next";
          return TaskTransitions.read(
              transactionManager,
              () -> {
                SelfLink link = link(caller);
                if (after != null
                    && tasks.findOwn(tenant, link.employeeId(), after.id()).isEmpty()) {
                  throw TaskPaging.invalidCursor();
                }
                Instant now = calendar.now();
                List<Task> rows =
                    tasks.page(
                        tenant,
                        link.employeeId(),
                        filter,
                        now,
                        after == null ? null : after.id(),
                        size + 1);
                List<Task> shown = rows.subList(0, Math.min(rows.size(), size));
                String next =
                    rows.size() > size
                        ? cursors.encode(
                            scope,
                            new KeysetPosition(
                                TaskPaging.CURSOR_CODE, shown.get(shown.size() - 1).id()))
                        : null;
                boolean filtered = filter.state() != null || filter.due() != null;
                recorder.listed(
                    caller,
                    "task.list",
                    "self",
                    "employee",
                    link.employeeId(),
                    page,
                    shown,
                    filtered,
                    now);
                LOG.atInfo()
                    .addKeyValue("operation", LIST)
                    .addKeyValue("page", page)
                    .addKeyValue("resultCount", shown.size())
                    .addKeyValue("outcome", "listed")
                    .log("task_self_listed");
                return new MyPage(
                    shown.stream().map(task -> MyTaskView.of(task, now)).toList(), next, now);
              });
        });
  }

  /**
   * One of the caller's own tasks with its history.
   *
   * @param caller verified employee
   * @param taskId path value
   * @return the detail, after its disclosure audit committed
   */
  public MyDetailView read(OperationsCaller caller, String taskId) {
    return recorded(
        READ,
        () -> {
          java.util.UUID id = TaskCommands.taskId(taskId);
          return TaskTransitions.read(
              transactionManager,
              () -> {
                SelfLink link = link(caller);
                Task task =
                    tasks
                        .findOwn(caller.tenant(), link.employeeId(), id)
                        .orElseThrow(TaskCommands::notFound);
                Instant now = calendar.now();
                recorder.read(caller, "task.read", "self", task, now);
                LOG.atInfo()
                    .addKeyValue("operation", READ)
                    .addKeyValue("outcome", "read")
                    .log("task_self_read");
                return MyDetailView.of(task, tasks.history(caller.tenant(), id), now);
              });
        });
  }

  // ------------------------------------------------------------------------------------------
  // Shared
  // ------------------------------------------------------------------------------------------

  /** The caller's own active link ({@code FOR SHARE}), or {@code 403 EMPLOYEE_LINK_REQUIRED}. */
  private SelfLink link(OperationsCaller caller) {
    return links
        .linkedEmployee(caller.tenant(), caller.subject())
        .orElseThrow(() -> new ApiException(ErrorCode.EMPLOYEE_LINK_REQUIRED, Map.of()));
  }

  /** Records a read's outcome, counting link denials on the self-service counter. */
  private <T> T recorded(String operation, Supplier<T> work) {
    try {
      T result = counted(operation, work);
      metrics.record(operation, Outcome.LISTED);
      return result;
    } catch (ApiException rejected) {
      Outcome outcome =
          switch (rejected.code()) {
            case EMPLOYEE_LINK_REQUIRED -> Outcome.DENIED;
            case TASK_NOT_FOUND -> Outcome.NOT_FOUND;
            default -> Outcome.VALIDATION_FAILED;
          };
      metrics.record(operation, outcome);
      LOG.atInfo()
          .addKeyValue("operation", operation)
          .addKeyValue("outcome", outcome.name().toLowerCase(Locale.ROOT))
          .addKeyValue("code", rejected.code().name())
          .log("task_self_rejected");
      throw rejected;
    } catch (RuntimeException failure) {
      metrics.record(operation, Outcome.FAILURE);
      throw failure;
    }
  }

  /** Counts the self-service denials on the bounded counter only: no link, and no such task. */
  private <T> T counted(String operation, Supplier<T> work) {
    try {
      return work.get();
    } catch (ApiException denied) {
      if (denied.code() == ErrorCode.EMPLOYEE_LINK_REQUIRED) {
        ScopeAuthorizationInterceptor.selfServiceDenied(meters, operation, "link_required");
      } else if (denied.code() == ErrorCode.TASK_NOT_FOUND) {
        ScopeAuthorizationInterceptor.selfServiceDenied(meters, operation, "not_found");
      }
      throw denied;
    }
  }
}
