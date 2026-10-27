package com.divalhr.core.operations.application;

import com.divalhr.core.operations.api.CancelTaskRequest;
import com.divalhr.core.operations.api.CreateTaskRequest;
import com.divalhr.core.operations.api.TaskResponses.DetailView;
import com.divalhr.core.operations.api.TaskResponses.Page;
import com.divalhr.core.operations.api.TaskResponses.Receipt;
import com.divalhr.core.operations.api.TaskResponses.TaskView;
import com.divalhr.core.operations.domain.Task;
import com.divalhr.core.operations.domain.TaskActionType;
import com.divalhr.core.operations.domain.TaskFilter;
import com.divalhr.core.operations.domain.TaskState;
import com.divalhr.core.operations.internal.JdbcTaskRepository;
import com.divalhr.core.operations.internal.TaskConstraintViolations;
import com.divalhr.core.platform.access.EmployeeAccessLinks;
import com.divalhr.core.platform.access.EmploymentContractFacts;
import com.divalhr.core.platform.access.EmploymentContractFacts.ContractFacts;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The administrator's task commands and reads (MVP-080, Issue #167): assign, list, read and cancel.
 *
 * <p>Authorization (role {@code tenant-admin}, exact MFA, verified tenant, active membership) has
 * run before any of this. A creation is one bounded transaction: idempotency reservation (step 0);
 * for a new execution the server instant, the assignee's eligibility (D80-3: same tenant, active
 * employment on the business date, active linked employee membership, one indistinguishable
 * refusal), the insert (the database serializes the 10,000-task limit under a per-tenant lock),
 * then the audit record and the assigned event (step 6). A cancellation locks the task first (step
 * 5), then validates the version and the transition, so a concurrent completion and cancellation of
 * one task produce exactly one terminal action; the loser observes the new version. User-entered
 * text reaches only the task row and the authorized responses.
 */
@Service
public class TaskService {

  /** Create operation (idempotency operation). */
  public static final String CREATE = "task.create";

  /** Cancel operation (idempotency operation). */
  public static final String CANCEL = "task.cancel";

  /** List operation (and disclosure audit action). */
  public static final String LIST = "task.list";

  /** Read operation (and disclosure audit action). */
  public static final String READ = "task.read";

  /** Event of an assignment. */
  public static final String ASSIGNED_EVENT = "operations.task.assigned.v1";

  /** Event of a cancellation. */
  public static final String CANCELLED_EVENT = "operations.task.cancelled.v1";

  /** Per-subject read bucket (60 per minute). */
  public static final String SUBJECT_READ_BUCKET = "task-read";

  /** Per-subject create bucket (60 per hour). */
  public static final String SUBJECT_CREATE_BUCKET = "task-create";

  /** Per-tenant create bucket (300 per hour). */
  public static final String TENANT_CREATE_BUCKET = "task-create";

  /** Per-subject start, complete and cancel bucket (120 per hour, combined). */
  public static final String SUBJECT_TRANSITION_BUCKET = "task-transition";

  /** Per-tenant start, complete and cancel bucket (600 per hour, combined). */
  public static final String TENANT_TRANSITION_BUCKET = "task-transition";

  static final String ADMIN_ROLE = "tenant-admin";

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.operations");
  private static final Duration TRANSACTION_TIMEOUT = Duration.ofSeconds(15);
  private static final IdempotentOperation.Spec CREATE_SPEC =
      new IdempotentOperation.Spec(CREATE, "task", "create", "created", 201);
  private static final IdempotentOperation.Spec CANCEL_SPEC =
      new IdempotentOperation.Spec(CANCEL, "task", "cancel", "cancelled", 200);

  private final IdempotentOperation operations;
  private final JdbcTaskRepository tasks;
  private final EmploymentContractFacts employments;
  private final EmployeeAccessLinks links;
  private final OperationsCalendar calendar;
  private final CursorCodec cursors;
  private final TaskRecorder recorder;
  private final TaskQuotas quotas;
  private final OperationMetrics metrics;
  private final PlatformTransactionManager transactionManager;

