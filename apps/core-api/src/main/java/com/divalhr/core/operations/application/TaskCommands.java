package com.divalhr.core.operations.application;

import com.divalhr.core.operations.api.CancelTaskRequest;
import com.divalhr.core.operations.api.CompleteTaskRequest;
import com.divalhr.core.operations.api.CreateTaskRequest;
import com.divalhr.core.operations.api.StartTaskRequest;
import com.divalhr.core.operations.domain.TaskCancelReason;
import com.divalhr.core.operations.domain.TaskPriority;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The validated commands of the task lifecycle (MVP-080). Validation is deterministic: it depends
 * on the request alone, so it runs before the idempotency decision; the ownership, version and
 * transition rules run inside the transaction. Problems are {@code {field, constraint}} pairs only:
 * a user-entered value is never echoed, and every {@code toString} shows identifiers only.
 */
public final class TaskCommands {

  private static final Pattern UUID_SHAPE =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  private TaskCommands() {}

  /**
   * The assignment of a task.
   *
   * @param assigneeEmployeeId the assignee
   * @param title title
   * @param instructions instructions or {@code null}
   * @param priority priority
   * @param dueAt due instant or {@code null}
   */
  public record Create(
      UUID assigneeEmployeeId,
      String title,
      String instructions,
      TaskPriority priority,
      Instant dueAt) {

    /**
     * Identifiers only.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "Create[" + assigneeEmployeeId + "]";
    }

    /**
     * Validates the assignment.
     *
     * @param idempotencyKey header
     * @param request body
     * @return the command
     */
    public static Create from(String idempotencyKey, CreateTaskRequest request) {
      FieldErrors errors = new FieldErrors();
      key(errors, idempotencyKey);
      body(errors, request == null ? null : request.unknownProperties(), request);
      UUID assignee = uuid(errors, "assigneeEmployeeId", request.getAssigneeEmployeeId(), true);
      String title = TaskText.parse(errors, "title", request.getTitle(), 1, 120, true);
      String instructions =
          TaskText.parse(errors, "instructions", request.getInstructions(), 1, 1000, false);
      TaskPriority priority =
          enumeration(errors, "priority", request.getPriority(), TaskPriority.class, true);
      Instant dueAt = instant(errors, "dueAt", request.getDueAt());
      errors.throwIfAny();
      return new Create(assignee, title, instructions, priority, dueAt);
    }

    /**
     * The due instant must be after creation and at most 366 days ahead (D80-5); checked for a new
     * execution with the server instant, never for a replay.
     *
     * @param now the server instant
     */
    public void requireDueWithin(Instant now) {
      if (dueAt != null && (!dueAt.isAfter(now) || dueAt.isAfter(now.plus(366, ChronoUnit.DAYS)))) {
        FieldErrors errors = new FieldErrors();
        errors.add("dueAt", Constraint.RANGE);
        errors.throwIfAny();
      }
    }

    /**
     * The normalized command and its scope, fingerprinted for idempotency.
     *
     * @param tenant verified tenant
     * @return key-sorted canonical form
     */
    public Map<String, Object> canonical(TenantId tenant) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("assigneeEmployeeId", assigneeEmployeeId.toString());
      canonical.put("title", title);
      canonical.put("instructions", instructions == null ? "" : instructions);
      canonical.put("priority", priority.name());
      canonical.put("dueAt", dueAt == null ? "" : dueAt.toString());
      return canonical;
    }
  }

  /**
   * The cancellation of an unfinished task.
   *
   * @param taskId task
   * @param expectedVersion the version the administrator saw
   * @param reason closed reason
   * @param explanation explanation of {@code OTHER} or {@code null}
   */
  public record Cancel(
      UUID taskId, int expectedVersion, TaskCancelReason reason, String explanation) {

    /**
     * Identifiers only.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "Cancel[" + taskId + "]";
    }

    /**
     * Validates the cancellation.
     *
     * @param idempotencyKey header
     * @param taskId path value
     * @param request body
     * @return the command
     */
    public static Cancel from(String idempotencyKey, String taskId, CancelTaskRequest request) {
      UUID id = TaskCommands.taskId(taskId);
      FieldErrors errors = new FieldErrors();
      key(errors, idempotencyKey);
      body(errors, request == null ? null : request.unknownProperties(), request);
      Integer version = version(errors, request == null ? null : request.getExpectedVersion());
      TaskCancelReason reason =
          enumeration(errors, "reason", request.getReason(), TaskCancelReason.class, true);
      String explanation = null;
      if (reason == TaskCancelReason.OTHER) {
        explanation =
            TaskText.parse(errors, "explanation", request.getExplanation(), 10, 500, true);
      } else if (reason != null && request.getExplanation() != null) {
        errors.add("explanation", Constraint.FORMAT);
      }
      errors.throwIfAny();
      return new Cancel(id, version, reason, explanation);
    }

    /**
     * The normalized command and its scope.
     *
     * @param tenant verified tenant
     * @return key-sorted canonical form
     */
    public Map<String, Object> canonical(TenantId tenant) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("taskId", taskId.toString());
      canonical.put("expectedVersion", expectedVersion);
      canonical.put("reason", reason.name());
      canonical.put("explanation", explanation == null ? "" : explanation);
      return canonical;
    }
  }

  /**
   * The start of a task by its assignee.
   *
   * @param taskId task
   * @param expectedVersion the version the employee saw
   */
  public record Start(UUID taskId, int expectedVersion) {

    /**
     * Identifiers only.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "Start[" + taskId + "]";
    }

    /**
     * Validates the start.
     *
     * @param idempotencyKey header
     * @param taskId path value
     * @param request body
     * @return the command
     */
    public static Start from(String idempotencyKey, String taskId, StartTaskRequest request) {
      UUID id = TaskCommands.taskId(taskId);
      FieldErrors errors = new FieldErrors();
      key(errors, idempotencyKey);
      body(errors, request == null ? null : request.unknownProperties(), request);
      Integer version = version(errors, request == null ? null : request.getExpectedVersion());
      errors.throwIfAny();
      return new Start(id, version);
    }

    /**
     * The normalized command and its scope.
     *
     * @param tenant verified tenant
     * @return key-sorted canonical form
     */
    public Map<String, Object> canonical(TenantId tenant) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("taskId", taskId.toString());
      canonical.put("expectedVersion", expectedVersion);
      return canonical;
    }
  }

  /**
   * The completion of a task by its assignee.
   *
   * @param taskId task
   * @param expectedVersion the version the employee saw
   * @param note optional note or {@code null}
   */
  public record Complete(UUID taskId, int expectedVersion, String note) {

    /**
     * Identifiers only.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "Complete[" + taskId + "]";
    }

    /**
     * Validates the completion.
     *
     * @param idempotencyKey header
     * @param taskId path value
     * @param request body
     * @return the command
     */
    public static Complete from(String idempotencyKey, String taskId, CompleteTaskRequest request) {
      UUID id = TaskCommands.taskId(taskId);
      FieldErrors errors = new FieldErrors();
      key(errors, idempotencyKey);
      body(errors, request == null ? null : request.unknownProperties(), request);
      Integer version = version(errors, request == null ? null : request.getExpectedVersion());
      String note = TaskText.parse(errors, "note", request.getNote(), 1, 500, false);
      errors.throwIfAny();
      return new Complete(id, version, note);
    }

    /**
     * The normalized command and its scope.
     *
     * @param tenant verified tenant
     * @return key-sorted canonical form
     */
    public Map<String, Object> canonical(TenantId tenant) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("taskId", taskId.toString());
      canonical.put("expectedVersion", expectedVersion);
      canonical.put("note", note == null ? "" : note);
      return canonical;
    }
  }

  /**
   * The task of a path, or the safe {@code 404 TASK_NOT_FOUND} for anything else.
   *
   * @param taskId path value
   * @return the task id
   */
  public static UUID taskId(String taskId) {
    if (taskId == null || !UUID_SHAPE.matcher(taskId).matches()) {
      throw notFound();
    }
    return UUID.fromString(taskId.toLowerCase(Locale.ROOT));
  }

  /**
   * The one answer for a task the caller may not see or change.
   *
   * @return {@code 404 TASK_NOT_FOUND} with empty params
   */
  public static ApiException notFound() {
    return new ApiException(ErrorCode.TASK_NOT_FOUND, Map.of());
  }

  private static void key(FieldErrors errors, String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      errors.add(IdempotencyKeys.HEADER, Constraint.REQUIRED);
    } else if (!IdempotencyKeys.isWellFormed(idempotencyKey)) {
      errors.add(IdempotencyKeys.HEADER, Constraint.FORMAT);
    }
  }

  private static void body(FieldErrors errors, java.util.Set<String> unknown, Object body) {
    if (body == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!unknown.isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
  }

  private static Integer version(FieldErrors errors, Object raw) {
    if (raw == null) {
      errors.add("expectedVersion", Constraint.REQUIRED);
      return 0;
    }
    if (!(raw instanceof Integer || raw instanceof Long)) {
      errors.add("expectedVersion", Constraint.FORMAT);
      return 0;
    }
    long value = ((Number) raw).longValue();
    if (value < 1 || value > Integer.MAX_VALUE) {
      errors.add("expectedVersion", Constraint.RANGE);
      return 0;
    }
    return (int) value;
  }

  private static UUID uuid(FieldErrors errors, String field, Object raw, boolean required) {
    if (raw == null) {
      if (required) {
        errors.add(field, Constraint.REQUIRED);
      }
      return null;
    }
    if (!(raw instanceof String text) || !UUID_SHAPE.matcher(text).matches()) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    return UUID.fromString(text.toLowerCase(Locale.ROOT));
  }

  private static <E extends Enum<E>> E enumeration(
      FieldErrors errors, String field, Object raw, Class<E> type, boolean required) {
    if (raw == null) {
      if (required) {
        errors.add(field, Constraint.REQUIRED);
      }
      return null;
    }
    if (raw instanceof String text) {
      for (E candidate : type.getEnumConstants()) {
        if (candidate.name().equals(text)) {
          return candidate;
        }
      }
    }
    errors.add(field, Constraint.FORMAT);
    return null;
  }

  private static Instant instant(FieldErrors errors, String field, Object raw) {
    if (raw == null) {
      return null;
    }
    if (!(raw instanceof String text)) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    try {
      return OffsetDateTime.parse(text).toInstant().truncatedTo(ChronoUnit.MICROS);
    } catch (DateTimeParseException invalid) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
  }
}
