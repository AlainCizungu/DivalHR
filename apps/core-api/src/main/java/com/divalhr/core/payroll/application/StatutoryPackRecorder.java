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
 * Audit records and internal outbox events of statutory pack governance (MVP-097A, D97A-11).
 * Platform scope: the audit and outbox rows carry the platform sentinel {@link #PLATFORM_SCOPE},
 * never a tenant. Metadata and event data are an allow-list ({@link #ALLOWED}): the pack row, its
 * code, versions, country, state and digests. They never carry a rate, a formula, an amount,
 * personal data, a reviewer note, a source URL or a reference value; a key outside the allow-list is
 * refused before anything is written. The events are internal: no webhook or WhatsApp type maps
 * them.
 */
@Component
public class StatutoryPackRecorder {

  /** The platform audit and outbox scope: not a tenant. */
  public static final UUID PLATFORM_SCOPE = new UUID(0L, 0L);

  /** Schema version of the audit metadata and event payloads. */
  public static final int SCHEMA_VERSION = 1;

  /** Audit action of a pack read. */
  public static final String READ = "statutory-pack.read";

  /** Audit action of a validation. */
  public static final String VALIDATE = "statutory-pack.validate";

  /** Audit action of a submission for review. */
  public static final String SUBMIT_REVIEW = "statutory-pack.submit-review";

  /** Audit action of an approval. */
  public static final String APPROVE = "statutory-pack.approve";

  /** Audit action of a rejection. */
  public static final String REJECT = "statutory-pack.reject";

  /** Audit action of a scheduling. */
  public static final String SCHEDULE = "statutory-pack.schedule";

  /** Audit action of an activation. */
  public static final String ACTIVATE = "statutory-pack.activate";

  /** Audit action of a retirement. */
  public static final String RETIRE = "statutory-pack.retire";

  /** Audit action of a tenant read of the applicable pack. */
  public static final String APPLICABILITY_READ = "statutory-pack.applicability-read";

  /** Internal event: a pack version validated technically. */
  public static final String VALIDATED_EVENT = "payroll.statutory-pack.validated.v1";

  /** Internal event: a pack version was independently reviewed (approved). */
  public static final String REVIEWED_EVENT = "payroll.statutory-pack.reviewed.v1";

  /** Internal event: a pack version became ACTIVE. */
  public static final String ACTIVATED_EVENT = "payroll.statutory-pack.activated.v1";

  /** Internal event: a pack version was retired. */
  public static final String RETIRED_EVENT = "payroll.statutory-pack.retired.v1";

  /** The only keys audit metadata and event data may carry. */
  public static final Set<String> ALLOWED =
      Set.of(
          "schemaVersion",
          "packId",
          "packCode",
          "packVersion",
          "country",
          "state",
          "previousState",
          "packSha256",
          "fixtureSha256",
          "legalEntityId",
          "period",
          "view",
          "page",
          "resultCount",
          "outcome");

  private static final String SOURCE = "core-api/payroll";

  private final AuditRecorder audit;
  private final OutboxWriter outbox;

  /**
   * Creates the recorder.
   *
   * @param audit audit recorder (mandatory transaction)
   * @param outbox outbox writer (mandatory transaction)
   */
  public StatutoryPackRecorder(AuditRecorder audit, OutboxWriter outbox) {
    this.audit = audit;
    this.outbox = outbox;
  }

  /**
   * Records an audited command and, when an event type is given, its internal event.
   *
   * @param actor verified platform administrator subject
   * @param action audit action
   * @param packId the pack row
   * @param metadata allow-listed metadata
   * @param eventType internal event type, or {@code null} for none
   * @param correlationId request correlation ID
   * @param at server instant
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void command(
      String actor,
      String action,
      UUID packId,
      Map<String, Object> metadata,
      String eventType,
      String correlationId,
      Instant at) {
    Map<String, Object> checked = checked(metadata);
    record(actor, action, packId, checked, correlationId, at, "DIVALHR-STATUTORY-PACK-COMMAND");
    if (eventType != null) {
      outbox.append(
          new EventEnvelope(
              UUID.randomUUID().toString(),
              eventType,
              SCHEMA_VERSION,
              PLATFORM_SCOPE.toString(),
              SOURCE,
              packId.toString(),
              at.toString(),
              correlationId,
              null,
              checked));
    }
  }

  /**
   * Records a read disclosure (fail closed: the read commits only with its audit).
   *
   * @param actor verified subject
   * @param action audit action
   * @param resourceId resource (the pack row, or the legal entity for a tenant read)
   * @param tenant the verified tenant of a tenant read, or {@code null} for a platform read
   * @param metadata allow-listed metadata
   * @param correlationId request correlation ID
   * @param at server instant
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void disclosed(
      String actor,
      String action,
      UUID resourceId,
      UUID tenant,
      Map<String, Object> metadata,
      String correlationId,
      Instant at) {
    Map<String, Object> checked = checked(metadata);
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            at,
            actor,
            action,
            "statutory-pack",
            resourceId,
            tenant == null ? PLATFORM_SCOPE : tenant,
            "SUCCESS",
            correlationId,
            checked,
            Fingerprints.sha256(
                "DIVALHR-STATUTORY-PACK-DISCLOSURE\nversion=1\naction="
                    + action
                    + "\nresource="
                    + resourceId
                    + "\nby="
                    + actor
                    + "\n")));
  }

  private void record(
      String actor,
      String action,
      UUID packId,
      Map<String, Object> metadata,
      String correlationId,
      Instant at,
      String header) {
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            at,
            actor,
            action,
            "statutory-pack",
            packId,
            PLATFORM_SCOPE,
            "SUCCESS",
            correlationId,
            metadata,
            Fingerprints.sha256(
                header
                    + "\nversion=1\naction="
                    + action
                    + "\npack="
                    + packId
                    + "\nstate="
                    + metadata.get("state")
                    + "\n")));
  }

  private static Map<String, Object> checked(Map<String, Object> metadata) {
    Map<String, Object> copy = new LinkedHashMap<>();
    copy.put("schemaVersion", SCHEMA_VERSION);
    copy.putAll(metadata);
    for (String key : copy.keySet()) {
      if (!ALLOWED.contains(key)) {
        throw new IllegalArgumentException("statutory pack metadata key outside the allow-list");
      }
    }
    return copy;
  }
}
