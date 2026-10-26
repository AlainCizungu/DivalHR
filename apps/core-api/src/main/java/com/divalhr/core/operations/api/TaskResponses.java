package com.divalhr.core.operations.api;

import com.divalhr.core.operations.domain.Task;
import com.divalhr.core.operations.domain.TaskAction;
import com.divalhr.core.operations.domain.TaskActionType;
import com.divalhr.core.operations.domain.TaskCancelReason;
import com.divalhr.core.operations.domain.TaskDueClass;
import com.divalhr.core.operations.domain.TaskPriority;
import com.divalhr.core.operations.domain.TaskState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Task responses (MVP-080). The administrator's views carry the assignee and the actor subjects;
 * the employee's views never carry another person's identifiers. User-entered text appears only
 * here, in authorized responses.
 */
public final class TaskResponses {

  private TaskResponses() {}

  /**
   * The administrator's view of a task.
   *
   * @param id task
   * @param assigneeEmployeeId assignee
   * @param title title
   * @param instructions instructions or {@code null}
   * @param priority priority
   * @param dueAt due instant or {@code null}
   * @param dueClass derived classification, {@code null} when finished
   * @param state state
   * @param version version
   * @param createdAt creation instant
   * @param startedAt start instant or {@code null}
   * @param completedAt completion instant or {@code null}
   * @param cancelledAt cancellation instant or {@code null}
   * @param completionNote note or {@code null}
   * @param cancelReason reason or {@code null}
   * @param cancelExplanation explanation or {@code null}
   */
  @Schema(name = "Task")
  public record TaskView(
      UUID id,
      UUID assigneeEmployeeId,
      String title,
      String instructions,
      TaskPriority priority,
      Instant dueAt,
      TaskDueClass dueClass,
      TaskState state,
      int version,
      Instant createdAt,
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
     * The view of a task at an instant.
     *
     * @param task task
     * @param now server instant
     * @return the view
     */
    public static TaskView of(Task task, Instant now) {
      return new TaskView(
          task.id(),
          task.assigneeEmployeeId(),
          task.title(),
          task.instructions(),
          task.priority(),
          task.dueAt(),
          task.dueClass(now),
          task.state(),
          task.version(),
          task.createdAt(),
          task.startedAt(),
          task.completedAt(),
          task.cancelledAt(),
          task.completionNote(),
          task.cancelReason(),
          task.cancelExplanation());
    }
  }

  /**
   * The employee's view of their own task.
   *
   * @param id task
   * @param title title
   * @param instructions instructions or {@code null}
   * @param priority priority
   * @param dueAt due instant or {@code null}
   * @param dueClass derived classification, {@code null} when finished
   * @param state state
   * @param version version
   * @param createdAt creation instant
   * @param startedAt start instant or {@code null}
   * @param completedAt completion instant or {@code null}
   * @param cancelledAt cancellation instant or {@code null}
   * @param completionNote note or {@code null}
   * @param cancelReason reason or {@code null}
   * @param cancelExplanation explanation or {@code null}
   */
  @Schema(name = "MyTask")
  public record MyTaskView(
      UUID id,
      String title,
      String instructions,
      TaskPriority priority,
      Instant dueAt,
      TaskDueClass dueClass,
      TaskState state,
      int version,
      Instant createdAt,
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
      return "MyTask[" + id + "]";
    }

