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
 * Audit and outbox records of separations (MVP-022), written in the caller's transaction.
 *
 * <ul>
 *   <li>Audit metadata is an allow-list (A22-6): {@code schemaVersion}, counts, versions and closed
 *       state codes. The integrity hash (the confirmed preview or cancellation digest, or a digest
 *       of identifiers) goes to the record's own {@code after_state_sha256}, never to metadata.
 *   <li>Outbox data is identifiers only.
 *   <li>Never a date, reason, task code, name, number or HR value.
 * </ul>
 */
@Component
public class SeparationEvents {

  /** A separation was recorded (scheduled, or effective at once). */
  public static final String RECORDED = "people.employment.separation-recorded.v1";

  /** A scheduled separation was cancelled. */
  public static final String CANCELLED = "people.employment.separation-cancelled.v1";

  /** A scheduled separation became effective. */
  public static final String EFFECTIVE = "people.employment.separation-effective.v1";

  /** A follow-up task changed status. */
  public static final String TASK_UPDATED = "people.separation-task.updated.v1";

  /** Actor recorded for the effective-date job. */
  static final String SYSTEM_ACTOR = "system:separation-effective";

  static final String RESOURCE = "employee-separation";
  static final String TASK_RESOURCE = "separation-task";

  private final AuditRecorder audit;
  private final OutboxWriter outbox;

  /**
   * Creates the recorder.
   *
   * @param audit audit recorder
   * @param outbox outbox writer
   */
  public SeparationEvents(AuditRecorder audit, OutboxWriter outbox) {
    this.audit = audit;
    this.outbox = outbox;
  }

  /**
   * What a separation write did, in counts and identifiers.
   *
   * @param separationId separation
   * @param employeeId employee
   * @param employmentId employment
   * @param state the separation's state after the write
   * @param reportCount reports affected
   * @param intervalCount intervals affected
   * @param rowsSuperseded the employee's rows superseded
   * @param rowsCreated the employee's rows created
   * @param employmentVersion the employment's new version
   * @param integrity the confirmed digest
   */
  record Write(
      UUID separationId,
      UUID employeeId,
      UUID employmentId,
      String state,
      int reportCount,
      int intervalCount,
      int rowsSuperseded,
      int rowsCreated,
      long employmentVersion,
      String integrity) {}

  /**
   * A separation was recorded or cancelled.
   *
   * @param tenant tenant
   * @param actor verified subject
   * @param action {@code employee-separation.record|cancel}
   * @param eventType {@link #RECORDED} or {@link #CANCELLED}
   * @param write what was written
   * @param now write time
   * @param correlationId correlation ID
   */
  void written(
      TenantId tenant,
      String actor,
      String action,
      String eventType,
      Write write,
      Instant now,
      String correlationId) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", EmploymentHistoryDigests.VERSION);
    metadata.put("state", write.state());
    metadata.put("reportCount", write.reportCount());
    metadata.put("intervalCount", write.intervalCount());
    metadata.put("rowsSuperseded", write.rowsSuperseded());
    metadata.put("rowsCreated", write.rowsCreated());
    metadata.put("employmentVersion", write.employmentVersion());
    record(
        tenant,
        actor,
        action,
        RESOURCE,
        write.separationId(),
        metadata,
        write.integrity(),
        now,
        correlationId);
    publish(
        tenant,
        eventType,
        write.employeeId(),
        write.employmentId(),
        write.separationId(),
        null,
        now,
        correlationId);
  }

  /**
   * A scheduled separation became effective (effective-date job).
   *
   * @param tenant tenant
   * @param separationId separation
   * @param employeeId employee
   * @param employmentId employment
   * @param version separation version after the transition
   * @param now transition time
   * @param correlationId correlation ID
   */
  void effective(
      TenantId tenant,
      UUID separationId,
      UUID employeeId,
      UUID employmentId,
      long version,
      Instant now,
      String correlationId) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", EmploymentHistoryDigests.VERSION);
    metadata.put("fromState", "SCHEDULED");
    metadata.put("toState", "EFFECTIVE");
    metadata.put("version", version);
    record(
        tenant,
        SYSTEM_ACTOR,
        "employee-separation.effective",
        RESOURCE,
        separationId,
        metadata,
        SeparationDigests.identifiers("effective", separationId, Long.toString(version)),
        now,
        correlationId);
    publish(tenant, EFFECTIVE, employeeId, employmentId, separationId, null, now, correlationId);
  }

  /**
   * A follow-up task changed status.
   *
   * @param tenant tenant
   * @param actor verified subject
   * @param separationId separation
   * @param employeeId employee
   * @param taskId task
   * @param fromStatus status before
   * @param toStatus status after
   * @param version task version after
   * @param now change time
   * @param correlationId correlation ID
   */
  void taskUpdated(
      TenantId tenant,
      String actor,
      UUID separationId,
      UUID employeeId,
      UUID taskId,
      String fromStatus,
      String toStatus,
      long version,
      Instant now,
      String correlationId) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", EmploymentHistoryDigests.VERSION);
    metadata.put("fromStatus", fromStatus);
    metadata.put("toStatus", toStatus);
    metadata.put("version", version);
    record(
        tenant,
        actor,
        "separation-task.update",
        TASK_RESOURCE,
        taskId,
        metadata,
        SeparationDigests.identifiers("task", taskId, toStatus, Long.toString(version)),
        now,
        correlationId);
    Map<String, Object> extra = new LinkedHashMap<>();
    extra.put("taskId", taskId.toString());
    publish(tenant, TASK_UPDATED, employeeId, null, separationId, extra, now, correlationId);
  }

  private void record(
      TenantId tenant,
      String actor,
      String action,
      String resourceType,
      UUID resourceId,
      Map<String, Object> metadata,
      String integrity,
      Instant now,
      String correlationId) {
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
            integrity));
  }

  private void publish(
      TenantId tenant,
      String eventType,
      UUID employeeId,
      UUID employmentId,
      UUID separationId,
      Map<String, Object> extra,
      Instant now,
      String correlationId) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("employeeId", employeeId.toString());
    if (employmentId != null) {
      data.put("employmentId", employmentId.toString());
    }
    data.put("separationId", separationId.toString());
    if (extra != null) {
      data.putAll(extra);
    }
    outbox.append(
        new EventEnvelope(
            UUID.randomUUID().toString(),
            eventType,
            EmploymentHistoryDigests.VERSION,
            tenant.toString(),
            EmploymentHistoryEvents.SOURCE,
            employeeId.toString(),
            now.toString(),
            correlationId,
            null,
            data));
  }
}
