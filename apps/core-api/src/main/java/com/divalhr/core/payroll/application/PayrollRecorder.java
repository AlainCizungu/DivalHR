package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.domain.CompensationCurrency;
import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audit records and internal outbox events of payroll policies and runs (MVP-094A, D94A-9).
 * Metadata and event data are an allow-list: {@code legalEntityId}, {@code runId}, {@code version},
 * {@code period}, {@code currency}, {@code status}, {@code policySequence}, {@code employeeCount},
 * {@code blockedCount}, {@code supersededRunId}, {@code view}, {@code page}, {@code resultCount}
 * and {@code schemaVersion}. They never carry an amount, an employee number or identifier with a
 * day count, a range, an explanation or a digest. The events are internal: no webhook or WhatsApp
 * type maps them.
 */
@Component
public class PayrollRecorder {

  /** Schema version of the audit metadata and event payloads. */
  public static final int SCHEMA_VERSION = 1;

  /** Audit action of a policy change and the event type of its outbox event. */
  public static final String POLICY_CHANGE = "payroll-policy.change";

  /** Audit action of a policy disclosure. */
  public static final String POLICY_READ = "payroll-policy.read";

  /** Audit action of a run disclosure. */
  public static final String RUN_READ = "payroll-run.read";

  /** Audit action of a run creation. */
  public static final String RUN_CREATE = "payroll-run.create";

  /** Internal event of a recorded policy. */
  public static final String POLICY_EVENT = "payroll.payroll-policy.changed.v1";

  /** Internal event of a created run. */
  public static final String RUN_EVENT = "payroll.payroll-run.created.v1";

  private static final String SOURCE = "core-api/payroll";

  private final AuditRecorder audit;
  private final OutboxWriter outbox;

  /**
   * Creates the recorder.
   *
   * @param audit audit recorder (mandatory transaction)
   * @param outbox outbox writer (mandatory transaction)
   */
  public PayrollRecorder(AuditRecorder audit, OutboxWriter outbox) {
    this.audit = audit;
    this.outbox = outbox;
  }

  /**
   * One recorded policy, without any amount.
   *
   * @param legalEntityId legal entity
   * @param policyId the policy
   * @param sequence its sequence (the policy version after it)
   * @param at server instant
   */
  public record PolicyRecorded(UUID legalEntityId, UUID policyId, int sequence, Instant at) {}

  /**
   * One created run, without any amount or employee data.
   *
   * @param legalEntityId legal entity
   * @param runId the run
   * @param version its version
   * @param period {@code YYYY-MM}
   * @param currency currency
   * @param policySequence frozen policy sequence
   * @param employeeCount employees with a gross
   * @param supersededRunId the version it superseded, or {@code null}
   * @param inputDigest the digest of the inputs (stored only as the audit integrity reference)
   * @param at server instant
   */
  public record RunRecorded(
      UUID legalEntityId,
      UUID runId,
      int version,
      String period,
      CompensationCurrency currency,
      int policySequence,
      int employeeCount,
      UUID supersededRunId,
      String inputDigest,
      Instant at) {

    @Override
    public String toString() {
      return "RunRecorded[" + runId + "]";
    }
  }

  /**
   * The fail-closed disclosure audit of a read or preview: the view and counts only.
   *
   * @param caller verified caller
   * @param action {@code payroll-policy.read} or {@code payroll-run.read}
   * @param resourceType {@code payroll-policy} or {@code payroll-run}
   * @param resourceId the run, or the legal entity for a policy, list or preview
   * @param legalEntityId legal entity
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
      UUID legalEntityId,
      String view,
      boolean first,
      int count,
      Instant at) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", SCHEMA_VERSION);
    metadata.put("legalEntityId", legalEntityId.toString());
    metadata.put("view", view);
    metadata.put("page", first ? "first" : "cursor");
    metadata.put("resultCount", count);
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
                "DIVALHR-PAYROLL-DISCLOSURE\nversion=1\naction="
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
   * Records a committed policy: the audit record and the internal outbox event.
   *
   * @param caller verified caller
   * @param recorded the policy
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void policyChanged(PayrollCaller caller, PolicyRecorded recorded) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", SCHEMA_VERSION);
    metadata.put("legalEntityId", recorded.legalEntityId().toString());
    metadata.put("version", recorded.sequence());
    metadata.put("policySequence", recorded.sequence());
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            recorded.at(),
            caller.subject(),
            POLICY_CHANGE,
            "payroll-policy",
            recorded.policyId(),
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(
                "DIVALHR-PAYROLL-POLICY-CHANGED\nversion=1\npolicy="
                    + recorded.policyId()
                    + "\nsequence="
                    + recorded.sequence()
                    + "\n")));
    outbox.append(
        envelope(caller, POLICY_EVENT, recorded.policyId(), recorded.at(), metadata));
  }

  /**
   * Records a created run: the audit record and the internal outbox event.
   *
   * @param caller verified caller
   * @param recorded the run
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void runCreated(PayrollCaller caller, RunRecorded recorded) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", SCHEMA_VERSION);
    metadata.put("legalEntityId", recorded.legalEntityId().toString());
    metadata.put("runId", recorded.runId().toString());
    metadata.put("version", recorded.version());
    metadata.put("period", recorded.period());
    metadata.put("currency", recorded.currency().name());
    metadata.put("status", "DRAFT");
    metadata.put("policySequence", recorded.policySequence());
    metadata.put("employeeCount", recorded.employeeCount());
    metadata.put("blockedCount", 0);
    if (recorded.supersededRunId() != null) {
      metadata.put("supersededRunId", recorded.supersededRunId().toString());
    }
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            recorded.at(),
            caller.subject(),
            RUN_CREATE,
            "payroll-run",
            recorded.runId(),
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(
                "DIVALHR-PAYROLL-RUN-CREATED\nversion=1\nrun="
                    + recorded.runId()
                    + "\ninput="
                    + recorded.inputDigest()
                    + "\n")));
    outbox.append(envelope(caller, RUN_EVENT, recorded.runId(), recorded.at(), metadata));
  }

  private static EventEnvelope envelope(
      PayrollCaller caller,
      String type,
      UUID subject,
      Instant at,
      Map<String, Object> data) {
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
