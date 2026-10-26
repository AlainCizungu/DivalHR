package com.divalhr.core.operations;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.operations.domain.Task;
import com.divalhr.core.operations.domain.TaskDueClass;
import com.divalhr.core.operations.domain.TaskPriority;
import com.divalhr.core.operations.domain.TaskState;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The derived due classification (MVP-080, D80-5): strict at the due instant, never stored. */
class TaskDueClassTest {

  private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");

  private static Task task(TaskState state, Instant due) {
    return new Task(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "Title",
        null,
        TaskPriority.NORMAL,
        due,
        state,
        1,
        NOW.minusSeconds(60),
        "admin",
        null,
        state == TaskState.COMPLETED ? NOW : null,
        state == TaskState.CANCELLED ? NOW : null,
        null,
        null,
        null);
  }

  @Test
  void exactlyAtTheDueInstantIsNotOverdue() {
    Instant due = NOW;
    assertThat(Task.classify(due, NOW.minusNanos(1_000))).isEqualTo(TaskDueClass.DUE_SOON);
    assertThat(Task.classify(due, NOW)).isEqualTo(TaskDueClass.DUE_SOON);
    assertThat(Task.classify(due, NOW.plusNanos(1_000))).isEqualTo(TaskDueClass.OVERDUE);
  }

  @Test
  void theDueSoonHorizonIs48Hours() {
    assertThat(Task.classify(NOW.plus(Duration.ofHours(48)), NOW)).isEqualTo(TaskDueClass.DUE_SOON);
    assertThat(Task.classify(NOW.plus(Duration.ofHours(48)).plusNanos(1_000), NOW))
        .isEqualTo(TaskDueClass.LATER);
  }

  @Test
  void noDueInstantIsNone() {
    assertThat(Task.classify(null, NOW)).isEqualTo(TaskDueClass.NONE);
  }

  @Test
  void aFinishedTaskHasNoClassification() {
    assertThat(task(TaskState.COMPLETED, NOW.minusSeconds(5)).dueClass(NOW)).isNull();
    assertThat(task(TaskState.CANCELLED, NOW.minusSeconds(5)).dueClass(NOW)).isNull();
    assertThat(task(TaskState.OPEN, NOW.minusSeconds(5)).dueClass(NOW))
        .isEqualTo(TaskDueClass.OVERDUE);
    assertThat(task(TaskState.IN_PROGRESS, NOW.plusSeconds(5)).dueClass(NOW))
        .isEqualTo(TaskDueClass.DUE_SOON);
  }
}
