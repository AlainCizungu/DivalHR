package com.divalhr.core.operations.application;

import com.divalhr.core.operations.domain.Expense;
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
 * The audit and event evidence of the expense lifecycle (MVP-081, D81-11). Audit metadata and event
 * payloads carry identifiers, state, cycle, revision, version, currency, the amount in minor units,
 * the category, and receipt-present and reason-present flags only: never a merchant, description,
 * return reason, receipt file name, receipt digest or receipt bytes, and never a name, number or
 * contact detail. The whole after-state, including the restricted text, is reduced to a SHA-256
 * digest. Only submission, withdrawal, approval and return emit an internal event; there is no
 * webhook, WhatsApp or other delivery.
 */
@Component
class ExpenseRecorder {

  /** Schema version of the audit metadata and the events. */
  static final int SCHEMA_VERSION = 1;

  private static final String SOURCE = "core-api/operations";

  private final AuditRecorder audit;
  private final OutboxWriter outbox;
  private final JsonMapper json;

  ExpenseRecorder(AuditRecorder audit, OutboxWriter outbox, JsonMapper json) {
    this.audit = audit;
    this.outbox = outbox;
    this.json = json;
  }

  /**
   * Records a command: one audit row, and (when {@code eventType} is not null) one outbox event, in
   * the command's transaction.
   *
   * @param caller verified caller
   * @param auditAction audit action
   * @param eventType event type or {@code null}
   * @param before the expense before ({@code null} for a creation)
   * @param after the expense after
   * @param role {@code employee} or {@code manager}
   * @param at server instant
   */
  void command(
      OperationsCaller caller,
      String auditAction,
      String eventType,
      Expense before,
      Expense after,
      String role,
      boolean reasonPresent,
      Instant at) {
    Map<String, Object> ids = new LinkedHashMap<>();
    ids.put("expenseId", after.id().toString());
    ids.put("employeeId", after.employeeId().toString());
    ids.put("state", after.state().name());
    ids.put("cycle", after.cycle());
    ids.put("revision", after.revision());
    ids.put("version", after.version());
    ids.put("currency", after.currency().name());
    ids.put("amountMinorUnits", after.amountMinor());
    ids.put("category", after.category().name());
    ids.put("receiptPresent", after.receipt() != null);
    if (reasonPresent) {
      ids.put("reasonPresent", true);
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
            "expense",
            after.id(),
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(json.writeValueAsString(afterState(caller, after)))));
    if (eventType != null) {
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
  }

  /**
   * Records the disclosure of one page: the page, the count and the filter flag; the identifiers of
   * the expenses only in the digest.
   *
   * @param caller verified caller
   * @param view {@code self}, {@code manager} or {@code admin}
   * @param resourceId the tenant, or the caller's own employee
   * @param resourceType audit resource type
   * @param page {@code first} or {@code next}
   * @param shown the expenses shown
   * @param filtered whether any filter applied
   * @param at server instant
   */
  void listed(
      OperationsCaller caller,
      String view,
      String resourceType,
      UUID resourceId,
      String page,
      List<Expense> shown,
      boolean filtered,
      Instant at) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", SCHEMA_VERSION);
    metadata.put("view", view);
    metadata.put("page", page);
    metadata.put("resultCount", shown.size());
    metadata.put("filtered", filtered);
    StringBuilder text =
        new StringBuilder("DIVALHR-EXPENSE-DISCLOSURE\nversion=1\nview=").append(view).append('\n');
    shown.forEach(expense -> text.append("id=").append(expense.id()).append('\n'));
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            at,
            caller.subject(),
            "expense.list",
            resourceType,
            resourceId,
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(text.toString())));
  }

  /**
   * Records the disclosure of one expense, or of one revision's receipt.
   *
   * @param caller verified caller
   * @param action {@code expense.read} or {@code expense.receipt-download}
   * @param view {@code self}, {@code manager} or {@code admin}
   * @param expense the expense shown
   * @param revision the receipt's revision, or 0 for a plain read
   * @param at server instant
   */
  void read(
      OperationsCaller caller,
      String action,
      String view,
      Expense expense,
      int revision,
      Instant at) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", SCHEMA_VERSION);
    metadata.put("view", view);
    metadata.put("expenseId", expense.id().toString());
    metadata.put("state", expense.state().name());
    metadata.put("version", expense.version());
    if (revision > 0) {
      metadata.put("revision", revision);
    }
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            at,
            caller.subject(),
            action,
            "expense",
            expense.id(),
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(
                "DIVALHR-EXPENSE-DISCLOSURE\nversion=1\nview="
                    + view
                    + "\nid="
                    + expense.id()
                    + "\nrevision="
                    + revision
                    + "\n")));
  }

  /** The whole persisted state; only its digest is stored ({@code after_state_sha256}). */
  private static Map<String, Object> afterState(OperationsCaller caller, Expense e) {
    Map<String, Object> state = new TreeMap<>();
    state.put("tenantId", caller.tenant().toString());
    state.put("id", e.id().toString());
    state.put("employeeId", e.employeeId().toString());
    state.put("state", e.state().name());
    state.put("cycle", e.cycle());
    state.put("revision", e.revision());
    state.put("version", e.version());
    state.put("businessDate", e.businessDate().toString());
    state.put("currency", e.currency().name());
    state.put("amountMinorUnits", e.amountMinor());
    state.put("category", e.category().name());
    state.put("merchant", e.merchant());
    state.put("description", e.description());
    state.put("receiptSha256", e.receipt() == null ? null : e.receipt().sha256());
    state.put("createdAt", e.createdAt().toString());
    state.put("submittedAt", e.submittedAt() == null ? null : e.submittedAt().toString());
    state.put("decidedAt", e.decidedAt() == null ? null : e.decidedAt().toString());
    state.put("actor", caller.subject());
    return state;
  }
}
