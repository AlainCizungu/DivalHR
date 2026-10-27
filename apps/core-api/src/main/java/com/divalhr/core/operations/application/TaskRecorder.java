package com.divalhr.core.operations.application;

import com.divalhr.core.operations.domain.Task;
import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The audit and event evidence of the task lifecycle (MVP-080, D80-9). Audit metadata and event
 * payloads carry identifiers, state, priority, the due instant, the reason code and bounded counts
 * only: never a title, instructions, note, explanation, name, employee number, e-mail address or
 * telephone number. The whole after-state, including the confidential text, is reduced to a SHA-256
 * digest.
 */
@Component
class TaskRecorder {

  /** Schema version of the audit metadata and the events. */
  static final int SCHEMA_VERSION = 1;

  private static final String SOURCE = "core-api/operations";

  private final AuditRecorder audit;
  private final OutboxWriter outbox;
  private final JsonMapper json;

  TaskRecorder(AuditRecorder audit, OutboxWriter outbox, JsonMapper json) {
    this.audit = audit;
    this.outbox = outbox;
    this.json = json;
  }

  /**
   * Records a command: one audit row and one outbox event, in the command's transaction.
   *
   * @param caller verified caller
   * @param auditAction audit action
   * @param eventType event type
   * @param before the task before ({@code null} for a creation)
   * @param after the task after
   * @param role {@code tenant-admin} or {@code employee}
   * @param at server instant
   */
  void command(
      OperationsCaller caller,
      String auditAction,
      String eventType,
      Task before,
      Task after,
      String role,
      Instant at) {
    Map<String, Object> ids = new LinkedHashMap<>();
    ids.put("taskId", after.id().toString());
    ids.put("assigneeEmployeeId", after.assigneeEmployeeId().toString());
    ids.put("state", after.state().name());
    ids.put("version", after.version());
    ids.put("priority", after.priority().name());
    if (after.dueAt() != null) {
      ids.put("dueAt", after.dueAt().toString());
    }
    if (after.cancelReason() != null) {
      ids.put("reasonCode", after.cancelReason().name());
    }
    if (after.completionNote() != null) {
      ids.put("hasCompletionNote", true);
    }
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", SCHEMA_VERSION);
    metadata.putAll(ids);
    metadata.put("actorRole", role);
    if (before != null) {
      metadata.put("priorState", before.state().name());
    }
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            at,
            caller.subject(),
            auditAction,
            "task",
            after.id(),
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(json.writeValueAsString(afterState(caller, after)))));
    outbox.append(
        new EventEnvelope(
            UUID.randomUUID().toString(),
            eventType,
            SCHEMA_VERSION,
            caller.tenant().toString(),
            SOURCE,
            after.id().toString(),
            at.toString(),
            caller.correlationId(),
            null,
            new LinkedHashMap<>(ids)));
  }

  /**
   * Records the disclosure of one page: the page, the count and the filter flags; the identifiers
   * of the tasks only in the digest.
   *
   * @param caller verified caller
   * @param action {@code task.list}
   * @param view {@code admin} or {@code self}
   * @param resourceType audit resource type
   * @param resourceId the tenant (admin) or the caller's employee (self)
   * @param page {@code first} or {@code next}
   * @param shown the tasks shown
   * @param filtered whether any filter applied
   * @param at server instant
   */
  void listed(
      OperationsCaller caller,
      String action,
      String view,
      String resourceType,
      UUID resourceId,
      String page,
      List<Task> shown,
      boolean filtered,
      Instant at) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", SCHEMA_VERSION);
    metadata.put("view", view);
    metadata.put("page", page);
    metadata.put("resultCount", shown.size());
    metadata.put("filtered", filtered);
    StringBuilder text =
        new StringBuilder("DIVALHR-TASK-DISCLOSURE\nversion=1\nview=").append(view).append('\n');
    shown.forEach(task -> text.append("id=").append(task.id()).append('\n'));
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            at,
            caller.subject(),
            action,
            resourceType,
            resourceId,
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(text.toString())));
  }

  /**
   * Records the disclosure of one task.
   *
   * @param caller verified caller
   * @param action {@code task.read}
   * @param view {@code admin} or {@code self}
   * @param task the task shown
   * @param at server instant
   */
  void read(OperationsCaller caller, String action, String view, Task task, Instant at) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", SCHEMA_VERSION);
    metadata.put("view", view);
    metadata.put("taskId", task.id().toString());
    metadata.put("state", task.state().name());
    metadata.put("version", task.version());
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            at,
            caller.subject(),
            action,
            "task",
            task.id(),
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(
                "DIVALHR-TASK-DISCLOSURE\nversion=1\nview=" + view + "\nid=" + task.id() + "\n")));
  }

  /** The whole persisted state; only its digest is stored ({@code after_state_sha256}). */
  private static Map<String, Object> afterState(OperationsCaller caller, Task task) {
    Map<String, Object> state = new TreeMap<>();
    state.put("tenantId", caller.tenant().toString());
    state.put("id", task.id().toString());
    state.put("assigneeEmployeeId", task.assigneeEmployeeId().toString());
    state.put("title", task.title());
    state.put("instructions", task.instructions());
    state.put("priority", task.priority().name());
    state.put("dueAt", task.dueAt() == null ? null : task.dueAt().toString());
    state.put("state", task.state().name());
    state.put("version", task.version());
    state.put("createdAt", task.createdAt().toString());
    state.put("startedAt", task.startedAt() == null ? null : task.startedAt().toString());
    state.put("completedAt", task.completedAt() == null ? null : task.completedAt().toString());
    state.put("cancelledAt", task.cancelledAt() == null ? null : task.cancelledAt().toString());
    state.put("completionNote", task.completionNote());
    state.put("cancelReason", task.cancelReason() == null ? null : task.cancelReason().name());
    state.put("cancelExplanation", task.cancelExplanation());
    state.put("actor", caller.subject());
    return state;
  }
}