    /**
     * The view of a task at an instant.
     *
     * @param task task
     * @param now server instant
     * @return the view
     */
    public static MyTaskView of(Task task, Instant now) {
      return new MyTaskView(
          task.id(),
          task.title(),
          task.instructions(),
          task.priority(),
          task.dueAt(),
          task.dueClass(now),
          task.state(),
          task.version(),
          task.createdAt(),
          task.startedAt(),
          task.completedAt(),
          task.cancelledAt(),
          task.completionNote(),
          task.cancelReason(),
          task.cancelExplanation());
    }
  }

  /**
   * One action of the history, as the administrator sees it.
   *
   * @param seq sequence
   * @param action action
   * @param occurredAt instant
   * @param actorRole {@code tenant-admin} or {@code employee}
   * @param actorSubject verified subject of the actor
   * @param fromState state before or {@code null}
   * @param toState state after
   */
  @Schema(name = "TaskAction")
  public record ActionView(
      int seq,
      TaskActionType action,
      Instant occurredAt,
      String actorRole,
      String actorSubject,
      TaskState fromState,
      TaskState toState) {

    /**
     * The view of an action.
     *
     * @param action action
     * @return the view
     */
    public static ActionView of(TaskAction action) {
      return new ActionView(
          action.seq(),
          action.action(),
          action.occurredAt(),
          action.actorRole(),
          action.actorSubject(),
          action.fromState(),
          action.toState());
    }
  }

  /**
   * One action of the history, as the assignee sees it (no actor identifier).
   *
   * @param seq sequence
   * @param action action
   * @param occurredAt instant
   * @param actorRole {@code tenant-admin} or {@code employee}
   * @param fromState state before or {@code null}
   * @param toState state after
   */
  @Schema(name = "MyTaskAction")
  public record MyActionView(
      int seq,
      TaskActionType action,
      Instant occurredAt,
      String actorRole,
      TaskState fromState,
      TaskState toState) {

    /**
     * The view of an action.
     *
     * @param action action
     * @return the view
     */
    public static MyActionView of(TaskAction action) {
      return new MyActionView(
          action.seq(),
          action.action(),
          action.occurredAt(),
          action.actorRole(),
          action.fromState(),
          action.toState());
    }
  }

  /**
   * A task with its history (administrator).
   *
   * @param id task
   * @param assigneeEmployeeId assignee
   * @param title title
   * @param instructions instructions or {@code null}
   * @param priority priority
   * @param dueAt due instant or {@code null}
   * @param dueClass derived classification
   * @param state state
   * @param version version
   * @param createdAt creation instant
   * @param startedAt start instant or {@code null}
   * @param completedAt completion instant or {@code null}
   * @param cancelledAt cancellation instant or {@code null}
   * @param completionNote note or {@code null}
   * @param cancelReason reason or {@code null}
   * @param cancelExplanation explanation or {@code null}
   * @param history the actions in sequence order
   */
  @Schema(name = "TaskDetail")
  public record DetailView(
      UUID id,
      UUID assigneeEmployeeId,
      String title,
      String instructions,
      TaskPriority priority,
      Instant dueAt,
      TaskDueClass dueClass,
      TaskState state,
      int version,
      Instant createdAt,
      Instant startedAt,
      Instant completedAt,
      Instant cancelledAt,
      String completionNote,
      TaskCancelReason cancelReason,
      String cancelExplanation,
      List<ActionView> history) {

    /**
     * Identifiers only.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "TaskDetail[" + id + "]";
    }

    /**
     * The detail of a task at an instant.
     *
     * @param task task
     * @param history its actions
     * @param now server instant
     * @return the view
     */
    public static DetailView of(Task task, List<TaskAction> history, Instant now) {
      TaskView view = TaskView.of(task, now);
      return new DetailView(
          view.id(),
          view.assigneeEmployeeId(),
          view.title(),
          view.instructions(),
          view.priority(),
          view.dueAt(),
          view.dueClass(),
          view.state(),
          view.version(),
          view.createdAt(),
          view.startedAt(),
          view.completedAt(),
          view.cancelledAt(),
          view.completionNote(),
          view.cancelReason(),
          view.cancelExplanation(),
          history.stream().map(ActionView::of).toList());
    }
  }

  /**
   * A task with its history (assignee).
   *
   * @param id task
   * @param title title
   * @param instructions instructions or {@code null}
   * @param priority priority
   * @param dueAt due instant or {@code null}
   * @param dueClass derived classification
   * @param state state
   * @param version version
   * @param createdAt creation instant
   * @param startedAt start instant or {@code null}
   * @param completedAt completion instant or {@code null}
   * @param cancelledAt cancellation instant or {@code null}
   * @param completionNote note or {@code null}
   * @param cancelReason reason or {@code null}
   * @param cancelExplanation explanation or {@code null}
   * @param history the actions in sequence order
   */
  @Schema(name = "MyTaskDetail")
  public record MyDetailView(
      UUID id,
      String title,
      String instructions,
      TaskPriority priority,
      Instant dueAt,
      TaskDueClass dueClass,
      TaskState state,
      int version,
      Instant createdAt,
      Instant startedAt,
      Instant completedAt,
      Instant cancelledAt,
      String completionNote,
      TaskCancelReason cancelReason,
      String cancelExplanation,
      List<MyActionView> history) {

    /**
     * Identifiers only.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "MyTaskDetail[" + id + "]";
    }

    /**
     * The detail of a task at an instant.
     *
     * @param task task
     * @param history its actions
     * @param now server instant
     * @return the view
     */
    public static MyDetailView of(Task task, List<TaskAction> history, Instant now) {
      MyTaskView view = MyTaskView.of(task, now);
      return new MyDetailView(
          view.id(),
          view.title(),
          view.instructions(),
          view.priority(),
          view.dueAt(),
          view.dueClass(),
          view.state(),
          view.version(),
          view.createdAt(),
          view.startedAt(),
          view.completedAt(),
          view.cancelledAt(),
          view.completionNote(),
          view.cancelReason(),
          view.cancelExplanation(),
          history.stream().map(MyActionView::of).toList());
    }
  }

  /**
   * One page of tasks (administrator).
   *
   * @param items tasks
   * @param nextCursor opaque cursor or {@code null}
   * @param asOf the server instant of the page (due classification)
   */
  @Schema(name = "TaskPage")
  public record Page(List<TaskView> items, String nextCursor, Instant asOf) {}

  /**
   * One page of the caller's own tasks.
   *
   * @param items tasks
   * @param nextCursor opaque cursor or {@code null}
   * @param asOf the server instant of the page (due classification)
   */
  @Schema(name = "MyTaskPage")
  public record MyPage(List<MyTaskView> items, String nextCursor, Instant asOf) {}

  /**
   * The minimal receipt of a command: no user text, so the idempotency store never holds any.
   *
   * @param id task
   * @param state resulting state
   * @param version resulting version
   * @param occurredAt server instant
   */
  @Schema(name = "TaskReceipt")
  public record Receipt(UUID id, TaskState state, int version, Instant occurredAt) {}
}
