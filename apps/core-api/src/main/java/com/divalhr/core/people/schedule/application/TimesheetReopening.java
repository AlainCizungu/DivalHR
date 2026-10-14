package com.divalhr.core.people.schedule.application;

import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.schedule.domain.Timesheet;
import com.divalhr.core.people.schedule.domain.Timesheet.Row;
import com.divalhr.core.people.schedule.domain.TimesheetApproval;
import com.divalhr.core.people.schedule.domain.TimesheetApproval.Action;
import com.divalhr.core.people.schedule.domain.TimesheetApproval.Kind;
import com.divalhr.core.people.schedule.domain.TimesheetApproval.Status;
import com.divalhr.core.people.schedule.internal.JdbcTimesheetActionRepository;
import com.divalhr.core.people.schedule.internal.JdbcTimesheetRepository;
import com.divalhr.core.people.schedule.internal.JdbcTimesheetRepository.Header;
import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reopening approved time (MVP-070D, Issue #132, T70D-2, T70D-3, T70D-10; ADR 0020). Called only by
 * a committing attendance correction, inside its transaction and after its writes, while the
 * correction holds the tenant's scheduling lock (the lock every timesheet command takes first, so a
 * correction, a refresh, a submission and a decision serialize to one outcome).
 *
 * <p>Every APPROVED timesheet of the corrected employee whose week contains the corrected shift is
 * locked {@code FOR UPDATE} in ID order; its sources are evaluated again exactly as a refresh would
 * ({@link TimesheetService#nextRows}, which locks the week's candidates' employments {@code FOR
 * SHARE}). Only a timesheet whose source digest differs from its approval's is reopened: one
 * append-only REOPENED action binding the superseded approval's exact evidence, the digest after
 * the correction, the correction and the administrator's verified subject, the timesheet's move to
 * REOPENED, its audit record ({@value #REOPEN}) and its outbox event ({@value #REOPENED_EVENT}).
 * DRAFT and SUBMITTED timesheets are left to their MVP-070A and MVP-070B/C rules; a correction that
 * changes nothing material reopens nothing. A failure of any write rolls the correction back.
 *
 * <p>Names, numbers, labels, attendance instants, exact worked time, digests, the explanation, the
 * subject and correction identifiers never enter audit metadata or logs; the event carries stable
 * identifiers and bounded numeric and state facts only.
 */
@Service
public class TimesheetReopening {

  /** Reopening (audit action and metric operation). */
  public static final String REOPEN = "timesheet.reopen";

  /** Outbox event of a reopening. */
  public static final String REOPENED_EVENT = "people.timesheet.reopened.v1";

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.timesheet");
  private static final String SOURCE = "core-api/people";
  private static final String RESOURCE = "timesheet";

  private final JdbcTimesheetRepository timesheets;
  private final JdbcTimesheetActionRepository actions;
  private final TimesheetService drafts;
  private final AuditRecorder audit;
  private final OutboxWriter outbox;
  private final OperationMetrics metrics;
  private final JsonMapper json;

  /**
   * Creates the service.
   *
   * @param timesheets timesheet storage
   * @param actions action storage
   * @param drafts the draft timesheets (the sources evaluated again)
   * @param audit audit recorder
   * @param outbox outbox writer
   * @param metrics operation metrics
   * @param json JSON mapper
   */
  public TimesheetReopening(
      JdbcTimesheetRepository timesheets,
      JdbcTimesheetActionRepository actions,
      TimesheetService drafts,
      AuditRecorder audit,
      OutboxWriter outbox,
      OperationMetrics metrics,
      JsonMapper json) {
    this.timesheets = timesheets;
    this.actions = actions;
    this.drafts = drafts;
    this.audit = audit;
    this.outbox = outbox;
    this.metrics = metrics;
    this.json = json;
  }

  /**
   * Whether approved time of the employee covers the shift now (a correction preview's warning and
   * digest fact; no lock, nothing written).
   *
   * @param tenant verified tenant
   * @param employeeId the corrected employee
   * @param shiftStart the corrected shift's start
   * @return whether an APPROVED timesheet's week contains it
   */
  public boolean approvedCovers(TenantId tenant, UUID employeeId, Instant shiftStart) {
    return !actions.approvedCovering(tenant, employeeId, shiftStart, false).isEmpty();
  }

  /**
   * Reopens the employee's approved timesheets that the committed correction materially changed.
   * The caller holds the scheduling lock and runs the correction's transaction.
   *
   * @param caller the correcting tenant administrator
   * @param correctionId the committed correction
   * @param employeeId the corrected employee
   * @param shiftStart the corrected shift's start
   * @param now the correction instant
   * @return the number of reopened timesheets
   */
  public int reopen(
      PeopleCaller caller, UUID correctionId, UUID employeeId, Instant shiftStart, Instant now) {
    TenantId tenant = caller.tenant();
    List<Header> approved = actions.approvedCovering(tenant, employeeId, shiftStart, true);
    int reopened = 0;
    for (Header header : approved) {
      Action approval =
          actions
              .action(tenant, header.currentActionId())
              .filter(a -> a.kind() == Kind.APPROVED)
              .orElseThrow(() -> new IllegalStateException("an approved timesheet's approval"));
      List<Row> next = drafts.nextRows(tenant, header, true);
      String after = Timesheet.digest(next);
      if (after.equals(approval.sourceDigest())) {
        continue; // no material change: the approval stands
      }
      Action reopening = Action.reopening(approval, after, correctionId, now);
      actions.insert(tenant, reopening, header.period().siteId(), caller.subject());
      if (actions.moveTo(tenant, reopening, Status.APPROVED) != 1) {
        throw new IllegalStateException("a locked approved timesheet moved");
      }
      recorded(caller, header, reopening);
      event(caller, header, reopening);
      reopened++;
    }
    if (reopened > 0) {
      timesheets.checkConsistent(tenant);
      metrics.record(REOPEN, Outcome.UPDATED);
      LOG.atInfo()
          .addKeyValue("operation", REOPEN)
          .addKeyValue("outcome", "reopened")
          .addKeyValue("timesheets", TimesheetService.band(reopened))
          .log("timesheet_reopened");
    }
    return reopened;
  }

  /**
   * Bounded metadata only (T70D-3): the action, cycle, revision, generation version, row count and
   * a worked-minute band; identifiers, both digests, the correction and the subject only inside the
   * state digest.
   */
  private void recorded(PeopleCaller caller, Header header, Action action) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", WorkScheduleService.SCHEMA_VERSION);
    metadata.put("action", action.kind().name());
    metadata.put("cycle", action.cycle());
    metadata.put("revision", action.revision());
    metadata.put("generationVersion", action.generationVersion());
    metadata.put("rowCount", action.totals().rows());
    metadata.put(
        "workedMinuteBand", TimesheetApproval.workedMinuteBand(action.totals().workedMinutes()));
    Map<String, Object> state = new TreeMap<>();
    state.put("tenantId", caller.tenant().toString());
    state.put("timesheetId", header.id().toString());
    state.put("generationId", header.generationId().toString());
    state.put("actionId", action.id().toString());
    state.put("sequence", action.sequence());
    state.put("supersededActionId", action.supersededActionId().toString());
    state.put("correctionId", action.correctionId().toString());
    state.put("employeeId", action.employeeId().toString());
    state.put("employmentId", action.employmentId().toString());
    state.put("managerEmployeeId", action.managerEmployeeId().toString());
    state.put("sourceDigestBefore", action.sourceDigest());
    state.put("sourceDigestAfter", action.afterSourceDigest());
    state.put("occurredAt", action.occurredAt().toString());
    state.put("by", caller.subject());
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            action.occurredAt(),
            caller.subject(),
            REOPEN,
            RESOURCE,
            header.id(),
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(json.writeValueAsString(state))));
  }

  /** Stable IDs, the week's boundaries, the superseded evidence numbers and the state only. */
  private void event(PeopleCaller caller, Header header, Action action) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("timesheetId", header.id().toString());
    data.put("actionId", action.id().toString());
    data.put("supersededActionId", action.supersededActionId().toString());
    data.put("correctionId", action.correctionId().toString());
    data.put("generationId", header.generationId().toString());
    data.put("employeeId", header.employeeId().toString());
    data.put("siteId", header.period().siteId().toString());
    data.put("weekStart", header.period().weekStart().toString());
    data.put("periodStartAt", header.period().startAt().toString());
    data.put("periodEndAt", header.period().endAt().toString());
    data.put("revision", action.revision());
    data.put("generationVersion", action.generationVersion());
    data.put("cycle", action.cycle());
    data.put("rowCount", action.totals().rows());
    data.put("workedMinutes", action.totals().workedMinutes());
    data.put("status", Status.REOPENED.name());
    data.put("occurredAt", action.occurredAt().toString());
    outbox.append(
        new EventEnvelope(
            UUID.randomUUID().toString(),
            REOPENED_EVENT,
            WorkScheduleService.SCHEMA_VERSION,
            caller.tenant().toString(),
            SOURCE,
            header.id().toString(),
            action.occurredAt().toString(),
            caller.correlationId(),
            null,
            data));
  }
}
