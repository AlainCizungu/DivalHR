package com.divalhr.core.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.operations.api.CancelTaskRequest;
import com.divalhr.core.operations.api.CompleteTaskRequest;
import com.divalhr.core.operations.api.CreateTaskRequest;
import com.divalhr.core.operations.api.StartTaskRequest;
import com.divalhr.core.operations.application.TaskCommands;
import com.divalhr.core.operations.domain.TaskCancelReason;
import com.divalhr.core.operations.domain.TaskPriority;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The validated task commands (MVP-080): shape, closed vocabularies, due range, no echo. */
class TaskCommandsTest {

  private static final String KEY = "0123456789abcdef0123";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final UUID ASSIGNEE = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
  private static final UUID TASK = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

  private static <T> T body(String json, Class<T> type) {
    return JSON.readValue(json, type);
  }

  private static List<String> problems(Runnable validation) {
    try {
      validation.run();
      return List.of();
    } catch (ApiException invalid) {
      assertThat(invalid.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
      @SuppressWarnings("unchecked")
      List<Map<String, String>> fields = (List<Map<String, String>>) invalid.params().get("fields");
      return fields.stream().map(f -> f.get("field") + ":" + f.get("constraint")).toList();
    }
  }

  private static CreateTaskRequest create(String json) {
    return body(json, CreateTaskRequest.class);
  }

  private static final String VALID =
      "{\"assigneeEmployeeId\":\""
          + ASSIGNEE
          + "\",\"title\":\"Count stock\",\"priority\":\"HIGH\"}";

  @Test
  void aValidAssignmentIsNormalized() {
    TaskCommands.Create command =
        TaskCommands.Create.from(
            KEY,
            create(
                "{\"assigneeEmployeeId\":\""
                    + ASSIGNEE.toString().toUpperCase()
                    + "\",\"title\":\"  Count stock \",\"instructions\":\"  \","
                    + "\"priority\":\"URGENT\",\"dueAt\":\"2026-11-01T10:00:00+01:00\"}"));
    assertThat(command.assigneeEmployeeId()).isEqualTo(ASSIGNEE);
    assertThat(command.title()).isEqualTo("Count stock");
    assertThat(command.instructions()).isNull();
    assertThat(command.priority()).isEqualTo(TaskPriority.URGENT);
    assertThat(command.dueAt()).isEqualTo(Instant.parse("2026-11-01T09:00:00Z"));
    assertThat(command.toString()).doesNotContain("Count stock");
  }

  @Test
  void theCanonicalFormIsScopedAndKeySorted() {
    TaskCommands.Create command = TaskCommands.Create.from(KEY, create(VALID));
    Map<String, Object> canonical = command.canonical(new TenantId(UUID.randomUUID()));
    assertThat(canonical.keySet())
        .containsExactly(
            "assigneeEmployeeId", "dueAt", "instructions", "priority", "tenantId", "title");
  }

  @Test
  void everyDefectIsAFieldAndConstraintPairWithoutEcho() {
    assertThat(problems(() -> TaskCommands.Create.from(null, create(VALID))))
        .containsExactly("Idempotency-Key:REQUIRED");
    assertThat(problems(() -> TaskCommands.Create.from("short", create(VALID))))
        .containsExactly("Idempotency-Key:FORMAT");
    assertThat(problems(() -> TaskCommands.Create.from(KEY, create("{}"))))
        .containsExactlyInAnyOrder(
            "assigneeEmployeeId:REQUIRED", "title:REQUIRED", "priority:REQUIRED");
    assertThat(
            problems(
                () ->
                    TaskCommands.Create.from(
                        KEY,
                        create(
                            "{\"assigneeEmployeeId\":\"nope\",\"title\":\"<b>x</b>\","
                                + "\"priority\":\"LOW\",\"dueAt\":\"tomorrow\","
                                + "\"instructions\":7,\"tenantId\":\"x\"}"))))
        .containsExactlyInAnyOrder(
            "body:UNKNOWN_PROPERTY",
            "assigneeEmployeeId:FORMAT",
            "title:FORMAT",
            "instructions:FORMAT",
            "priority:FORMAT",
            "dueAt:FORMAT");
    assertThat(problems(() -> TaskCommands.Create.from(KEY, null)))
        .containsExactly("body:REQUIRED");
  }

  @Test
  void theDueInstantMustBeAfterNowAndWithin366Days() {
    Instant now = Instant.parse("2026-10-01T10:00:00Z");
    TaskCommands.Create at =
        TaskCommands.Create.from(
            KEY, create(VALID.replace("}", ",\"dueAt\":\"2026-10-01T10:00:00Z\"}")));
    assertThat(problems(() -> at.requireDueWithin(now))).containsExactly("dueAt:RANGE");
    TaskCommands.Create after =
        TaskCommands.Create.from(
            KEY, create(VALID.replace("}", ",\"dueAt\":\"2026-10-01T10:00:00.000001Z\"}")));
    assertThat(problems(() -> after.requireDueWithin(now))).isEmpty();
    TaskCommands.Create limit =
        TaskCommands.Create.from(
            KEY,
            create(VALID.replace("}", ",\"dueAt\":\"" + now.plusSeconds(366L * 86_400) + "\"}")));
    assertThat(problems(() -> limit.requireDueWithin(now))).isEmpty();
    TaskCommands.Create beyond =
        TaskCommands.Create.from(
            KEY,
            create(
                VALID.replace(
                    "}",
                    ",\"dueAt\":\"" + now.plusSeconds(366L * 86_400).plusNanos(1_000) + "\"}")));
    assertThat(problems(() -> beyond.requireDueWithin(now))).containsExactly("dueAt:RANGE");
    TaskCommands.Create none = TaskCommands.Create.from(KEY, create(VALID));
    assertThat(problems(() -> none.requireDueWithin(now))).isEmpty();
  }

  @Test
  void aCancellationNeedsAClosedReasonAndOtherNeedsAnExplanation() {
    TaskCommands.Cancel plain =
        TaskCommands.Cancel.from(
            KEY,
            TASK.toString(),
            body(
                "{\"expectedVersion\":2,\"reason\":\"NO_LONGER_REQUIRED\"}",
                CancelTaskRequest.class));
    assertThat(plain.reason()).isEqualTo(TaskCancelReason.NO_LONGER_REQUIRED);
    assertThat(plain.explanation()).isNull();
    assertThat(plain.expectedVersion()).isEqualTo(2);

    TaskCommands.Cancel other =
        TaskCommands.Cancel.from(
            KEY,
            TASK.toString(),
            body(
                "{\"expectedVersion\":1,\"reason\":\"OTHER\","
                    + "\"explanation\":\"The client changed the plan\"}",
                CancelTaskRequest.class));
    assertThat(other.explanation()).isEqualTo("The client changed the plan");
    assertThat(other.toString()).doesNotContain("client");

    assertThat(
            problems(
                () ->
                    TaskCommands.Cancel.from(
                        KEY,
                        TASK.toString(),
                        body(
                            "{\"expectedVersion\":1,\"reason\":\"OTHER\"}",
                            CancelTaskRequest.class))))
        .containsExactly("explanation:REQUIRED");
    assertThat(
            problems(
                () ->
                    TaskCommands.Cancel.from(
                        KEY,
                        TASK.toString(),
                        body(
                            "{\"expectedVersion\":1,\"reason\":\"OTHER\",\"explanation\":\"short\"}",
                            CancelTaskRequest.class))))
        .containsExactly("explanation:LENGTH");
    assertThat(
            problems(
                () ->
                    TaskCommands.Cancel.from(
                        KEY,
                        TASK.toString(),
                        body(
                            "{\"expectedVersion\":1,\"reason\":\"CREATED_IN_ERROR\","
                                + "\"explanation\":\"Not allowed with this reason\"}",
                            CancelTaskRequest.class))))
        .containsExactly("explanation:FORMAT");
    assertThat(
            problems(
                () ->
                    TaskCommands.Cancel.from(
                        KEY,
                        TASK.toString(),
                        body(
                            "{\"expectedVersion\":1,\"reason\":\"BORED\"}",
                            CancelTaskRequest.class))))
        .containsExactly("reason:FORMAT");
  }

  @Test
  void theExpectedVersionIsARequiredPositiveInteger() {
    for (String version : List.of("0", "-1", "\"2\"", "1.5", "true", "2147483648")) {
      assertThat(
              problems(
                  () ->
                      TaskCommands.Start.from(
                          KEY,
                          TASK.toString(),
                          body("{\"expectedVersion\":" + version + "}", StartTaskRequest.class))))
          .as(version)
          .hasSize(1);
    }
    assertThat(
            problems(
                () ->
                    TaskCommands.Start.from(
                        KEY, TASK.toString(), body("{}", StartTaskRequest.class))))
        .containsExactly("expectedVersion:REQUIRED");
    assertThat(
            TaskCommands.Start.from(
                    KEY, TASK.toString(), body("{\"expectedVersion\":7}", StartTaskRequest.class))
                .expectedVersion())
        .isEqualTo(7);
  }

  @Test
  void aCompletionNoteIsOptionalRestrictedPlainText() {
    TaskCommands.Complete without =
        TaskCommands.Complete.from(
            KEY,
            TASK.toString(),
            body("{\"expectedVersion\":1,\"note\":\"  \"}", CompleteTaskRequest.class));
    assertThat(without.note()).isNull();
    TaskCommands.Complete with =
        TaskCommands.Complete.from(
            KEY,
            TASK.toString(),
            body(
                "{\"expectedVersion\":1,\"note\":\"Rien à signaler\"}", CompleteTaskRequest.class));
    assertThat(with.note()).isEqualTo("Rien à signaler");
    assertThat(with.toString()).doesNotContain("Rien");
    assertThat(
            problems(
                () ->
                    TaskCommands.Complete.from(
                        KEY,
                        TASK.toString(),
                        body(
                            "{\"expectedVersion\":1,\"note\":\"" + "x".repeat(501) + "\"}",
                            CompleteTaskRequest.class))))
        .containsExactly("note:LENGTH");
    assertThat(
            problems(
                () ->
                    TaskCommands.Complete.from(
                        KEY,
                        TASK.toString(),
                        body(
                            "{\"expectedVersion\":1,\"note\":\"see https://x.example\"}",
                            CompleteTaskRequest.class))))
        .containsExactly("note:FORMAT");
  }

  @Test
  void aPathThatIsNotATaskIdIsTheSafeNotFound() {
    assertThatThrownBy(() -> TaskCommands.taskId("not-a-uuid"))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.TASK_NOT_FOUND));
    assertThatThrownBy(() -> TaskCommands.taskId(null))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.TASK_NOT_FOUND));
    assertThat(TaskCommands.taskId(TASK.toString().toUpperCase())).isEqualTo(TASK);
    assertThatThrownBy(
            () ->
                TaskCommands.Start.from(
                    KEY, "x", body("{\"expectedVersion\":1}", StartTaskRequest.class)))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.TASK_NOT_FOUND));
  }
}
