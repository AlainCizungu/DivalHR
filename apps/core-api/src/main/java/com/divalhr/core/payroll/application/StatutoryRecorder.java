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
 * Audit records and internal outbox events of the statutory preview (MVP-094B, ADR 0034 section 8).
 * Metadata and event data are an allow-list ({@link #ALLOWED}): identifiers, the pack version, the
 * period, the currency, the status, the version and aggregate counts. They never carry an amount,
 * an employee number, a classification, a profile flag, a reviewer identity, a digest, a rule or a
 * trace; a key outside the allow-list is refused before anything is written. The events are
 * internal: no webhook or WhatsApp type maps them.
 */
@Component
public class StatutoryRecorder {

  /** Schema version of the audit metadata and event payloads. */
  public static final int SCHEMA_VERSION = 1;

  /** Audit action of a rule pack disclosure. */
  public static final String PACK_READ = "statutory-rule-pack.read";

  /** Audit action of an activation disclosure. */
  public static final String ACTIVATION_READ = "statutory-activation.read";

  /** Audit action of an activation. */
  public static final String ACTIVATION_CREATE = "statutory-activation.create";

  /** Audit action of a profile disclosure. */
  public static final String PROFILE_READ = "statutory-profile.read";

  /** Audit action of a profile change. */
  public static final String PROFILE_CHANGE = "statutory-profile.change";

  /** Audit action of a calculation disclosure. */
  public static final String CALCULATION_READ = "statutory-calculation.read";

  /** Audit action of a calculation creation. */
  public static final String CALCULATION_CREATE = "statutory-calculation.create";

  /** Internal event of an activation. */
  public static final String ACTIVATION_EVENT = "payroll.statutory-activation.created.v1";

  /** Internal event of a profile change. */
  public static final String PROFILE_EVENT = "payroll.statutory-profile.changed.v1";

  /** Internal event of a created calculation. */
  public static final String CALCULATION_EVENT = "payroll.statutory-calculation.created.v1";

  /** The only keys audit metadata and event data may carry. */
  public static final Set<String> ALLOWED =
      Set.of(
          "schemaVersion",
          "legalEntityId",
          "runId",
          "calculationId",
          "activationId",
          "packId",
          "packVersion",
          "employeeId",
          "sequence",
          "version",
          "period",
          "currency",
          "status",
          "supportedCount",
          "excludedCount",
          "blockedCount",
          "supersededCalculationId",
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
  public StatutoryRecorder(AuditRecorder audit, OutboxWriter outbox) {
    this.audit = audit;
    this.outbox = outbox;
  }

  /**
   * One recorded activation.
   *
   * @param legalEntityId legal entity
   * @param activationId the activation
   * @param packId the activated pack
   * @param packVersion its version
   * @param period first month, {@code YYYY-MM}
   * @param sequence its sequence (the activation version after it)
   * @param at server instant
   */
  public record ActivationRecorded(
      UUID legalEntityId,
      UUID activationId,
      UUID packId,
      int packVersion,
      String period,
      int sequence,
      Instant at) {}

  /**
   * One recorded profile entry, without any classification or flag.
   *
   * @param employeeId employee
   * @param profileId the entry
   * @param sequence its sequence (the profile version after it)
   * @param at server instant
   */
  public record ProfileRecorded(UUID employeeId, UUID profileId, int sequence, Instant at) {}

  /**
   * One created calculation, without any amount or employee data.
   *
   * @param legalEntityId legal entity
   * @param runId source run
   * @param calculationId the calculation
   * @param packId the pack
   * @param packVersion its version
   * @param period {@code YYYY-MM}
   * @param version its version in the chain
   * @param supportedCount supported lines
   * @param excludedCount excluded lines
   * @param supersededCalculationId the version it superseded, or {@code null}
   * @param calculationDigest the digest of the command (stored only as the integrity reference)
   * @param at server instant
   */
  public record CalculationRecorded(
      UUID legalEntityId,
      UUID runId,
      UUID calculationId,
      UUID packId,
      int packVersion,
      String period,
      int version,
      int supportedCount,
      int excludedCount,
      UUID supersededCalculationId,
      String calculationDigest,
      Instant at) {

    @Override
    public String toString() {
      return "StatutoryCalculationRecorded[" + calculationId + "]";
    }
  }

  /**
   * The fail-closed disclosure audit of a read or preview: the view, the identifiers and counts
   * only.
   *
   * @param caller verified caller
   * @param action one of the {@code *.read} actions
   * @param resourceType {@code statutory-rule-pack}, {@code statutory-activation}, {@code
   *     statutory-profile} or {@code statutory-calculation}
   * @param resourceId the resource (the pack, the legal entity, the employee or the run)
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
                "DIVALHR-STATUTORY-DISCLOSURE\nversion=1\naction="
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
   * Records an activation: the audit record and the internal outbox event.
   *
   * @param caller verified caller
   * @param recorded the activation
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void activated(PayrollCaller caller, ActivationRecorded recorded) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", SCHEMA_VERSION);
    metadata.put("legalEntityId", recorded.legalEntityId().toString());
    metadata.put("activationId", recorded.activationId().toString());
    metadata.put("packId", recorded.packId().toString());
    metadata.put("packVersion", recorded.packVersion());
    metadata.put("period", recorded.period());
    metadata.put("sequence", recorded.sequence());
    metadata.put("version", recorded.sequence());
    requireAllowed(metadata);
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            recorded.at(),
            caller.subject(),
            ACTIVATION_CREATE,
            "statutory-activation",
            recorded.activationId(),
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(
                "DIVALHR-STATUTORY-ACTIVATED\nversion=1\nactivation="
                    + recorded.activationId()
                    + "\npack="
                    + recorded.packId()
                    + "\nsequence="
                    + recorded.sequence()
                    + "\n")));
    outbox.append(
        envelope(caller, ACTIVATION_EVENT, recorded.activationId(), recorded.at(), metadata));
  }

  /**
   * Records a profile change: the audit record and the internal outbox event.
   *
   * @param caller verified caller
   * @param recorded the profile entry
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void profileChanged(PayrollCaller caller, ProfileRecorded recorded) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", SCHEMA_VERSION);
    metadata.put("employeeId", recorded.employeeId().toString());
    metadata.put("sequence", recorded.sequence());
    metadata.put("version", recorded.sequence());
    requireAllowed(metadata);
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            recorded.at(),
            caller.subject(),
            PROFILE_CHANGE,
            "statutory-profile",
            recorded.employeeId(),
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(
                "DIVALHR-STATUTORY-PROFILE-CHANGED\nversion=1\nprofile="
                    + recorded.profileId()
                    + "\nsequence="
                    + recorded.sequence()
                    + "\n")));
    outbox.append(envelope(caller, PROFILE_EVENT, recorded.employeeId(), recorded.at(), metadata));
  }

  /**
   * Records a created calculation: the audit record and the internal outbox event.
   *
   * @param caller verified caller
   * @param recorded the calculation
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void calculationCreated(PayrollCaller caller, CalculationRecorded recorded) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", SCHEMA_VERSION);
    metadata.put("legalEntityId", recorded.legalEntityId().toString());
    metadata.put("runId", recorded.runId().toString());
    metadata.put("calculationId", recorded.calculationId().toString());
    metadata.put("packId", recorded.packId().toString());
    metadata.put("packVersion", recorded.packVersion());
    metadata.put("period", recorded.period());
    metadata.put("currency", "CDF");
    metadata.put("status", "DRAFT");
    metadata.put("version", recorded.version());
    metadata.put("supportedCount", recorded.supportedCount());
    metadata.put("excludedCount", recorded.excludedCount());
    metadata.put("blockedCount", 0);
    if (recorded.supersededCalculationId() != null) {
      metadata.put("supersededCalculationId", recorded.supersededCalculationId().toString());
    }
    requireAllowed(metadata);
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            recorded.at(),
            caller.subject(),
            CALCULATION_CREATE,
            "statutory-calculation",
            recorded.calculationId(),
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(
                "DIVALHR-STATUTORY-CALCULATION-CREATED\nversion=1\ncalculation="
                    + recorded.calculationId()
                    + "\ncommand="
                    + recorded.calculationDigest()
                    + "\n")));
    outbox.append(
        envelope(caller, CALCULATION_EVENT, recorded.calculationId(), recorded.at(), metadata));
  }

  private static void requireAllowed(Map<String, Object> metadata) {
    for (String key : metadata.keySet()) {
      if (!ALLOWED.contains(key)) {
        throw new IllegalArgumentException("statutory metadata key outside the allow-list");
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
