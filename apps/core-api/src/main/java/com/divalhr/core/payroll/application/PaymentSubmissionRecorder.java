package com.divalhr.core.payroll.application;

import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audit records and internal outbox events of provider submissions (MVP-095B, ADR 0037, D95B-8).
 * Metadata and event data are an allow-list ({@link #ALLOWED}): identifiers, the provider code, the
 * state, the version, counts, the attempt number and the allow-listed outcome code. They never
 * carry an amount, a currency total, an employee, a destination, a provider reference, a signature,
 * a response detail or a digest; a key outside the allow-list is refused before anything is
 * written. The events are internal: no webhook or WhatsApp type maps them.
 */
@Component
public class PaymentSubmissionRecorder {

  /** Schema version of the audit metadata and event payloads. */
  public static final int SCHEMA_VERSION = 1;

  /** Audit action of a preview or a read (disclosure). */
  public static final String PREVIEW = "payment-submission.preview";

  /** Audit action of a created submission. */
  public static final String CREATE = "payment-submission.create";

  /** Audit action of a read or an attempt-history read (disclosure). */
  public static final String READ = "payment-submission.read";

  /** Audit action of a worker attempt (every state change after creation). */
  public static final String ATTEMPT = "payment-submission.attempt";

  /** Internal event of a created submission. */
  public static final String CREATED_EVENT = "payroll.payment-submission.created.v1";

  /** Internal event of a state change. */
  public static final String STATE_CHANGED_EVENT = "payroll.payment-submission.state-changed.v1";

  /** The actor of the delivery worker. */
  public static final String WORKER_ACTOR = "system:payment-submission-worker";

  /** The only keys audit metadata and event data may carry. */
  public static final Set<String> ALLOWED =
      Set.of(
          "schemaVersion",
          "legalEntityId",
          "batchId",
          "submissionId",
          "providerCode",
          "state",
          "version",
          "lineCount",
          "attemptNumber",
          "outcomeCode",
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
  public PaymentSubmissionRecorder(AuditRecorder audit, OutboxWriter outbox) {
    this.audit = audit;
    this.outbox = outbox;
  }

  /**
   * A submission state change, without any amount, reference or destination.
   *
   * @param legalEntityId legal entity
   * @param batchId batch
   * @param submissionId submission
   * @param providerCode provider profile code
   * @param state the new state
   * @param version the new version
   * @param lineCount lines
   * @param attemptNumber attempt count
   * @param outcomeCode allow-listed outcome code, or {@code null}
   * @param integrity the batch digest (only the integrity reference of the audit record)
   * @param at server instant
   */
  public record Change(
      UUID legalEntityId,
      UUID batchId,
      UUID submissionId,
      String providerCode,
      String state,
      int version,
      int lineCount,
      int attemptNumber,
      String outcomeCode,
      String integrity,
      Instant at) {
    @Override
    public String toString() {
      return "PaymentSubmissionChange[" + submissionId + "]";
    }
  }

  /**
   * The fail-closed disclosure audit of a preview or a read: the view, identifiers and counts only.
   *
   * @param caller verified caller
   * @param action {@link #PREVIEW} or {@link #READ}
   * @param resourceId the batch or submission
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
            "payment-submission",
            resourceId,
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(
                "DIVALHR-PAYMENT-SUBMISSION-DISCLOSURE\nversion=1\naction="
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
   * Records a created submission: the audit record and the internal outbox event.
   *
   * @param caller verified caller
   * @param change the created submission
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void created(PayrollCaller caller, Change change) {
    Map<String, Object> metadata = metadata(change);
    audit.record(
        auditEvent(
            caller.subject(),
            CREATE,
            caller.tenant(),
            caller.correlationId(),
            change,
            metadata,
            "CREATED"));
    outbox.append(
        envelope(
            caller.tenant(),
            caller.correlationId(),
            CREATED_EVENT,
            change.submissionId(),
            change.at(),
            eventData(change)));
  }

  /**
   * Records a worker state change: the audit record and the internal outbox event.
   *
   * @param tenant the tenant of the submission
   * @param correlationId the worker cycle's correlation ID
   * @param change the new state
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void attempted(TenantId tenant, String correlationId, Change change) {
    Map<String, Object> metadata = metadata(change);
    audit.record(
        auditEvent(WORKER_ACTOR, ATTEMPT, tenant, correlationId, change, metadata, "ATTEMPT"));
    outbox.append(
        envelope(
            tenant,
            correlationId,
            STATE_CHANGED_EVENT,
            change.submissionId(),
            change.at(),
            eventData(change)));
  }

  private static AuditEvent auditEvent(
      String actor,
      String action,
      TenantId tenant,
      String correlationId,
      Change change,
      Map<String, Object> metadata,
      String label) {
    return new AuditEvent(
        UUID.randomUUID(),
        change.at(),
        actor,
        action,
        "payment-submission",
        change.submissionId(),
        tenant.value(),
        "SUCCESS",
        correlationId,
        metadata,
        Fingerprints.sha256(
            "DIVALHR-PAYMENT-SUBMISSION-"
                + label
                + "\nversion=1\nsubmission="
                + change.submissionId()
                + "\nsequence="
                + change.version()
                + "\nbatch="
                + change.integrity()
                + "\n"));
  }

  private static Map<String, Object> metadata(Change change) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", SCHEMA_VERSION);
    metadata.put("legalEntityId", change.legalEntityId().toString());
    metadata.put("batchId", change.batchId().toString());
    metadata.put("submissionId", change.submissionId().toString());
    metadata.put("providerCode", change.providerCode());
    metadata.put("state", change.state());
    metadata.put("version", change.version());
    metadata.put("lineCount", change.lineCount());
    metadata.put("attemptNumber", change.attemptNumber());
    if (change.outcomeCode() != null) {
      metadata.put("outcomeCode", change.outcomeCode());
    }
    requireAllowed(metadata);
    return metadata;
  }

  /** Event payloads carry only the submission, the batch, the state, the version and the outcome. */
  private static Map<String, Object> eventData(Change change) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("schemaVersion", SCHEMA_VERSION);
    data.put("submissionId", change.submissionId().toString());
    data.put("batchId", change.batchId().toString());
    data.put("state", change.state());
    data.put("version", change.version());
    if (change.outcomeCode() != null) {
      data.put("outcomeCode", change.outcomeCode());
    }
    requireAllowed(data);
    return data;
  }

  private static void requireAllowed(Map<String, Object> metadata) {
    for (String key : metadata.keySet()) {
      if (!ALLOWED.contains(key)) {
        throw new IllegalArgumentException("payment submission metadata key outside the allow-list");
      }
    }
  }

  private static EventEnvelope envelope(
      TenantId tenant,
      String correlationId,
      String type,
      UUID subject,
      Instant at,
      Map<String, Object> data) {
    return new EventEnvelope(
        UUID.randomUUID().toString(),
        type,
        SCHEMA_VERSION,
        tenant.toString(),
        SOURCE,
        subject.toString(),
        at.toString(),
        correlationId,
        null,
        data);
  }
}
