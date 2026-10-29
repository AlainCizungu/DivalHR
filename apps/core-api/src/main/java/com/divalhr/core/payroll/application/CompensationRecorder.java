package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.domain.CompensationAction;
import com.divalhr.core.payroll.domain.CompensationCurrency;
import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audit records and internal outbox events of monthly compensation (MVP-093A, D93-11). Metadata
 * and event data are an allow-list: stable identifiers, the currency, the frequency, effective
 * dates, the action, the version, whether a reason was supplied and bounded counts. They never
 * carry an amount, an employee number, a name, a contact detail or the explanation. The events
 * are internal: no webhook or WhatsApp type maps them.
 */
@Component
public class CompensationRecorder {

  /** Schema version of the audit metadata and event payloads. */
  public static final int SCHEMA_VERSION = 1;

  private static final String SOURCE = "core-api/payroll";
  private static final String DISCLOSURE_RESOURCE = "employee-compensation";
  private static final String CHANGE_RESOURCE = "compensation-change";

  private final AuditRecorder audit;
  private final OutboxWriter outbox;

  /**
   * Creates the recorder.
   *
   * @param audit audit recorder (mandatory transaction)
   * @param outbox outbox writer (mandatory transaction)
   */
  public CompensationRecorder(AuditRecorder audit, OutboxWriter outbox) {
    this.audit = audit;
    this.outbox = outbox;
  }

  /**
   * One recorded change, without any amount or text.
   *
   * @param employeeId employee
   * @param changeId the change
   * @param sequence its sequence (the version after it)
   * @param action its action
   * @param currency currency of the value it introduced or cancelled
   * @param effectiveFrom first day of that value
   * @param targetChangeId the change acted on, or {@code null}
   * @param reasonSupplied whether a correction reason was supplied
   * @param retroactive whether the date was before the business date
   * @param supersededFacts facts it superseded
   * @param createdFacts facts it created
   * @param digest the confirmed preview or cancellation digest
   * @param at server instant
   */
  public record Recorded(
      UUID employeeId,
      UUID changeId,
      int sequence,
      CompensationAction action,
      CompensationCurrency currency,
      LocalDate effectiveFrom,
      UUID targetChangeId,
      boolean reasonSupplied,
      boolean retroactive,
      int supersededFacts,
      int createdFacts,
      String digest,
      Instant at) {}

  /**
   * The fail-closed disclosure audit of a read or preview: the view and counts only.
   *
   * @param caller verified caller
   * @param action {@code compensation.read} or {@code compensation.history-read}
   * @param employeeId employee whose compensation was disclosed
   * @param view the kind of view
   * @param first whether it is the first page
   * @param count rows disclosed
   * @param at server instant
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void disclosed(
      PayrollCaller caller,
      String action,
      UUID employeeId,
      String view,
      boolean first,
      int count,
      Instant at) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", SCHEMA_VERSION);
    metadata.put("view", view);
    metadata.put("page", first ? "first" : "cursor");
    metadata.put("resultCount", count);
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            at,
            caller.subject(),
            action,
            DISCLOSURE_RESOURCE,
            employeeId,
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(
                "DIVALHR-COMPENSATION-DISCLOSURE\nversion=1\nview="
                    + view
                    + "\nemployee="
                    + employeeId
                    + "\nby="
                    + caller.subject()
                    + "\n")));
  }

  /**
   * Records a committed change: the audit record and the internal outbox event, in the command's
   * transaction.
   *
   * @param caller verified caller
   * @param recorded the change
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void recorded(PayrollCaller caller, Recorded recorded) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", SCHEMA_VERSION);
    metadata.put("employeeId", recorded.employeeId().toString());
    metadata.put("changeId", recorded.changeId().toString());
    metadata.put("sequence", recorded.sequence());
    metadata.put("action", recorded.action().name());
    metadata.put("currency", recorded.currency().name());
    metadata.put("frequency", "MONTHLY");
    metadata.put("effectiveFrom", recorded.effectiveFrom().toString());
    metadata.put("version", recorded.sequence());
    metadata.put("reasonSupplied", recorded.reasonSupplied());
    metadata.put("retroactive", recorded.retroactive());
    metadata.put("supersededFacts", recorded.supersededFacts());
    metadata.put("createdFacts", recorded.createdFacts());
    if (recorded.targetChangeId() != null) {
      metadata.put("targetChangeId", recorded.targetChangeId().toString());
    }
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            recorded.at(),
            caller.subject(),
            recorded.action().auditAction(),
            CHANGE_RESOURCE,
            recorded.changeId(),
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            recorded.digest()));
    Map<String, Object> data = new LinkedHashMap<>(metadata);
    data.put("occurredAt", recorded.at().toString());
    outbox.append(
        new EventEnvelope(
            UUID.randomUUID().toString(),
            recorded.action().eventType(),
            SCHEMA_VERSION,
            caller.tenant().toString(),
            SOURCE,
            recorded.changeId().toString(),
            recorded.at().toString(),
            caller.correlationId(),
            null,
            data));
  }
}
