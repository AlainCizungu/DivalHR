package com.divalhr.core.payroll.application;

import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audit records and internal outbox events of payment destinations and payment batches (MVP-095A,
 * ADR 0036). Metadata and event data are an allow-list ({@link #ALLOWED}): identifiers, the
 * version, the method, the period, the currency, the status, counts and the kind of view. They
 * never carry an account, a phone number, a holder name, a branch, a provider, a masked value, a
 * fingerprint, a ciphertext, an amount, an employee number, a reason code, an explanation, a
 * digest, a subject other than the actor, or a filename; a key outside the allow-list is refused
 * before anything is written. The events are internal: no webhook or WhatsApp type maps them.
 */
@Component
public class PaymentRecorder {

  /** Schema version of the audit metadata and event payloads. */
  public static final int SCHEMA_VERSION = 1;

  /** Audit action of a destination disclosure (read, history, own read, preview). */
  public static final String DESTINATION_READ = "payment-destination.read";

  /** Audit action of a recorded destination. */
  public static final String DESTINATION_RECORD = "payment-destination.record";

  /** Audit action of a batch disclosure (list, detail, lines, previews). */
  public static final String BATCH_READ = "payment-batch.read";

  /** Audit action of a prepared batch. */
  public static final String BATCH_PREPARE = "payment-batch.prepare";

  /** Audit action of a cancelled batch. */
  public static final String BATCH_CANCEL = "payment-batch.cancel";

  /** Audit action of a register download. */
  public static final String REGISTER_DOWNLOAD = "payment-register.download";

  /** Internal event of a recorded destination. */
  public static final String DESTINATION_EVENT = "payroll.payment-destination.recorded.v1";

  /** Internal event of a prepared batch. */
  public static final String PREPARED_EVENT = "payroll.payment-batch.prepared.v1";

  /** Internal event of a cancelled batch. */
  public static final String CANCELLED_EVENT = "payroll.payment-batch.cancelled.v1";

  /** The only keys audit metadata and event data may carry. */
  public static final Set<String> ALLOWED =
      Set.of(
          "schemaVersion",
          "legalEntityId",
          "packageId",
          "batchId",
          "employeeId",
          "version",
          "method",
          "period",
          "currency",
          "status",
          "lineCount",
          "view",
          "page",
          "resultCount");

  private static final String SOURCE = "core-api/payroll";

  private final AuditRecorder audit;
  private final OutboxWriter outbox;

  /**
   * Creates the recorder.
   *
   * @param audit audit recorder (mandatory transaction)
   * @param outbox outbox writer (mandatory transaction)
   */
  public PaymentRecorder(AuditRecorder audit, OutboxWriter outbox) {
    this.audit = audit;
    this.outbox = outbox;
  }

  /**
   * A recorded destination, without any value.
   *
   * @param employeeId the employee
   * @param version destination version
   * @param method the method
   * @param at server instant
   */
  public record DestinationRecorded(UUID employeeId, int version, String method, Instant at) {}

  /**
   * A prepared batch, without any amount or destination.
   *
   * @param legalEntityId legal entity
   * @param packageId package
   * @param batchId batch
   * @param version batch version
   * @param period {@code YYYY-MM}
   * @param lineCount lines
   * @param integrity the batch digest (only the integrity reference of the audit record)
   * @param at server instant
   */
  public record BatchRecorded(
      UUID legalEntityId,
      UUID packageId,
      UUID batchId,
      int version,
      String period,
      int lineCount,
      String integrity,
      Instant at) {

    @Override
    public String toString() {
      return "PaymentBatchRecorded[" + batchId + "]";
    }
  }

  /**
   * The fail-closed disclosure audit of a read, preview or download: the view, identifiers and
   * counts only.
   *
   * @param caller verified caller
   * @param action one of the read or download actions
   * @param resourceType {@code payment-destination} or {@code payment-batch}
   * @param resourceId the resource
   * @param ids further identifiers, keys from {@link #ALLOWED} only
   * @param view the kind of view
   * @param first whether it is the first page
   * @param count rows disclosed
   * @param at server instant
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void disclosed(
      PayrollCaller caller,
      String action,
      String resourceType,
      UUID resourceId,
      Map<String, String> ids,
      String view,
      boolean first,
      int count,
      Instant at) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", SCHEMA_VERSION);
    metadata.putAll(ids);
    metadata.put("view", view);
    metadata.put("page", first ? "first" : "cursor");
    metadata.put("resultCount", count);
    requireAllowed(metadata);
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
            Fingerprints.sha256(
                "DIVALHR-PAYMENT-DISCLOSURE\nversion=1\naction="
                    + action
                    + "\nview="
                    + view
                    + "\nresource="
                    + resourceId
                    + "\nby="
                    + caller.subject()
                    + "\n")));
  }

  /**
   * Records a destination: the audit record and the internal outbox event.
   *
   * @param caller verified caller
   * @param recorded the destination
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void destinationRecorded(PayrollCaller caller, DestinationRecorded recorded) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", SCHEMA_VERSION);
    metadata.put("employeeId", recorded.employeeId().toString());
    metadata.put("version", recorded.version());
    metadata.put("method", recorded.method());
    metadata.put("status", "RECORDED");
    requireAllowed(metadata);
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            recorded.at(),
            caller.subject(),
            DESTINATION_RECORD,
            "payment-destination",
            recorded.employeeId(),
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(
                "DIVALHR-PAYMENT-DESTINATION-RECORDED\nversion=1\nemployee="
                    + recorded.employeeId()
                    + "\nsequence="
                    + recorded.version()
                    + "\n")));
    outbox.append(
        envelope(caller, DESTINATION_EVENT, recorded.employeeId(), recorded.at(), metadata));
  }

  /**
   * Records a prepared batch: the audit record and the internal outbox event.
   *
   * @param caller verified caller
   * @param recorded the batch
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void prepared(PayrollCaller caller, BatchRecorded recorded) {
    batch(caller, recorded, BATCH_PREPARE, PREPARED_EVENT, "PREPARED", "PREPARED");
  }

  /**
   * Records a cancelled batch: the audit record and the internal outbox event.
   *
   * @param caller verified caller
   * @param recorded the batch
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void cancelled(PayrollCaller caller, BatchRecorded recorded) {
    batch(caller, recorded, BATCH_CANCEL, CANCELLED_EVENT, "CANCELLED", "CANCELLED");
  }

  private void batch(
      PayrollCaller caller,
      BatchRecorded recorded,
      String action,
      String event,
      String status,
      String label) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", SCHEMA_VERSION);
    metadata.put("legalEntityId", recorded.legalEntityId().toString());
    metadata.put("packageId", recorded.packageId().toString());
    metadata.put("batchId", recorded.batchId().toString());
    metadata.put("version", recorded.version());
    metadata.put("period", recorded.period());
    metadata.put("currency", "CDF");
    metadata.put("status", status);
    metadata.put("lineCount", recorded.lineCount());
    requireAllowed(metadata);
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            recorded.at(),
            caller.subject(),
            action,
            "payment-batch",
            recorded.batchId(),
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(
                "DIVALHR-PAYMENT-BATCH-"
                    + label
                    + "\nversion=1\nbatch="
                    + recorded.batchId()
                    + "\ndigest="
                    + recorded.integrity()
                    + "\n")));
    outbox.append(envelope(caller, event, recorded.batchId(), recorded.at(), metadata));
  }

  private static void requireAllowed(Map<String, Object> metadata) {
    for (String key : metadata.keySet()) {
      if (!ALLOWED.contains(key)) {
        throw new IllegalArgumentException("payment metadata key outside the allow-list");
      }
    }
  }

  private static EventEnvelope envelope(
      PayrollCaller caller, String type, UUID subject, Instant at, Map<String, Object> data) {
    return new EventEnvelope(
        UUID.randomUUID().toString(),
        type,
        SCHEMA_VERSION,
        caller.tenant().toString(),
        SOURCE,
        subject.toString(),
        at.toString(),
        caller.correlationId(),
        null,
        data);
  }
}
