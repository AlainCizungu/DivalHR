package com.divalhr.core.operations.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * The current projection of a task (MVP-080). Title, instructions, completion note and cancellation
 * explanation are Confidential tenant data: {@code toString} never shows them.
 *
 * @param id task
 * @param assigneeEmployeeId the one assignee (immutable)
 * @param title restricted plain text
 * @param instructions restricted plain text or {@code null}
 * @param priority priority
 * @param dueAt due instant or {@code null}
 * @param state state
 * @param version optimistic version
 * @param createdAt creation instant
 * @param createdBy administrator subject
 * @param startedAt start instant or {@code null}
 * @param completedAt completion instant or {@code null}
 * @param cancelledAt cancellation instant or {@code null}
 * @param completionNote optional note or {@code null}
 * @param cancelReason closed reason or {@code null}
 * @param cancelExplanation explanation of {@code OTHER} or {@code null}
 */
public record Task(
    UUID id,
    UUID assigneeEmployeeId,
    String title,
    String instructions,
    TaskPriority priority,
    Instant dueAt,
    TaskState state,
    int version,
    Instant createdAt,
    String createdBy,
    Instant startedAt,
    Instant completedAt,
    Instant cancelledAt,
    String completionNote,
    TaskCancelReason cancelReason,
    String cancelExplanation) {

  /**
   * Identifiers only.
   *
   * @return safe text
   */
  @Override
  public String toString() {
    return "Task[" + id + "]";
  }

  /**
   * The due classification at an instant; {@code null} for a finished task.
   *
   * @param now the server instant
   * @return the classification, or {@code null} when the task is terminal
   */
  public TaskDueClass dueClass(Instant now) {
    if (state.terminal()) {
      return null;
    }
    return classify(dueAt, now);
  }

  /**
   * Classifies a due instant of an unfinished task.
   *
   * @param dueAt due instant or {@code null}
   * @param now server instant
   * @return the classification
   */
  public static TaskDueClass classify(Instant dueAt, Instant now) {
    if (dueAt == null) {
      return TaskDueClass.NONE;
    }
    if (now.isAfter(dueAt)) {
      return TaskDueClass.OVERDUE;
    }
    return dueAt.isAfter(now.plus(Duration.ofHours(TaskDueClass.SOON_HOURS)))
        ? TaskDueClass.LATER
        : TaskDueClass.DUE_SOON;
  }
}