  /**
   * Creates the service.
   *
   * @param operations shared idempotent command flow
   * @param tasks task storage
   * @param employments employment facts port (implemented by people)
   * @param links employee access port (implemented by identity)
   * @param calendar server instant and business date
   * @param cursors signed cursors
   * @param recorder audit and event evidence
   * @param quotas durable hourly per-subject quotas
   * @param metrics operation metrics
   * @param transactionManager transaction manager
   */
  TaskService(
      IdempotentOperation operations,
      JdbcTaskRepository tasks,
      EmploymentContractFacts employments,
      EmployeeAccessLinks links,
      OperationsCalendar calendar,
      CursorCodec cursors,
      TaskRecorder recorder,
      TaskQuotas quotas,
      OperationMetrics metrics,
      PlatformTransactionManager transactionManager) {
    this.operations = operations;
    this.tasks = tasks;
    this.employments = employments;
    this.links = links;
    this.calendar = calendar;
    this.cursors = cursors;
    this.recorder = recorder;
    this.quotas = quotas;
    this.metrics = metrics;
    this.transactionManager = transactionManager;
  }

  // ------------------------------------------------------------------------------------------
  // Create
  // ------------------------------------------------------------------------------------------

  /**
   * Assigns a task to one eligible employee, or replays an earlier identical assignment.
   *
   * @param caller verified administrator
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body request
   * @return the receipt
   */
  public IdempotentOperation.Result<Receipt> create(
      OperationsCaller caller, String idempotencyKey, CreateTaskRequest body) {
    TaskCommands.Create command =
        operations.validated(CREATE_SPEC, () -> TaskCommands.Create.from(idempotencyKey, body));
    return operations.execute(
        CREATE_SPEC,
        caller.subject(),
        idempotencyKey,
        command.canonical(caller.tenant()),
        Receipt.class,
        TRANSACTION_TIMEOUT,
        taskId -> {},
        () -> created(caller, command));
  }

  private IdempotentOperation.Completed<Receipt> created(
      OperationsCaller caller, TaskCommands.Create command) {
    TenantId tenant = caller.tenant();
    Instant now = calendar.now();
    command.requireDueWithin(now);
    quotas.requireCreate(tenant, caller.subject(), now);
    requireEligible(tenant, command.assigneeEmployeeId());
    Task task =
        new Task(
            UUID.randomUUID(),
            command.assigneeEmployeeId(),
            command.title(),
            command.instructions(),
            command.priority(),
            command.dueAt(),
            TaskState.OPEN,
            1,
            now,
            caller.subject(),
            null,
            null,
            null,
            null,
            null,
            null);
    try {
      tasks.insert(tenant, task);
      tasks.checkConsistency(tenant);
    } catch (DataIntegrityViolationException violated) {
      Optional<String> constraint = TaskConstraintViolations.constraint(violated);
      if (constraint.filter(TaskConstraintViolations.OPEN_LIMIT::equals).isPresent()) {
        throw new ApiException(ErrorCode.TASK_LIMIT_REACHED, Map.of());
      }
      if (constraint.filter(TaskConstraintViolations.ASSIGNEE::equals).isPresent()) {
        throw assigneeNotFound();
      }
      throw violated;
    }
    recorder.command(caller, CREATE, ASSIGNED_EVENT, null, task, ADMIN_ROLE, now);
    LOG.atInfo()
        .addKeyValue("operation", CREATE)
        .addKeyValue("priority", task.priority().name())
        .addKeyValue("outcome", "created")
        .log("task_created");
    return new IdempotentOperation.Completed<>(
        new Receipt(task.id(), task.state(), task.version(), now), task.id(), Outcome.CREATED);
  }

