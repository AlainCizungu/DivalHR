package com.divalhr.core.documents.application;

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
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Audit and outbox records of contract templates and contracts (MVP-030, §9), written in the
 * caller's transaction (fail closed).
 *
 * <ul>
 *   <li>Audit metadata is an allow-list: {@code schemaVersion}, closed states and transitions,
 *       versions and counts. The integrity hash (body, snapshot or evidence digest, or a digest of
 *       identifiers) goes to the record's own {@code after_state_sha256}.
 *   <li>Outbox data is identifiers and {@code schemaVersion} only.
 *   <li>Never a type, language, date, title, body, name, subject, reason or statement wording.
 * </ul>
 */
@Component
public class ContractEvents {

  /** Event and audit schema version. */
  static final int SCHEMA_VERSION = 1;

  /** Producing module of outbox events. */
  static final String SOURCE = "core-api/documents";

  /** A template version was approved. */
  public static final String VERSION_APPROVED = "documents.contract-template.version-approved.v1";

  /** A template version was retired. */
  public static final String VERSION_RETIRED = "documents.contract-template.version-retired.v1";

  /** A contract was issued. */
  public static final String ISSUED = "documents.contract.issued.v1";

  /** A contract was acknowledged. */
  public static final String ACKNOWLEDGED = "documents.contract.acknowledged.v1";

  /** A contract was voided. */
  public static final String VOIDED = "documents.contract.voided.v1";

  static final String TEMPLATE = "contract-template";
  static final String TEMPLATE_VERSION = "contract-template-version";
  static final String CONTRACT = "contract";

  private final AuditRecorder audit;
  private final OutboxWriter outbox;

  /**
   * Creates the recorder.
   *
   * @param audit audit recorder
   * @param outbox outbox writer
   */
  public ContractEvents(AuditRecorder audit, OutboxWriter outbox) {
    this.audit = audit;
    this.outbox = outbox;
  }

  /**
   * A digest of identifiers (and closed values), for records whose state has no digest of its own.
   *
   * @param kind record kind
   * @param values identifiers and closed values
   * @return hex digest
   */
  static String identifiers(String kind, Object... values) {
    StringBuilder text = new StringBuilder("DIVALHR-CONTRACT-AUDIT\nversion=1\nkind=");
    text.append(kind).append('\n');
    for (Object value : values) {
      text.append(value).append('\n');
    }
    return Fingerprints.sha256(text.toString());
  }

  /**
   * A template was created.
   *
   * @param caller verified caller
   * @param templateId template
   * @param now time
   */
  void templateCreated(DocumentsCaller caller, UUID templateId, Instant now) {
    record(
        caller,
        "contract-template.create",
        TEMPLATE,
        templateId,
        metadata(Map.of("version", 0)),
        identifiers("template", templateId),
        now);
  }

  /**
   * A draft version was created, edited or deleted.
   *
   * @param caller verified caller
   * @param action {@code contract-template-version.create|update|delete}
   * @param versionId version
   * @param versionNumber number per language
   * @param rowVersion row version after the write (before it for a delete)
   * @param bodySha256 text digest
   * @param now time
   */
  void draftWritten(
      DocumentsCaller caller,
      String action,
      UUID versionId,
      int versionNumber,
      long rowVersion,
      String bodySha256,
      Instant now) {
    record(
        caller,
        action,
        TEMPLATE_VERSION,
        versionId,
        metadata(Map.of("versionNumber", versionNumber, "version", rowVersion)),
        bodySha256,
        now);
  }

  /**
   * A version was approved (retiring the previous approved one, if any) or retired.
   *
   * @param caller verified caller
   * @param approved whether this is an approval (else a retirement)
   * @param templateId template
   * @param versionId version
   * @param rowVersion row version after the transition
   * @param retiredPrevious versions retired by an approval (0 or 1)
   * @param bodySha256 text digest
   * @param now time
   */
  void versionTransitioned(
      DocumentsCaller caller,
      boolean approved,
      UUID templateId,
      UUID versionId,
      long rowVersion,
      int retiredPrevious,
      String bodySha256,
      Instant now) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("from", approved ? "DRAFT" : "APPROVED");
    values.put("to", approved ? "APPROVED" : "RETIRED");
    values.put("version", rowVersion);
    if (approved) {
      values.put("retiredPrevious", retiredPrevious);
    }
    record(
        caller,
        approved ? "contract-template-version.approve" : "contract-template-version.retire",
        TEMPLATE_VERSION,
        versionId,
        metadata(values),
        bodySha256,
        now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("templateId", templateId.toString());
    data.put("versionId", versionId.toString());
    publish(
        caller, approved ? VERSION_APPROVED : VERSION_RETIRED, templateId.toString(), data, now);
  }

