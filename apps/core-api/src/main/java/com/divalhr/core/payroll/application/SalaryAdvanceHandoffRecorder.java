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
 * Audit records and internal outbox events of the controlled partner handoff (MVP-120B, ADR 0042).
 * Metadata and event data are an allow-list of identifiers and closed codes: {@code schemaVersion},
 * {@code legalEntityId}, {@code requestId}, {@code handoffId}, {@code state}, {@code reviewState},
 * {@code version}, {@code currency}, {@code reasonCode}, {@code outcomeCode}, {@code attemptNumber}
 * and {@code actorRole}. They never carry an amount, a ceiling, a date of employment, an employee
 * number, a payload or its digest, a signature, a header, a partner reference or response, a
 * consent detail, an endpoint or a key. The events are internal: no webhook or WhatsApp type maps
 * them and none is sent to any partner. A failing audit or outbox write fails the whole
 * transaction, so the state change rolls back and the idempotency key stays reusable.
 */
@Component
public class SalaryAdvanceHandoffRecorder {

  /** Schema version of the audit metadata and event payloads. */
  public static final int SCHEMA_VERSION = 1;

  /** Audit actions. */
  public static final String PREVIEW = "salary-advance-handoff.preview";

  public static final String AUTHORIZE = "salary-advance-handoff.authorize";
  public static final String READ = "salary-advance-handoff.read";
  public static final String WITHDRAW = "salary-advance-handoff.withdraw";
  public static final String RECONCILE = "salary-advance-handoff.reconcile";
  public static final String DELIVERY = "salary-advance-handoff.delivery";
  public static final String PARTNER_STATUS = "salary-advance-handoff.partner-status";
  public static final String PARTNER_STATUS_REFUSED =
      "salary-advance-handoff.partner-status-refused";

  /** Internal event types. */
  public static final String AUTHORIZED_EVENT = "payroll.salary-advance-handoff.authorized.v1";

  public static final String WITHDRAWN_EVENT = "payroll.salary-advance-handoff.withdrawn.v1";
  public static final String STATE_CHANGED_EVENT =
      "payroll.salary-advance-handoff.state-changed.v1";

  /** The actors without a person: the delivery worker and the signed partner callback. */
  public static final String WORKER_ACTOR = "system:partner-handoff-worker";

  public static final String PARTNER_ACTOR = "system:synthetic-partner";

  private static final String SOURCE = "core-api/payroll";

  private static final Set<String> ALLOWED =
      Set.of(
          "schemaVersion",
          "legalEntityId",
          "requestId",
          "handoffId",
          "state",
          "reviewState",
          "version",
          "currency",
          "reasonCode",
          "outcomeCode",
          "attemptNumber",
          "actorRole",
          "view");

  private final AuditRecorder audit;
  private final OutboxWriter outbox;

  /**
   * Creates the recorder.
   *
   * @param audit audit recorder (mandatory transaction)
   * @param outbox outbox writer (mandatory transaction)
   */
  public SalaryAdvanceHandoffRecorder(AuditRecorder audit, OutboxWriter outbox) {
    this.audit = audit;
    this.outbox = outbox;
  }

  /**
   * One recorded state change: identifiers and closed codes only.
   *
   * @param handoffId the handoff
   * @param requestId the request
   * @param legalEntityId the legal entity
   * @param currency the currency (never an amount)
   * @param state the new delivery state
   * @param reviewState the new review state, or {@code null}
   * @param version the new row version
   * @param outcomeCode the allow-listed outcome, or {@code null}
   * @param reasonCode the closed partner review reason, or {@code null}
   * @param attemptNumber the attempt number, or 0
   * @param actorRole {@code EMPLOYEE}, {@code TENANT_ADMIN}, {@code SYSTEM} or {@code PARTNER}
   * @param at server instant
   */
  public record Change(
      UUID handoffId,
      UUID requestId,
      UUID legalEntityId,
      String currency,
      String state,
      String reviewState,
      int version,
      String outcomeCode,
      String reasonCode,
      int attemptNumber,
      String actorRole,
      Instant at) {}