  /**
   * The assignee must be an employee of the tenant with an active employment on the business date
   * and an active, linked employee membership; any failure is the same {@code 404
   * TASK_ASSIGNEE_NOT_FOUND}, without saying which condition failed (D80-3).
   */
  private void requireEligible(TenantId tenant, UUID employeeId) {
    LocalDate today = calendar.today(tenant);
    Optional<ContractFacts> facts = employments.read(tenant, employeeId, today);
    boolean employed =
        facts
            .filter(f -> !f.employmentStart().isAfter(today))
            .filter(f -> f.employmentEnd() == null || !f.employmentEnd().isBefore(today))
            .isPresent();
    if (!employed || !links.activeEmployee(tenant, employeeId)) {
      throw assigneeNotFound();
    }
  }

  private static ApiException assigneeNotFound() {
    return new ApiException(ErrorCode.TASK_ASSIGNEE_NOT_FOUND, Map.of());
  }

  // ------------------------------------------------------------------------------------------
  // Cancel
  // ------------------------------------------------------------------------------------------

  /**
   * Cancels an unfinished task, or replays an earlier identical cancellation.
   *
   * @param caller verified administrator
   * @param taskId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body expected version, reason and explanation
   * @return the receipt
   */
  public IdempotentOperation.Result<Receipt> cancel(
      OperationsCaller caller, String taskId, String idempotencyKey, CancelTaskRequest body) {
    TaskCommands.Cancel command =
        operations.validated(
            CANCEL_SPEC, () -> TaskCommands.Cancel.from(idempotencyKey, taskId, body));
    return operations.execute(
        CANCEL_SPEC,
        caller.subject(),
        idempotencyKey,
        command.canonical(caller.tenant()),
        Receipt.class,
        TRANSACTION_TIMEOUT,
        resource -> {
          if (resource == null || tasks.find(caller.tenant(), resource).isEmpty()) {
            throw TaskCommands.notFound();
          }
        },
        () -> cancelled(caller, command));
  }

  private IdempotentOperation.Completed<Receipt> cancelled(
      OperationsCaller caller, TaskCommands.Cancel command) {
    TenantId tenant = caller.tenant();
    Task before = tasks.lock(tenant, command.taskId()).orElseThrow(TaskCommands::notFound);
    if (before.version() != command.expectedVersion()) {
      throw new ApiException(ErrorCode.TASK_VERSION_CONFLICT, Map.of());
    }
    if (before.state().terminal()) {
      throw new ApiException(ErrorCode.TASK_TRANSITION_NOT_ALLOWED, Map.of());
    }
    Instant now = calendar.now();
    quotas.requireTransition(tenant, caller.subject(), now);
    Task after;
    try {
      tasks.transition(
          tenant,
          before,
          TaskActionType.CANCELLED,
          now,
          caller.subject(),
          null,
          null,
          command.reason(),
          command.explanation());
      tasks.checkConsistency(tenant);
      after = tasks.find(tenant, before.id()).orElseThrow();
    } catch (DataIntegrityViolationException violated) {
      throw TaskTransitions.inconsistent(violated);
    }
    recorder.command(caller, CANCEL, CANCELLED_EVENT, before, after, ADMIN_ROLE, now);
    LOG.atInfo()
        .addKeyValue("operation", CANCEL)
        .addKeyValue("reason", command.reason().name())
        .addKeyValue("outcome", "cancelled")
        .log("task_cancelled");
    return new IdempotentOperation.Completed<>(
        new Receipt(after.id(), after.state(), after.version(), now), after.id(), Outcome.UPDATED);
  }

  // ------------------------------------------------------------------------------------------
  // Reads
  // ------------------------------------------------------------------------------------------

