package com.divalhr.core.integrations.partner.referral;

import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audit records and internal outbox events of the consented partner referral (MVP-120F, ADR 0045).
 * Metadata and event data are an allow-list of identifiers, closed codes, the names of the
 * disclosed fields and the canonical disclosure digest. They never carry a name, an email address,
 * a phone number, an organization name, a disclosed value, a preview digest, a signature, an
 * endpoint or a key. The events are internal: none is sent to any partner. A failing audit or
 * outbox write fails the whole transaction, so the state change rolls back and the idempotency key
 * stays reusable.
 */
@Component
public class PartnerReferralRecorder {

  /** Schema version of the audit metadata and event payloads. */
  public static final int SCHEMA_VERSION = 1;

  /** Audit actions. */
  public static final String SUBMIT = "partner-referral.submit";

  public static final String WITHDRAW = "partner-referral.withdraw";
  public static final String DELIVERY = "partner-referral.delivery";

  /** Internal event types. */
  public static final String SUBMITTED_EVENT = "integrations.partner-referral.submitted.v1";

  public static final String WITHDRAWN_EVENT = "integrations.partner-referral.withdrawn.v1";
  public static final String STATE_CHANGED_EVENT = "integrations.partner-referral.state-changed.v1";

  /** The actor without a person: the delivery worker. */
  public static final String WORKER_ACTOR = "system:partner-referral-worker";

  private static final String SOURCE = "core-api/integrations";

  private static final Set<String> ALLOWED =
      Set.of(
          "schemaVersion",
          "referralId",
          "state",
          "version",
          "outcomeCode",
          "attemptNumber",
          "actorRole",
          "disclosedFields",
          "disclosureDigest");

  private final AuditRecorder audit;
  private final OutboxWriter outbox;

  /**
   * Creates the recorder.
   *
   * @param audit audit recorder (mandatory transaction)
   * @param outbox outbox writer (mandatory transaction)
   */
  public PartnerReferralRecorder(AuditRecorder audit, OutboxWriter outbox) {
    this.audit = audit;
    this.outbox = outbox;
  }

  /**
   * One recorded change: identifiers and closed codes only.
   *
   * @param referralId the referral
   * @param state the new state
   * @param version the new row version
   * @param outcomeCode the allow-listed outcome, or {@code null}
   * @param attemptNumber the attempt number, or 0
   * @param actorRole {@code EMPLOYEE} or {@code SYSTEM}
   * @param disclosedFields the names of the disclosed fields (submission only), or {@code null}
   * @param disclosureDigest the canonical disclosure digest (submission only), or {@code null}
   * @param at server instant
   */
  public record Change(
      UUID referralId,
      String state,
      int version,
      String outcomeCode,
      int attemptNumber,
      String actorRole,
      List<String> disclosedFields,
      String disclosureDigest,
      Instant at) {}

  /**
   * Records a submission.
   *
   * @param caller verified employee
   * @param change the new state
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void submitted(ReferralCaller caller, Change change) {
    write(
        caller.subject(), SUBMIT, caller.tenant(), caller.correlationId(), SUBMITTED_EVENT, change);
  }

  /**
   * Records a withdrawal before delivery.
   *
   * @param caller verified employee
   * @param change the new state
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void withdrawn(ReferralCaller caller, Change change) {
    write(
        caller.subject(),
        WITHDRAW,
        caller.tenant(),
        caller.correlationId(),
        WITHDRAWN_EVENT,
        change);
  }

  /**
   * Records a delivery state change by the worker.
   *
   * @param tenant the referral's tenant
   * @param correlationId the worker cycle's correlation id
   * @param change the new state
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void delivered(TenantId tenant, String correlationId, Change change) {
    write(WORKER_ACTOR, DELIVERY, tenant, correlationId, STATE_CHANGED_EVENT, change);
  }

  /**
   * Records that a platform administrator read one referral (by reference only).
   *
   * @param subject verified platform administrator
   * @param correlationId request correlation id
   * @param referralId the referral that was read
   * @param at server instant
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void operatorRead(String subject, String correlationId, UUID referralId, Instant at) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("schemaVersion", SCHEMA_VERSION);
    data.put("referralId", referralId.toString());
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            at,
            subject,
            "partner-referral.platform-read",
            "partner-referral",
            referralId,
            new UUID(0L, 0L),
            "SUCCESS",
            correlationId,
            data,
            Fingerprints.sha256(
                "DIVALHR-PARTNER-REFERRAL\nversion=1\naction=platform-read\nreferral="
                    + referralId
                    + "\nby="
                    + subject
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
            "partner-referral",
            change.referralId(),
            tenant.value(),
            "SUCCESS",
            correlationId,
            data,
            Fingerprints.sha256(
                "DIVALHR-PARTNER-REFERRAL\nversion=1\naction="
                    + action
                    + "\nreferral="
                    + change.referralId()
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
            change.referralId().toString(),
            change.at().toString(),
            correlationId,
            null,
            data));
  }

  private static Map<String, Object> data(Change change) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("schemaVersion", SCHEMA_VERSION);
    data.put("referralId", change.referralId().toString());
    data.put("state", change.state());
    data.put("version", change.version());
    if (change.outcomeCode() != null) {
      data.put("outcomeCode", change.outcomeCode());
    }
    if (change.attemptNumber() > 0) {
      data.put("attemptNumber", change.attemptNumber());
    }
    data.put("actorRole", change.actorRole());
    if (change.disclosedFields() != null) {
      data.put("disclosedFields", List.copyOf(change.disclosedFields()));
    }
    if (change.disclosureDigest() != null) {
      data.put("disclosureDigest", change.disclosureDigest());
    }
    if (!ALLOWED.containsAll(data.keySet())) {
      throw new IllegalStateException("referral audit data outside the allow-list");
    }
    return data;
  }
}