  /**
   * The fail-closed disclosure audit of a read or preview: the view only, never an amount or an
   * identifier of the data shown beyond the resource.
   *
   * @param caller verified caller
   * @param action audit action
   * @param resourceId the resource (a request, a handoff or the caller's employee record)
   * @param view the kind of view
   * @param at server instant
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void disclosed(
      PayrollCaller caller, String action, UUID resourceId, String view, Instant at) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", SCHEMA_VERSION);
    metadata.put("view", view);
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            at,
            caller.subject(),
            action,
            "salary-advance-handoff",
            resourceId,
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(
                "DIVALHR-SALARY-ADVANCE-HANDOFF-DISCLOSURE\nversion=1\naction="
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
   * Records an authorization by the employee: the audit record and the internal outbox event.
   *
   * @param caller verified caller (the employee)
   * @param change the new state
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void authorized(PayrollCaller caller, Change change) {
    write(
        caller.subject(),
        AUTHORIZE,
        caller.tenant(),
        caller.correlationId(),
        AUTHORIZED_EVENT,
        change);
  }

  /**
   * Records a withdrawal before receipt: the audit record and the internal outbox event.
   *
   * @param caller verified caller (the employee)
   * @param change the new state
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void withdrawn(PayrollCaller caller, Change change) {
    write(
        caller.subject(),
        WITHDRAW,
        caller.tenant(),
        caller.correlationId(),
        WITHDRAWN_EVENT,
        change);
  }

  /**
   * Records an administrator reconcile or retry: the audit record and the internal outbox event.
   *
   * @param caller verified caller (the administrator)
   * @param change the new state
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void reconciled(PayrollCaller caller, Change change) {
    write(
        caller.subject(),
        RECONCILE,
        caller.tenant(),
        caller.correlationId(),
        STATE_CHANGED_EVENT,
        change);
  }

  /**
   * Records a worker delivery state change.
   *
   * @param tenant the tenant of the handoff
   * @param correlationId the worker cycle's correlation id
   * @param change the new state
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void delivered(TenantId tenant, String correlationId, Change change) {
    write(WORKER_ACTOR, DELIVERY, tenant, correlationId, STATE_CHANGED_EVENT, change);
  }

  /**
   * Records a signed partner review status.
   *
   * @param tenant the tenant of the handoff
   * @param correlationId the callback's correlation id
   * @param change the new review state
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void partnerStatus(TenantId tenant, String correlationId, Change change) {
    write(PARTNER_ACTOR, PARTNER_STATUS, tenant, correlationId, STATE_CHANGED_EVENT, change);
  }

  /**
   * The audit record of a retention operation by the tenant administrator: counts and safe
   * identifiers only.
   *
   * @param caller verified caller (the administrator)
   * @param action audit action
   * @param resourceId the policy owner (tenant) or the preview
   * @param counts closed numeric or view values (never an identity, amount or digest)
   * @param at server instant
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void retention(
      PayrollCaller caller,
      String action,
      UUID resourceId,
      Map<String, Object> counts,
      Instant at) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", SCHEMA_VERSION);
    metadata.putAll(counts);
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            at,
            caller.subject(),
            action,
            "salary-advance-retention",
            resourceId,
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(
                "DIVALHR-SALARY-ADVANCE-RETENTION\nversion=1\naction="
                    + action
                    + "\nresource="
                    + resourceId
                    + "\nat="
                    + at
                    + "\nby="
                    + caller.subject()
                    + "\n")));
  }

  /**
   * Records a refused signed partner status (illegal, stale, terminal or conflicting): an audit
   * record with identifiers and closed codes only, never the request content, and no event.
   *
   * @param tenant the tenant of the handoff
   * @param correlationId the callback's correlation id
   * @param change the handoff and the closed status the partner attempted
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void partnerStatusRefused(TenantId tenant, String correlationId, Change change) {
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            change.at(),
            PARTNER_ACTOR,
            PARTNER_STATUS_REFUSED,
            "salary-advance-handoff",
            change.handoffId(),
            tenant.value(),
            "DENIED",
            correlationId,
            data(change),
            Fingerprints.sha256(
                "DIVALHR-SALARY-ADVANCE-HANDOFF-REFUSED\nversion=1\nhandoff="
                    + change.handoffId()
                    + "\nrowVersion="
                    + change.version()
                    + "\n")));
  }

  private void write(
      String actor,
      String action,
      TenantId tenant,
      String correlationId,
      String eventType,
      Change change) {
    Map<String, Object> data = data(change);
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            change.at(),
            actor,
            action,
            "salary-advance-handoff",
            change.handoffId(),
            tenant.value(),
            "SUCCESS",
            correlationId,
            data,
            Fingerprints.sha256(
                "DIVALHR-SALARY-ADVANCE-HANDOFF\nversion=1\naction="
                    + action
                    + "\nhandoff="
                    + change.handoffId()
                    + "\nstate="
                    + change.state()
                    + "\nrowVersion="
                    + change.version()
                    + "\n")));
    outbox.append(
        new EventEnvelope(
            UUID.randomUUID().toString(),
            eventType,
            SCHEMA_VERSION,
            tenant.toString(),
            SOURCE,
            change.handoffId().toString(),
            change.at().toString(),
            correlationId,
            null,
            data));
  }

  private static Map<String, Object> data(Change change) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("schemaVersion", SCHEMA_VERSION);
    data.put("legalEntityId", change.legalEntityId().toString());
    data.put("requestId", change.requestId().toString());
    data.put("handoffId", change.handoffId().toString());
    data.put("state", change.state());
    if (change.reviewState() != null) {
      data.put("reviewState", change.reviewState());
    }
    data.put("version", change.version());
    data.put("currency", change.currency());
    if (change.reasonCode() != null) {
      data.put("reasonCode", change.reasonCode());
    }
    if (change.outcomeCode() != null) {
      data.put("outcomeCode", change.outcomeCode());
    }
    if (change.attemptNumber() > 0) {
      data.put("attemptNumber", change.attemptNumber());
    }
    data.put("actorRole", change.actorRole());
    for (String key : data.keySet()) {
      if (!ALLOWED.contains(key)) {
        throw new IllegalArgumentException("handoff metadata key outside the allow-list");
      }
    }
    return data;
  }
}
