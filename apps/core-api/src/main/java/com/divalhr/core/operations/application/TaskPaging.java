package com.divalhr.core.operations.application;

import com.divalhr.core.operations.domain.TaskDueClass;
import com.divalhr.core.operations.domain.TaskFilter;
import com.divalhr.core.operations.domain.TaskState;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.pagination.CursorScope;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The list parameters of tasks (MVP-080, D80-8): page size, filters and the cursor's binding. The
 * cursor is bound to the operation, tenant, actor scope (a digest of the subject), every filter and
 * the page size, so it cannot be replayed across tenants, actors, filters or sizes.
 */
final class TaskPaging {

  /** Default page size. */
  static final int DEFAULT_LIMIT = 25;

  /** Largest page size. */
  static final int MAX_LIMIT = 100;

  /** The constant code of a task cursor; the row is looked up by id on decode. */
  static final String CURSOR_CODE = "TK";

  private static final Pattern LIMIT = Pattern.compile("[0-9]{1,4}");
  private static final Pattern UUID_SHAPE =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  private TaskPaging() {}

  /**
   * The page size.
   *
   * @param raw query value or {@code null}
   * @return 1 to 100, default 25
   */
  static int limit(String raw) {
    if (raw == null || raw.isEmpty()) {
      return DEFAULT_LIMIT;
    }
    if (LIMIT.matcher(raw).matches()) {
      int value = Integer.parseInt(raw);
      if (value >= 1 && value <= MAX_LIMIT) {
        return value;
      }
    }
    FieldErrors errors = new FieldErrors();
    errors.add("limit", Constraint.RANGE);
    errors.throwIfAny();
    throw new IllegalStateException("unreachable");
  }

  /**
   * The filters.
   *
   * @param assignee {@code assigneeId} query value, or {@code null}
   * @param state {@code state} query value, or {@code null}
   * @param due {@code due} query value, or {@code null}
   * @return the filter
   */
  static TaskFilter filter(String assignee, String state, String due) {
    FieldErrors errors = new FieldErrors();
    UUID assigneeId = null;
    if (assignee != null) {
      if (UUID_SHAPE.matcher(assignee).matches()) {
        assigneeId = UUID.fromString(assignee.toLowerCase(Locale.ROOT));
      } else {
        errors.add("assigneeId", Constraint.FORMAT);
      }
    }
    TaskState taskState = null;
    if (state != null) {
      try {
        taskState = TaskState.valueOf(state);
      } catch (IllegalArgumentException invalid) {
        errors.add("state", Constraint.FORMAT);
      }
    }
    TaskDueClass dueClass = null;
    if (due != null) {
      try {
        dueClass = TaskDueClass.valueOf(due);
      } catch (IllegalArgumentException invalid) {
        errors.add("due", Constraint.FORMAT);
      }
    }
    errors.throwIfAny();
    return new TaskFilter(assigneeId, taskState, dueClass);
  }

  /**
   * The cursor's binding.
   *
   * @param operation operation name
   * @param caller verified caller
   * @param actorScope {@code admin} or {@code self}
   * @param filter filters
   * @param size page size
   * @return the scope
   */
  static CursorScope scope(
      String operation, OperationsCaller caller, String actorScope, TaskFilter filter, int size) {
    Map<String, String> filters = new TreeMap<>();
    filters.put("subject", Fingerprints.sha256("DIVALHR-TASK-ACTOR\nsubject=" + caller.subject()));
    filters.put("scope", actorScope);
    filters.put("assignee", filter.assigneeId() == null ? "" : filter.assigneeId().toString());
    filters.put("state", filter.state() == null ? "" : filter.state().name());
    filters.put("due", filter.due() == null ? "" : filter.due().name());
    filters.put("limit", Integer.toString(size));
    return new CursorScope(operation, caller.tenant(), filters);
  }

  /**
   * The one answer for a cursor that is not valid for this caller, scope or page.
   *
   * @return {@code 400 CURSOR_INVALID}
   */
  static ApiException invalidCursor() {
    return new ApiException(ErrorCode.CURSOR_INVALID, Map.of());
  }
}
