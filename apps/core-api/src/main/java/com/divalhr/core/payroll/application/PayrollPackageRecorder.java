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
 * Audit records and internal outbox events of payroll approval and payslips (MVP-094C, ADR 0035
 * section 14). Metadata and event data are an allow-list ({@link #ALLOWED}): identifiers, the
 * version, the period, the currency, the package status, counts and the same-preparer boolean of an
 * approval. They never carry an amount, an employee number, a snapshot, a digest, a reason code, an
 * explanation, an approver or voider subject, a pack code, a locale-dependent text or a filename; a
 * key outside the allow-list is refused before anything is written. The events are internal: no
 * webhook or WhatsApp type maps them and no payslip event exists.
 */
@Component
public class PayrollPackageRecorder {

  /** Schema version of the audit metadata and event payloads. */
  public static final int SCHEMA_VERSION = 1;

  /** Audit action of a package disclosure (preview, list, read). */
  public static final String PACKAGE_READ = "payroll-package.read";

  /** Audit action of an approval. */
  public static final String APPROVE = "payroll-package.approve";

  /** Audit action of a void. */
  public static final String VOID = "payroll-package.void";

  /** Audit action of a payslip disclosure. */
  public static final String PAYSLIP_READ = "payslip.read";

  /** Audit action of a payslip PDF download. */
  public static final String PAYSLIP_DOWNLOAD = "payslip.download";

  /** Internal event of an approved package. */
  public static final String APPROVED_EVENT = "payroll.package.approved.v1";

  /** Internal event of a voided package. */
  public static final String VOIDED_EVENT = "payroll.package.voided.v1";

  /** The only keys audit metadata and event data may carry. */
  public static final Set<String> ALLOWED =
      Set.of(
          "schemaVersion",
          "legalEntityId",
          "packageId",
          "payslipId",
          "version",
          "period",
          "currency",
          "status",
          "payslipCount",
          "runVersion",
          "calculationVersion",
          "samePreparer",
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
  public PayrollPackageRecorder(AuditRecorder audit, OutboxWriter outbox) {
    this.audit = audit;
    this.outbox = outbox;
  }

  /**
   * One approved package, without any amount, digest or subject.
   *
   * @param legalEntityId legal entity
   * @param packageId the package
   * @param version its version in the chain
   * @param period {@code YYYY-MM}
   * @param payslipCount payslips
   * @param runVersion the run version it bound
   * @param calculationVersion the calculation version it bound
   * @param samePreparer whether the approver also prepared a source
   * @param approvalDigest the approval digest (only the integrity reference of the audit record)
   * @param at server instant
   */
  public record ApprovalRecorded(
      UUID legalEntityId,
      UUID packageId,
      int version,
      String period,
      int payslipCount,
      int runVersion,
      int calculationVersion,
      boolean samePreparer,
      String approvalDigest,
      Instant at) {

    @Override
    public String toString() {
      return "PayrollApprovalRecorded[" + packageId + "]";
    }
  }

  /**
   * One voided package, without any reason, explanation, amount or subject.
   *
   * @param legalEntityId legal entity
   * @param packageId the package
   * @param version its version in the chain
   * @param period {@code YYYY-MM}
   * @param payslipCount payslips
   * @param approvalDigest the approval digest (only the integrity reference of the audit record)
   * @param at server instant
   */
  public record VoidRecorded(
      UUID legalEntityId,
      UUID packageId,
      int version,
      String period,
      int payslipCount,
      String approvalDigest,
      Instant at) {

    @Override
    public String toString() {
      return "PayrollVoidRecorded[" + packageId + "]";
    }
  }

  /**
   * The fail-closed disclosure audit of a read, preview or download: the view, identifiers and
   * counts only.
   *
   * @param caller verified caller
   * @param action one of the read or download actions
   * @param resourceType {@code payroll-package} or {@code payslip}
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
                "DIVALHR-PAYROLL-PACKAGE-DISCLOSURE\nversion=1\naction="
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
   * Records an approval: the audit record and the internal outbox event.
   *
   * @param caller verified caller
   * @param recorded the approval
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void approved(PayrollCaller caller, ApprovalRecorded recorded) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", SCHEMA_VERSION);
    metadata.put("legalEntityId", recorded.legalEntityId().toString());
    metadata.put("packageId", recorded.packageId().toString());
    metadata.put("version", recorded.version());
    metadata.put("period", recorded.period());
    metadata.put("currency", "CDF");
    metadata.put("status", "APPROVED");
    metadata.put("payslipCount", recorded.payslipCount());
    metadata.put("runVersion", recorded.runVersion());
    metadata.put("calculationVersion", recorded.calculationVersion());
    metadata.put("samePreparer", recorded.samePreparer());
    requireAllowed(metadata);
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            recorded.at(),
            caller.subject(),
            APPROVE,
            "payroll-package",
            recorded.packageId(),
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(
                "DIVALHR-PAYROLL-PACKAGE-APPROVED\nversion=1\npackage="
                    + recorded.packageId()
                    + "\napproval="
                    + recorded.approvalDigest()
                    + "\n")));
    outbox.append(envelope(caller, APPROVED_EVENT, recorded.packageId(), recorded.at(), metadata));
  }

  /**
   * Records a void: the audit record and the internal outbox event.
   *
   * @param caller verified caller
   * @param recorded the void
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void voided(PayrollCaller caller, VoidRecorded recorded) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", SCHEMA_VERSION);
    metadata.put("legalEntityId", recorded.legalEntityId().toString());
    metadata.put("packageId", recorded.packageId().toString());
    metadata.put("version", recorded.version());
    metadata.put("period", recorded.period());
    metadata.put("currency", "CDF");
    metadata.put("status", "VOIDED");
    metadata.put("payslipCount", recorded.payslipCount());
    requireAllowed(metadata);
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            recorded.at(),
            caller.subject(),
            VOID,
            "payroll-package",
            recorded.packageId(),
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(
                "DIVALHR-PAYROLL-PACKAGE-VOIDED\nversion=1\npackage="
                    + recorded.packageId()
                    + "\napproval="
                    + recorded.approvalDigest()
                    + "\n")));
    outbox.append(envelope(caller, VOIDED_EVENT, recorded.packageId(), recorded.at(), metadata));
  }

  private static void requireAllowed(Map<String, Object> metadata) {
    for (String key : metadata.keySet()) {
      if (!ALLOWED.contains(key)) {
        throw new IllegalArgumentException("payroll package metadata key outside the allow-list");
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