  /**
   * One page of the tenant's tasks in the stable order of D80-8.
   *
   * @param caller verified administrator
   * @param cursor opaque cursor or {@code null}
   * @param limit page size (1 to 100, default 25)
   * @param assigneeId assignee filter or {@code null}
   * @param state state filter or {@code null}
   * @param due due classification filter or {@code null}
   * @return the page, after its disclosure audit committed
   */
  public Page list(
      OperationsCaller caller,
      String cursor,
      String limit,
      String assigneeId,
      String state,
      String due) {
    return recorded(
        LIST,
        () -> {
          TenantId tenant = caller.tenant();
          int size = TaskPaging.limit(limit);
          TaskFilter filter = TaskPaging.filter(assigneeId, state, due);
          CursorScope scope = TaskPaging.scope(LIST, caller, "admin", filter, size);
          KeysetPosition after = cursor == null ? null : cursors.decode(cursor, scope);
          if (after != null && !TaskPaging.CURSOR_CODE.equals(after.code())) {
            throw TaskPaging.invalidCursor();
          }
          String page = after == null ? "first" : "next";
          return TaskTransitions.read(
              transactionManager,
              () -> {
                if (after != null && tasks.find(tenant, after.id()).isEmpty()) {
                  throw TaskPaging.invalidCursor();
                }
                Instant now = calendar.now();
                List<Task> rows =
                    tasks.page(
                        tenant, null, filter, now, after == null ? null : after.id(), size + 1);
                List<Task> shown = rows.subList(0, Math.min(rows.size(), size));
                String next =
                    rows.size() > size
                        ? cursors.encode(
                            scope,
                            new KeysetPosition(
                                TaskPaging.CURSOR_CODE, shown.get(shown.size() - 1).id()))
                        : null;
                boolean filtered =
                    filter.assigneeId() != null || filter.state() != null || filter.due() != null;
                recorder.listed(
                    caller,
                    LIST,
                    "admin",
                    "organization",
                    tenant.value(),
                    page,
                    shown,
                    filtered,
                    now);
                LOG.atInfo()
                    .addKeyValue("operation", LIST)
                    .addKeyValue("page", page)
                    .addKeyValue("resultCount", shown.size())
                    .addKeyValue("outcome", "listed")
                    .log("task_listed");
                return new Page(
                    shown.stream().map(task -> TaskView.of(task, now)).toList(), next, now);
              });
        });
  }

  /**
   * One task with its history.
   *
   * @param caller verified administrator
   * @param taskId path value
   * @return the detail, after its disclosure audit committed
   */
  public DetailView read(OperationsCaller caller, String taskId) {
    return recorded(
        READ,
        () -> {
          UUID id = TaskCommands.taskId(taskId);
          return TaskTransitions.read(
              transactionManager,
              () -> {
                Task task = tasks.find(caller.tenant(), id).orElseThrow(TaskCommands::notFound);
                Instant now = calendar.now();
                recorder.read(caller, READ, "admin", task, now);
                LOG.atInfo()
                    .addKeyValue("operation", READ)
                    .addKeyValue("outcome", "read")
                    .log("task_read");
                return DetailView.of(task, tasks.history(caller.tenant(), id), now);
              });
        });
  }

  /** Records a read's outcome on the operation metric. */
  private <T> T recorded(String operation, Supplier<T> work) {
    try {
      T result = work.get();
      metrics.record(operation, Outcome.LISTED);
      return result;
    } catch (ApiException rejected) {
      Outcome outcome =
          rejected.code() == ErrorCode.TASK_NOT_FOUND
              ? Outcome.NOT_FOUND
              : Outcome.VALIDATION_FAILED;
      metrics.record(operation, outcome);
      LOG.atInfo()
          .addKeyValue("operation", operation)
          .addKeyValue("outcome", outcome.name().toLowerCase(Locale.ROOT))
          .addKeyValue("code", rejected.code().name())
          .log("task_rejected");
      throw rejected;
    } catch (RuntimeException failure) {
      metrics.record(operation, Outcome.FAILURE);
      throw failure;
    }
  }
}