  /**
   * A contract was issued.
   *
   * @param caller verified caller
   * @param contractId contract
   * @param employeeId employee
   * @param employmentId employment
   * @param snapshotSha256 snapshot digest
   * @param now time
   */
  void issued(
      DocumentsCaller caller,
      UUID contractId,
      UUID employeeId,
      UUID employmentId,
      String snapshotSha256,
      Instant now) {
    record(
        caller,
        "contract.issue",
        CONTRACT,
        contractId,
        metadata(Map.of("to", "ISSUED", "version", 0)),
        snapshotSha256,
        now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("employeeId", employeeId.toString());
    data.put("employmentId", employmentId.toString());
    data.put("contractId", contractId.toString());
    publish(caller, ISSUED, employeeId.toString(), data, now);
  }

  /**
   * A contract was voided (the reason code stays in the contract row, never in the audit).
   *
   * @param caller verified caller
   * @param contractId contract
   * @param employeeId employee
   * @param rowVersion row version after the transition
   * @param now time
   */
  void voided(
      DocumentsCaller caller, UUID contractId, UUID employeeId, long rowVersion, Instant now) {
    record(
        caller,
        "contract.void",
        CONTRACT,
        contractId,
        metadata(Map.of("from", "ISSUED", "to", "VOID", "version", rowVersion)),
        identifiers("void", contractId, rowVersion),
        now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("employeeId", employeeId.toString());
    data.put("contractId", contractId.toString());
    publish(caller, VOIDED, employeeId.toString(), data, now);
  }

  /**
   * A contract was acknowledged.
   *
   * @param caller verified caller (the employee)
   * @param contractId contract
   * @param employeeId employee
   * @param rowVersion row version after the transition
   * @param evidenceSha256 evidence digest
   * @param now time
   */
  void acknowledged(
      DocumentsCaller caller,
      UUID contractId,
      UUID employeeId,
      long rowVersion,
      String evidenceSha256,
      Instant now) {
    record(
        caller,
        "contract.acknowledge",
        CONTRACT,
        contractId,
        metadata(
            Map.of(
                "from",
                "ISSUED",
                "to",
                "ACKNOWLEDGED",
                "version",
                rowVersion,
                "statementVersion",
                1)),
        evidenceSha256,
        now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("employeeId", employeeId.toString());
    data.put("contractId", contractId.toString());
    publish(caller, ACKNOWLEDGED, employeeId.toString(), data, now);
  }

  /**
   * A disclosure (read or preview of Restricted HR data), recorded in the read's transaction.
   *
   * @param caller verified caller
   * @param action {@code contract.preview|read|self-read}
   * @param resourceType resource type
   * @param resourceId resource (the employee, or the contract)
   * @param view view name
   * @param page {@code first} or {@code next}
   * @param ids disclosed identifiers
   * @param now time
   */
  void disclosed(
      DocumentsCaller caller,
      String action,
      String resourceType,
      UUID resourceId,
      String view,
      String page,
      List<UUID> ids,
      Instant now) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("view", view);
    values.put("page", page);
    values.put("resultCount", ids.size());
    StringBuilder text = new StringBuilder("DIVALHR-CONTRACT-DISCLOSURE\nversion=1\nview=");
    text.append(view).append('\n');
    ids.forEach(id -> text.append("id=").append(id).append('\n'));
    record(
        caller,
        action,
        resourceType,
        resourceId,
        metadata(values),
        Fingerprints.sha256(text.toString()),
        now);
  }

  /**
   * An integrity mismatch found by the read-only job (FAILURE result; contract ID only).
   *
   * @param tenant the contract's tenant
   * @param contractId contract
   * @param correlationId the run's correlation ID
   * @param now time
   */
  void integrityMismatch(TenantId tenant, UUID contractId, String correlationId, Instant now) {
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            now,
            ContractIntegrityJob.ACTOR,
            "contract-integrity.check",
            CONTRACT,
            contractId,
            tenant.value(),
            "FAILURE",
            correlationId,
            metadata(Map.of("outcome", "mismatch")),
            identifiers("integrity", contractId)));
  }

  private static Map<String, Object> metadata(Map<String, Object> values) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", SCHEMA_VERSION);
    metadata.putAll(values);
    return metadata;
  }

  private void record(
      DocumentsCaller caller,
      String action,
      String resourceType,
      UUID resourceId,
      Map<String, Object> metadata,
      String integrity,
      Instant now) {
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            now,
            caller.subject(),
            action,
            resourceType,
            resourceId,
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            integrity));
  }

  private void publish(
      DocumentsCaller caller,
      String eventType,
      String subject,
      Map<String, Object> data,
      Instant now) {
    Map<String, Object> payload = new LinkedHashMap<>(data);
    payload.put("schemaVersion", SCHEMA_VERSION);
    outbox.append(
        new EventEnvelope(
            UUID.randomUUID().toString(),
            eventType,
            SCHEMA_VERSION,
            caller.tenant().toString(),
            SOURCE,
            subject,
            now.toString(),
            caller.correlationId(),
            null,
            payload));
  }
}
