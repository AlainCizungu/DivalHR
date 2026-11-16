package com.divalhr.core.payroll.application;

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
 * Audit records and internal outbox events of the salary-advance workflow (MVP-120A, D120A-13).
 * Metadata and event data are an allow-list: {@code legalEntityId}, {@code policyId}, {@code
 * policySequence}, {@code requestId}, {@code status}, {@code version}, {@code currency}, {@code
 * reasonCode}, {@code view}, {@code page}, {@code resultCount} and {@code schemaVersion}. They
 * never carry an amount, a salary, a ceiling, an employment date, an employee number or identifier,
 * a consent detail, a note, a digest of one or a request body. The events are internal: no webhook
 * or WhatsApp type maps them and none is sent to any partner.
 */
@Component
public class SalaryAdvanceRecorder {

  /** Schema version of the audit metadata and event payloads. */
  public static final int SCHEMA_VERSION = 1;

  /** Audit actions. */
  public static final String POLICY_READ = "salary-advance-policy.read";

  public static final String POLICY_PREVIEW = "salary-advance-policy.preview";
  public static final String POLICY_CREATE = "salary-advance-policy.create";
  public static final String POLICY_CANCEL = "salary-advance-policy.cancel";
  public static final String ELIGIBILITY_READ = "salary-advance-eligibility.read";
  public static final String REQUEST_PREVIEW = "salary-advance-request.preview";
  public static final String REQUEST_SUBMIT = "salary-advance-request.submit";
  public static final String REQUEST_READ = "salary-advance-request.read";
  public static final String REQUEST_WITHDRAW = "salary-advance-request.withdraw";
  public static final String EMPLOYER_CONFIRM = "salary-advance-request.employer-confirm";
  public static final String EMPLOYER_REFUSE = "salary-advance-request.employer-refuse";

  /** Internal event types. */
  public static final String POLICY_CREATED_EVENT = "payroll.salary-advance-policy.created.v1";

  public static final String SUBMITTED_EVENT = "payroll.salary-advance-request.submitted.v1";
  public static final String WITHDRAWN_EVENT = "payroll.salary-advance-request.withdrawn.v1";
  public static final String CONFIRMED_EVENT =
      "payroll.salary-advance-request.employer-confirmed.v1";
  public static final String REFUSED_EVENT = "payroll.salary-advance-request.employer-refused.v1";

  private static final String SOURCE = "core-api/payroll";

  private final AuditRecorder audit;
  private final OutboxWriter outbox;

  /**
   * Creates the recorder.
   *
   * @param audit audit recorder (mandatory transaction)
   * @param outbox outbox writer (mandatory transaction)
   */
  public SalaryAdvanceRecorder(AuditRecorder audit, OutboxWriter outbox) {
    this.audit = audit;
    this.outbox = outbox;
  }

  /**
   * The fail-closed disclosure audit of a read or preview: the view and counts only.
   *
   * @param caller verified caller
   * @param action audit action
   * @param resourceType resource type
   * @param resourceId the resource (a request, a legal entity or the caller's employee record)
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
        event(
            caller,
            action,
            resourceType,
            resourceId,
            at,
            metadata,
            "DIVALHR-SALARY-ADVANCE-DISCLOSURE\nversion=1\naction="
                + action
                + "\nview="
                + view
                + "\nresource="
                + resourceId
                + "\nby="
                + caller.subject()
                + "\n"));
  }

  /**
   * Records an appended policy version: the audit record and the internal outbox event.
   *
   * @param caller verified caller
   * @param legalEntityId legal entity
   * @param policyId the policy
   * @param sequence its sequence
   * @param currency its currency
   * @param at server instant
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void policyCreated(
      PayrollCaller caller,
      UUID legalEntityId,
      UUID policyId,
      int sequence,
      String currency,
      Instant at) {
    Map<String, Object> metadata = policyMetadata(legalEntityId, sequence, currency);
    audit.record(
        event(
            caller,
            POLICY_CREATE,
            "salary-advance-policy",
            policyId,
            at,
            metadata,
            "DIVALHR-SALARY-ADVANCE-POLICY-CREATED\nversion=1\npolicy="
                + policyId
                + "\nsequence="
                + sequence
                + "\n"));
    outbox.append(envelope(caller, POLICY_CREATED_EVENT, policyId, at, metadata));
  }

  /**
   * Records the cancellation of a policy version that had not taken effect (audit only).
   *
   * @param caller verified caller
   * @param legalEntityId legal entity
   * @param policyId the policy
   * @param sequence its sequence
   * @param currency its currency
   * @param at server instant
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void policyCancelled(
      PayrollCaller caller,
      UUID legalEntityId,
      UUID policyId,
      int sequence,
      String currency,
      Instant at) {
    audit.record(
        event(
            caller,
            POLICY_CANCEL,
            "salary-advance-policy",
            policyId,
            at,
            policyMetadata(legalEntityId, sequence, currency),
            "DIVALHR-SALARY-ADVANCE-POLICY-CANCELLED\nversion=1\npolicy="
                + policyId
                + "\nsequence="
                + sequence
                + "\n"));
  }

  /**
   * Records a request lifecycle step: the audit record and the internal outbox event.
   *
   * @param caller verified caller
   * @param action audit action
   * @param eventType outbox event type
   * @param requestId the request
   * @param legalEntityId legal entity
   * @param status new status
   * @param version new row version
   * @param currency currency
   * @param policySequence frozen policy sequence
   * @param reasonCode bounded refusal reason, or {@code null}
   * @param at server instant
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void requestChanged(
      PayrollCaller caller,
      String action,
      String eventType,
      UUID requestId,
      UUID legalEntityId,
      String status,
      int version,
      String currency,
      int policySequence,
      String reasonCode,
      Instant at) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", SCHEMA_VERSION);
    metadata.put("legalEntityId", legalEntityId.toString());
    metadata.put("requestId", requestId.toString());
    metadata.put("status", status);
    metadata.put("version", version);
    metadata.put("currency", currency);
    metadata.put("policySequence", policySequence);
    if (reasonCode != null) {
      metadata.put("reasonCode", reasonCode);
    }
    audit.record(
        event(
            caller,
            action,
            "salary-advance-request",
            requestId,
            at,
            metadata,
            "DIVALHR-SALARY-ADVANCE-REQUEST\nversion=1\naction="
                + action
                + "\nrequest="
                + requestId
                + "\nstatus="
                + status
                + "\nrowVersion="
                + version
                + "\n"));
    outbox.append(envelope(caller, eventType, requestId, at, metadata));
  }

  private static Map<String, Object> policyMetadata(
      UUID legalEntityId, int sequence, String currency) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", SCHEMA_VERSION);
    metadata.put("legalEntityId", legalEntityId.toString());
    metadata.put("policySequence", sequence);
    metadata.put("currency", currency);
    return metadata;
  }

  private static AuditEvent event(
      PayrollCaller caller,
      String action,
      String resourceType,
      UUID resourceId,
      Instant at,
      Map<String, Object> metadata,
      String integrity) {
    return new AuditEvent(
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
        Fingerprints.sha256(integrity));
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
