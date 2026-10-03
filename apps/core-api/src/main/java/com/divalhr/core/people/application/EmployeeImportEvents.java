package com.divalhr.core.people.application;

import com.divalhr.core.people.domain.ImportRecord;
import com.divalhr.core.people.domain.NewEmployee;
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
 * Audit and outbox records of the employee import (MVP-020, amendment A20-1), written in the
 * caller's transaction. Audit metadata carries only import IDs, counts and versions; outbox data
 * only identifiers and counts. Names, employee numbers, start dates and placements never appear:
 * {@code after_state_sha256} covers the canonical state without copying it.
 */
@Component
public class EmployeeImportEvents {

  /** Producing module. */
  static final String SOURCE = "core-api/people";

  /** Audit actor of the expiry job. */
  public static final String EXPIRY_ACTOR = "system:employee-import-expiry";

  /** Employee created (one per employee). */
  public static final String EMPLOYEE_CREATED = "people.employee.created.v1";

  /** Import committed (one per commit). */
  public static final String IMPORT_COMPLETED = "people.employee-import.completed.v1";

  private final AuditRecorder audit;
  private final OutboxWriter outbox;

  /**
   * Creates the recorder.
   *
   * @param audit audit recorder
   * @param outbox outbox writer
   */
  public EmployeeImportEvents(AuditRecorder audit, OutboxWriter outbox) {
    this.audit = audit;
    this.outbox = outbox;
  }

  /**
   * Records an upload: counts only; the integrity reference is the preview digest.
   *
   * @param tenant tenant
   * @param actor verified subject
   * @param record the import
   * @param correlationId correlation ID
   */
  public void uploaded(TenantId tenant, String actor, ImportRecord record, String correlationId) {
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            record.createdAt(),
            actor,
            EmployeeImportService.CREATE,
            "employee-import",
            record.id(),
            tenant.value(),
            "SUCCESS",
            correlationId,
            counts(record),
            record.previewDigest()));
  }

  /**
   * Records a commit: one audit event per employee, one summary event, and the outbox events.
   *
   * @param tenant tenant
   * @param actor verified subject
   * @param record the import (before commit)
   * @param employees created employees, in creation order
   * @param now commit time
   * @param correlationId correlation ID
   */
  public void committed(
      TenantId tenant,
      String actor,
      ImportRecord record,
      List<NewEmployee> employees,
      Instant now,
      String correlationId) {
    StringBuilder createdIds = new StringBuilder("DIVALHR-EMPLOYEE-IMPORT-COMMIT\nversion=1\n");
    for (NewEmployee employee : employees) {
      createdIds.append(employee.employeeId()).append('\n');
      audit.record(
          new AuditEvent(
              UUID.randomUUID(),
              now,
              actor,
              "employee.create",
              "employee",
              employee.employeeId(),
              tenant.value(),
              "SUCCESS",
              correlationId,
              Map.of("importId", record.id().toString(), "version", 1),
              Fingerprints.sha256(stateOf(employee))));
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("employeeId", employee.employeeId().toString());
      data.put("employmentId", employee.employmentId().toString());
      data.put("importId", record.id().toString());
      outbox.append(
          envelope(tenant, EMPLOYEE_CREATED, employee.employeeId(), now, correlationId, data));
    }
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("createdCount", employees.size());
    summary.put("notImportedCount", record.totalRows() - employees.size());
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            now,
            actor,
            EmployeeImportCommitService.COMMIT,
            "employee-import",
            record.id(),
            tenant.value(),
            "SUCCESS",
            correlationId,
            summary,
            Fingerprints.sha256(createdIds.toString())));
    Map<String, Object> data = new LinkedHashMap<>(summary);
    data.put("importId", record.id().toString());
    outbox.append(envelope(tenant, IMPORT_COMPLETED, record.id(), now, correlationId, data));
  }

  /**
   * Records a discard or an expiry.
   *
   * @param tenant tenant
   * @param actor verified subject, or {@link #EXPIRY_ACTOR}
   * @param action {@code employee-import.discard} or {@code employee-import.expire}
   * @param record the import (before closing)
   * @param now closing time
   * @param correlationId correlation ID
   */
  public void closed(
      TenantId tenant,
      String actor,
      String action,
      ImportRecord record,
      Instant now,
      String correlationId) {
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            now,
            actor,
            action,
            "employee-import",
            record.id(),
            tenant.value(),
            "SUCCESS",
            correlationId,
            counts(record),
            Fingerprints.sha256(
                "DIVALHR-EMPLOYEE-IMPORT-CLOSE\nversion=1\n"
                    + record.id()
                    + "\n"
                    + action
                    + "\n")));
  }

  private static Map<String, Object> counts(ImportRecord record) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("totalRows", record.totalRows());
    metadata.put("validRows", record.validRows());
    metadata.put("invalidRows", record.invalidRows());
    return metadata;
  }

  /**
   * Canonical employee and employment state (hashed only, never stored or logged).
   *
   * @param e the employee
   * @return canonical text
   */
  static String stateOf(NewEmployee e) {
    return "DIVALHR-EMPLOYEE-STATE\nversion=1\n"
        + "employee="
        + e.employeeId()
        + "\nnumber="
        + e.employeeNumber()
        + "\ngiven="
        + e.givenNames()
        + "\nfamily="
        + e.familyName()
        + "\nemployment="
        + e.employmentId()
        + "\nstart="
        + e.startDate()
        + "\nle="
        + e.legalEntityId()
        + "\nsite="
        + e.siteId()
        + "\ndept="
        + e.departmentId()
        + "\ncc="
        + e.costCenterId()
        + "\nteam="
        + e.teamId()
        + "\n";
  }

  private static EventEnvelope envelope(
      TenantId tenant,
      String type,
      UUID subject,
      Instant now,
      String correlationId,
      Map<String, Object> data) {
    return new EventEnvelope(
        UUID.randomUUID().toString(),
        type,
        1,
        tenant.toString(),
        SOURCE,
        subject.toString(),
        now.toString(),
        correlationId,
        null,
        data);
  }
}
