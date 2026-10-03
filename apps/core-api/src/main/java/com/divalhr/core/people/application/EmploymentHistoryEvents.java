package com.divalhr.core.people.application;

import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Audit and outbox records of the employee directory and employment history (MVP-021, M21-1),
 * written in the caller's transaction.
 *
 * <ul>
 *   <li>Write audit metadata is exactly {@code {schemaVersion, rowsSuperseded, rowsCreated,
 *       employmentVersion}}; the action names the operation (schedule, apply, correct, cancel);
 *       {@code after_state_sha256} is the digest of the whole active timeline.
 *   <li>Outbox data is exactly {@code {employeeId, employmentId, changeId}}; the envelope carries
 *       the schema version and the type distinguishes a change from a cancellation.
 *   <li>Disclosure audit metadata is exactly {@code {view, page, resultCount, schemaVersion}}: no
 *       search text, filter, employee data or result ID.
 * </ul>
 *
 * <p>Names, numbers, dates, reasons, categories, unit and manager IDs never appear in metadata or
 * event data.
 */
@Component
public class EmploymentHistoryEvents {

  /** Producing module. */
  static final String SOURCE = "core-api/people";

  /** A change or correction was recorded. */
  public static final String CHANGED = "people.employment.changed.v1";

  /** A scheduled change was cancelled ({@code changeId} is the cancelled change). */
  public static final String CHANGE_CANCELLED = "people.employment.change-cancelled.v1";

  /** Audit resource type of writes. */
  static final String CHANGE_RESOURCE = "employment-change";

  private final AuditRecorder audit;
  private final OutboxWriter outbox;

  /**
   * Creates the recorder.
   *
   * @param audit audit recorder
   * @param outbox outbox writer
   */
  public EmploymentHistoryEvents(AuditRecorder audit, OutboxWriter outbox) {
    this.audit = audit;
    this.outbox = outbox;
  }

  /**
   * What a write did, in counts and identifiers only.
   *
   * @param employeeId employee
   * @param employmentId employment
   * @param changeId the recorded change (or cancellation)
   * @param eventChangeId the change the outbox event names (the cancelled one for a cancellation)
   * @param rowsSuperseded rows superseded
   * @param rowsCreated rows created
   * @param employmentVersion the employment's new version
   * @param timelineDigest digest of the active timeline after the write
   */
  record Write(
      UUID employeeId,
      UUID employmentId,
      UUID changeId,
      UUID eventChangeId,
      int rowsSuperseded,
      int rowsCreated,
      long employmentVersion,
      String timelineDigest) {}

  /**
   * Records a change, correction or cancellation.
   *
   * @param tenant tenant
   * @param actor verified subject
   * @param action {@code employment-change.schedule|apply|correct|cancel}
   * @param eventType {@link #CHANGED} or {@link #CHANGE_CANCELLED}
   * @param write what was written
   * @param now write time
   * @param correlationId correlation ID
   */
  void recorded(
      TenantId tenant,
      String actor,
      String action,
      String eventType,
      Write write,
      Instant now,
      String correlationId) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", EmploymentHistoryDigests.VERSION);
    metadata.put("rowsSuperseded", write.rowsSuperseded());
    metadata.put("rowsCreated", write.rowsCreated());
    metadata.put("employmentVersion", write.employmentVersion());
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            now,
            actor,
            action,
            CHANGE_RESOURCE,
            write.changeId(),
            tenant.value(),
            "SUCCESS",
            correlationId,
            metadata,
            write.timelineDigest()));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("employeeId", write.employeeId().toString());
    data.put("employmentId", write.employmentId().toString());
    data.put("changeId", write.eventChangeId().toString());
    outbox.append(
        new EventEnvelope(
            UUID.randomUUID().toString(),
            eventType,
            EmploymentHistoryDigests.VERSION,
            tenant.toString(),
            SOURCE,
            write.employeeId().toString(),
            now.toString(),
            correlationId,
            null,
            data));
  }

  /**
   * Records a disclosure of Confidential or Restricted HR data (H15 under M21-1).
   *
   * @param tenant tenant
   * @param actor verified subject
   * @param action the read operation
   * @param resourceType {@code organization} (directory, search) or {@code employee}
   * @param resourceId tenant ID or employee ID
   * @param view {@code directory|search|profile|timeline|changes|change-preview|cancel-preview}
   * @param page {@code first} or {@code next}
   * @param resultCount items or rows disclosed
   * @param digest integrity reference of the disclosed IDs
   * @param now disclosure time
   * @param correlationId correlation ID
   */
  void disclosed(
      TenantId tenant,
      String actor,
      String action,
      String resourceType,
      UUID resourceId,
      String view,
      String page,
      int resultCount,
      String digest,
      Instant now,
      String correlationId) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("view", view);
    metadata.put("page", page);
    metadata.put("resultCount", resultCount);
    metadata.put("schemaVersion", EmploymentHistoryDigests.VERSION);
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            now,
            actor,
            action,
            resourceType,
            resourceId,
            tenant.value(),
            "SUCCESS",
            correlationId,
            metadata,
            digest));
  }
}
