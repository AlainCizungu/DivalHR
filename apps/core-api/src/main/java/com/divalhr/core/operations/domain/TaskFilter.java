package com.divalhr.core.operations.domain;

import java.util.UUID;

/**
 * The filters of a task list (MVP-080, D80-8). All optional; a due classification applies to
 * unfinished tasks only.
 *
 * @param assigneeId assignee filter (administrators only) or {@code null}
 * @param state state filter or {@code null}
 * @param due due classification filter or {@code null}
 */
public record TaskFilter(UUID assigneeId, TaskState state, TaskDueClass due) {}
