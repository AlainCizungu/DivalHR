package com.divalhr.core.people.schedule.application;

import com.divalhr.core.people.application.BusinessCalendar;
import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.schedule.api.AttendanceCorrectionRequest;
import com.divalhr.core.people.schedule.api.AttendanceCorrectionResponses.History;
import com.divalhr.core.people.schedule.api.AttendanceCorrectionResponses.HistoryEntry;
import com.divalhr.core.people.schedule.api.AttendanceCorrectionResponses.OriginalEvent;
import com.divalhr.core.people.schedule.api.AttendanceCorrectionResponses.Preview;
import com.divalhr.core.people.schedule.api.AttendanceCorrectionResponses.Receipt;
import com.divalhr.core.people.schedule.api.AttendanceCorrectionResponses.TimesView;
import com.divalhr.core.people.schedule.api.CommitAttendanceCorrectionRequest;
import com.divalhr.core.people.schedule.api.CommitMissingAttendanceCorrectionRequest;
import com.divalhr.core.people.schedule.api.MissingAttendanceCorrectionRequest;
import com.divalhr.core.people.schedule.application.AttendanceCorrectionCommand.Commit;
import com.divalhr.core.people.schedule.application.AttendanceCorrectionCommand.Proposal;
import com.divalhr.core.people.schedule.domain.Attendance;
import com.divalhr.core.people.schedule.domain.Attendance.Record;
import com.divalhr.core.people.schedule.domain.AttendanceCorrection;
import com.divalhr.core.people.schedule.domain.AttendanceCorrection.Entry;
import com.divalhr.core.people.schedule.domain.AttendanceCorrection.Operation;
import com.divalhr.core.people.schedule.domain.AttendanceCorrection.Times;
import com.divalhr.core.people.schedule.domain.AttendanceCorrection.Warning;
import com.divalhr.core.people.schedule.domain.Substitution;
import com.divalhr.core.people.schedule.domain.Substitution.Shift;
import com.divalhr.core.people.schedule.internal.JdbcAttendanceCorrectionRepository;
import com.divalhr.core.people.schedule.internal.JdbcAttendanceRepository;
import com.divalhr.core.people.schedule.internal.JdbcWorkScheduleRepository;
import com.divalhr.core.people.schedule.internal.JdbcWorkScheduleSubstitutionRepository;
import com.divalhr.core.people.schedule.internal.ScheduleConstraintViolations;
import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.UnitView;
import com.divalhr.core.platform.tenancy.TenantId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Attendance corrections by tenant administrators with exact MFA (MVP-062, Issue #126, C62-1 to
 * C62-9; ADR 0016). Authorization (role {@code tenant-admin}, privileged MFA, verified tenant) has
 * run before this; tenant and actor never come from the request.
 *
 * <p>A preview resolves the target inside the verified tenant (a record, or a slot and its
 * effective assignee: the same safe {@code 404} for unknown, foreign, malformed and undisclosable
 * targets), evaluates the proposal against the immutable schedule boundaries and the server
 * instant, and returns the before and after values, the factual exceptions before and after,
 * neutral warnings, the target version and the preview digest. It writes nothing but its
 * fail-closed disclosure audit.
 *
 * <p>A commit is one bounded transaction: idempotency (step 0), the tenant's scheduling lock (1),
 * the employment(s) {@code FOR SHARE} (2), the schedule {@code FOR UPDATE} (3), the record and the
 * employee's overlapping open records {@code FOR UPDATE} (4); then every fact is evaluated again. A
 * changed target version or any other difference from the reviewed preview is {@code 409
 * ATTENDANCE_CORRECTION_PREVIEW_STALE}, never recomputed and committed. Otherwise the append-only
 * correction, the projection's guarded change, the deferred checks, the audit record ({@value
 * #CREATE}) and the outbox event ({@value #CORRECTED_EVENT}) commit together with the idempotency
 * completion. Check-ins, check-outs and terminal actions take the same scheduling lock: every race
 * serializes to one valid outcome.
 *
 * <p>MVP-070D (T70D-2): in the same transaction and under the same locks, after the correction's
 * writes, every APPROVED timesheet of the employee whose week contains the shift and whose sources
 * the correction materially changed is reopened ({@link TimesheetReopening}); the receipt counts
 * them. The preview warns when approved time covers the shift, and that fact is part of the preview
 * digest, so a commit after it changed is stale.
 *
 * <p>The explanation, names, numbers, labels, instants, the subject, the preview digest and the
 * idempotency key never enter audit metadata, events, logs, metrics or errors.
 */
@Service
public class AttendanceCorrectionService {

  /** Preview (interceptor operation and disclosure audit action). */
  public static final String PREVIEW = "attendance.correction-preview";

  /** Commit (idempotency operation, interceptor operation and audit action). */
  public static final String CREATE = "attendance.correction-create";

  /** A record's history (interceptor operation and disclosure audit action). */
  public static final String HISTORY = "attendance.correction-history";

  /** Outbox event of a committed correction. */
  public static final String CORRECTED_EVENT = "people.attendance.corrected.v1";

  /** Bounded counter: operation, outcome, correction operation and reason code only. */
  public static final String METRIC = "divalhr.attendance.correction";

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.schedule");
  private static final String SOURCE = "core-api/people";
  private static final String RESOURCE = "attendance-record";
  private static final Duration TRANSACTION_TIMEOUT = Duration.ofSeconds(15);
  private static final IdempotentOperation.Spec CREATE_SPEC =
      new IdempotentOperation.Spec(CREATE, "attendance_correction", "create", "created", 201);

  private final IdempotentOperation operations;
  private final JdbcWorkScheduleRepository schedules;
  private final JdbcWorkScheduleSubstitutionRepository shifts;
  private final JdbcAttendanceRepository attendance;
  private final JdbcAttendanceCorrectionRepository corrections;
  private final OrganizationPlacementDirectory directory;
  private final TimesheetReopening reopening;
  private final BusinessCalendar calendar;
  private final AuditRecorder audit;
  private final OutboxWriter outbox;
  private final OperationMetrics metrics;
  private final MeterRegistry meters;
  private final PlatformTransactionManager transactionManager;
  private final JsonMapper json;

  /**
   * Creates the service.
   *
   * @param operations shared idempotent command flow
   * @param schedules schedule storage (the scheduling lock and schedule locks)
   * @param shifts shift, identity and qualifying-employment reads
   * @param attendance attendance storage
   * @param corrections correction storage
   * @param directory the tenant module's unit port (site codes and names)
   * @param reopening approved time a correction reopens (MVP-070D)
   * @param calendar the platform clock (the authoritative instant)
   * @param audit audit recorder
   * @param outbox outbox writer
   * @param metrics operation metrics
   * @param meters meter registry
   * @param transactionManager transaction manager
   * @param json JSON mapper
   */
  public AttendanceCorrectionService(
      IdempotentOperation operations,
      JdbcWorkScheduleRepository schedules,
      JdbcWorkScheduleSubstitutionRepository shifts,
      JdbcAttendanceRepository attendance,
      JdbcAttendanceCorrectionRepository corrections,
      OrganizationPlacementDirectory directory,
      TimesheetReopening reopening,
      BusinessCalendar calendar,
      AuditRecorder audit,
      OutboxWriter outbox,
      OperationMetrics metrics,
      MeterRegistry meters,
      PlatformTransactionManager transactionManager,
      JsonMapper json) {
    this.operations = operations;
    this.schedules = schedules;
    this.shifts = shifts;
    this.attendance = attendance;
    this.corrections = corrections;
    this.directory = directory;
    this.reopening = reopening;
    this.calendar = calendar;
    this.audit = audit;
    this.outbox = outbox;
    this.metrics = metrics;
    this.meters = meters;
    this.transactionManager = transactionManager;
    this.json = json;
  }

  // ------------------------------------------------------------------------------------------
  // Preview
  // ------------------------------------------------------------------------------------------

  /**
   * Previews a REPLACE_TIMES correction of an existing record.
   *
   * @param caller verified tenant administrator
   * @param recordId path value
   * @param body the proposal
   * @return the preview, after its disclosure audit committed
   */
  public Preview previewReplace(
      PeopleCaller caller, String recordId, AttendanceCorrectionRequest body) {
    return observed(
        PREVIEW,
        null,
        () -> previewed(caller, AttendanceCorrectionCommand.replace(recordId, body)));
  }

  /**
   * Previews a CREATE_MISSING_RECORD correction of a slot's effective assignee.
   *
   * @param caller verified tenant administrator
   * @param body the proposal
   * @return the preview, after its disclosure audit committed
   */
  public Preview previewMissing(PeopleCaller caller, MissingAttendanceCorrectionRequest body) {
    return observed(
        PREVIEW, null, () -> previewed(caller, AttendanceCorrectionCommand.missing(body)));
  }

  private Preview previewed(PeopleCaller caller, Proposal proposal) {
    return read(
        () -> {
          TenantId tenant = caller.tenant();
          Target target = resolve(tenant, proposal);
          Instant now = now();
          Preview preview = evaluate(caller, target, proposal, now);
          disclosed(
              caller,
              PREVIEW,
              "attendance-correction-preview",
              1,
              "DIVALHR-ATTENDANCE-CORRECTION-PREVIEW-DISCLOSURE\nversion=1\ndigest="
                  + preview.previewDigest()
                  + "\nemployee="
                  + target.employee().id()
                  + "\n");
          return preview;
        });
  }

  // ------------------------------------------------------------------------------------------
  // Commit
  // ------------------------------------------------------------------------------------------

  /**
   * Commits a previewed REPLACE_TIMES correction, or replays an earlier identical commit.
   *
   * @param caller verified tenant administrator
   * @param recordId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the proposal, target version, preview digest and confirmation
   * @return the receipt
   */
  public IdempotentOperation.Result<Receipt> commitReplace(
      PeopleCaller caller,
      String recordId,
      String idempotencyKey,
      CommitAttendanceCorrectionRequest body) {
    return committed(
        caller,
        idempotencyKey,
        () -> AttendanceCorrectionCommand.commitReplace(idempotencyKey, recordId, body));
  }

  /**
   * Commits a previewed CREATE_MISSING_RECORD correction, or replays an earlier identical commit.
   *
   * @param caller verified tenant administrator
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the proposal, target version, preview digest and confirmation
   * @return the receipt
   */
  public IdempotentOperation.Result<Receipt> commitMissing(
      PeopleCaller caller, String idempotencyKey, CommitMissingAttendanceCorrectionRequest body) {
    return committed(
        caller,
        idempotencyKey,
        () -> AttendanceCorrectionCommand.commitMissing(idempotencyKey, body));
  }

  private IdempotentOperation.Result<Receipt> committed(
      PeopleCaller caller, String idempotencyKey, Supplier<Commit> validation) {
    String operation = "unknown";
    String reason = "unknown";
    String outcome = "failure";
    try {
      Commit commit = operations.validated(CREATE_SPEC, validation);
      operation = commit.proposal().operation().name();
      reason = commit.proposal().reason().name();
      IdempotentOperation.Result<Receipt> result =
          operations.execute(
              CREATE_SPEC,
              caller.subject(),
              idempotencyKey,
              commit.canonical(caller.tenant()),
              Receipt.class,
              TRANSACTION_TIMEOUT,
              () -> corrected(caller, idempotencyKey, commit));
      outcome = result.replayed() ? "replayed" : "created";
      return result;
    } catch (ApiException refused) {
      outcome = refused.code().name().toLowerCase(Locale.ROOT);
      throw refused;
    } finally {
      Counter.builder(METRIC)
          .description("Attendance correction commits")
          .tag("operation", CREATE)
          .tag("outcome", outcome)
          .tag("correctionOperation", operation)
          .tag("reasonCode", reason)
          .register(meters)
          .increment();
    }
  }

  private IdempotentOperation.Completed<Receipt> corrected(
      PeopleCaller caller, String idempotencyKey, Commit commit) {
    TenantId tenant = caller.tenant();
    Proposal proposal = commit.proposal();
    schedules.lock(tenant);
    Target seen = resolveAgain(tenant, proposal);
    shifts.shareEmployments(
        tenant,
        seen.employmentId() == null ? List.of() : List.of(seen.employmentId()),
        seen.employee().id(),
        seen.shift().firstDate(),
        seen.shift().lastDate());
    schedules
        .lockSchedule(tenant, seen.shift().scheduleId())
        .orElseThrow(AttendanceCorrectionService::stale);
    if (proposal.operation() == Operation.REPLACE_TIMES) {
      attendance.lockRecord(tenant, proposal.recordId()).orElseThrow(this::notFound);
    }
    // Every fact again, under the locks: any change since the preview is stale.
    Target target = resolveAgain(tenant, proposal);
    if (target.version() != commit.targetVersion()) {
      throw stale();
    }
    Instant now = now();
    Preview fresh;
    try {
      fresh = evaluate(caller, target, proposal, now);
    } catch (ApiException refused) {
      throw refused.code() == ErrorCode.VALIDATION_FAILED ? refused : stale();
    }
    if (!fresh.previewDigest().equals(commit.previewDigest())) {
      throw stale();
    }
    Times after = proposal.after();
    Record before = target.record();
    if (after.checkedOutAt() == null) {
      Instant until = Attendance.checkOutUntil(target.shift().endAt());
      List<UUID> open =
          attendance.lockOpenOverlapping(
              tenant, target.employee().id(), after.checkedInAt(), until);
      if (open.stream().anyMatch(id -> before == null || !id.equals(before.id()))) {
        throw notAllowed();
      }
    }
    UUID recordId = before == null ? UUID.randomUUID() : before.id();
    Entry entry =
        new Entry(
            UUID.randomUUID(),
            recordId,
            target.version() + 1,
            proposal.operation(),
            before == null ? null : new Times(before.checkedInAt(), before.checkedOutAt()),
            after,
            proposal.reason(),
            proposal.explanation(),
            caller.subject(),
            now);
    Record binding =
        before != null
            ? before
            : new Record(
                recordId,
                target.shift().scheduleId(),
                target.shift().slotId(),
                target.employee().id(),
                target.employmentId(),
                target.shift().startAt(),
                target.shift().endAt(),
                null,
                after.checkedInAt(),
                null,
                after.checkedOutAt(),
                1,
                now);
    try {
      corrections.insert(
          tenant, entry, binding, commit.previewDigest(), digest(caller, idempotencyKey));
      if (before == null) {
        corrections.createRecord(tenant, binding);
      } else if (corrections.replace(tenant, recordId, before.correctionVersion(), after, now)
          != 1) {
        throw stale();
      }
      attendance.checkConsistent(tenant);
    } catch (DataIntegrityViolationException violated) {
      throw mapped(violated);
    }
    int reopened;
    try {
      // MVP-070D: approved time this correction materially changed is reopened, atomically.
      reopened =
          reopening.reopen(
              caller, entry.id(), target.employee().id(), target.shift().startAt(), now);
    } catch (DataIntegrityViolationException violated) {
      throw mapped(violated);
    }
    Record result = attendance.record(tenant, recordId).orElseThrow();
    recorded(caller, entry, result, commit.previewDigest(), proposal.explanationDigest());
    event(caller, entry, result);
    LOG.atInfo()
        .addKeyValue("operation", CREATE)
        .addKeyValue("outcome", "created")
        .addKeyValue("correctionOperation", entry.operation().name())
        .addKeyValue("reasonCode", entry.reason().name())
        .log("attendance_correction_recorded");
    return new IdempotentOperation.Completed<>(
        Receipt.of(entry, result, reopened), result.id(), Outcome.CREATED);
  }

  // ------------------------------------------------------------------------------------------
  // History
  // ------------------------------------------------------------------------------------------

  /**
   * A record's current effective values, its original events and its corrections, newest first.
   *
   * @param caller verified tenant administrator
   * @param recordId path value
   * @return the history, after its disclosure audit committed
   */
  public History history(PeopleCaller caller, String recordId) {
    return observed(
        HISTORY,
        "history",
        () ->
            read(
                () -> {
                  TenantId tenant = caller.tenant();
                  UUID id = AttendanceCorrectionCommand.recordId(recordId);
                  Record record = attendance.record(tenant, id).orElseThrow(this::notFound);
                  Shift shift = shifts.shift(tenant, record.slotId()).orElseThrow(this::notFound);
                  Substitution.Person employee =
                      shifts.person(tenant, record.employeeId()).orElseThrow(this::notFound);
                  List<HistoryEntry> entries = new ArrayList<>();
                  for (Entry entry : corrections.history(tenant, id)) {
                    entries.add(
                        new HistoryEntry(
                            entry.id(),
                            entry.version(),
                            entry.operation(),
                            TimesView.of(entry.before()),
                            TimesView.of(entry.after()),
                            entry.reason(),
                            entry.explanation(),
                            entry.correctedAt(),
                            entry.correctedBy().equals(caller.subject()),
                            administrator(tenant, entry.correctedBy())));
                  }
                  List<OriginalEvent> events =
                      corrections.originalEvents(tenant, id).stream()
                          .map(e -> new OriginalEvent(e.type(), e.recordedAt(), e.method()))
                          .toList();
                  History history =
                      History.of(record, employee, shift, site(tenant, shift), events, entries);
                  StringBuilder text =
                      new StringBuilder(
                              "DIVALHR-ATTENDANCE-CORRECTION-HISTORY-DISCLOSURE\n"
                                  + "version=1\n"
                                  + "record=")
                          .append(id)
                          .append('\n');
                  entries.forEach(e -> text.append("correction=").append(e.id()).append('\n'));
                  disclosed(
                      caller,
                      HISTORY,
                      "attendance-correction-history",
                      entries.size(),
                      text.toString());
                  return history;
                }));
  }

  // ------------------------------------------------------------------------------------------
  // Target and evaluation
  // ------------------------------------------------------------------------------------------

  /**
   * A resolved target.
   *
   * @param shift the immutable shift
   * @param employee the target employee
   * @param employmentId the record's employment, or the single qualifying employment
   * @param record the existing record, or {@code null}
   * @param version the target version (the record's correction count, or 0)
   */
  private record Target(
      Shift shift, Substitution.Person employee, UUID employmentId, Record record, int version) {}

  /** The target inside the verified tenant, or the one safe 404 (or a stable refusal). */
  private Target resolve(TenantId tenant, Proposal proposal) {
    if (proposal.operation() == Operation.REPLACE_TIMES) {
      Record record = attendance.record(tenant, proposal.recordId()).orElseThrow(this::notFound);
      Shift shift = shifts.shift(tenant, record.slotId()).orElseThrow(this::notFound);
      Substitution.Person employee =
          shifts.person(tenant, record.employeeId()).orElseThrow(this::notFound);
      return new Target(shift, employee, record.employmentId(), record, record.correctionVersion());
    }
    Shift shift = shifts.shift(tenant, proposal.slotId()).orElseThrow(this::notFound);
    UUID assignee =
        corrections.effectiveAssignee(tenant, shift.slotId()).orElseThrow(this::notFound);
    if (!assignee.equals(proposal.employeeId())) {
      throw notFound();
    }
    Substitution.Person employee =
        shifts.person(tenant, proposal.employeeId()).orElseThrow(this::notFound);
    if (!shift.operational()
        || attendance.forSlot(tenant, shift.slotId(), proposal.employeeId()).isPresent()) {
      // A cancelled or replaced schedule, or an existing record (REPLACE_TIMES applies).
      throw notAllowed();
    }
    List<UUID> qualifying = shifts.qualifyingEmployments(tenant, proposal.employeeId(), shift);
    if (qualifying.size() != 1) {
      // Zero or several: never chosen arbitrarily.
      throw notAllowed();
    }
    return new Target(shift, employee, qualifying.get(0), null, 0);
  }

  /** At commit: the target again; a target that is no longer correctable has changed (stale). */
  private Target resolveAgain(TenantId tenant, Proposal proposal) {
    try {
      return resolve(tenant, proposal);
    } catch (ApiException refused) {
      throw refused.code() == ErrorCode.ATTENDANCE_CORRECTION_TARGET_NOT_FOUND ? refused : stale();
    }
  }

  /** The proposal against the boundaries and the instant; the preview and its digest. */
  private Preview evaluate(PeopleCaller caller, Target target, Proposal proposal, Instant now) {
    Shift shift = target.shift();
    Times after = proposal.after();
    String broken = AttendanceCorrection.brokenRule(shift.startAt(), shift.endAt(), after, now);
    if (broken != null) {
      FieldErrors errors = new FieldErrors().add(broken, Constraint.RANGE);
      errors.throwIfAny();
    }
    Record record = target.record();
    Times before = record == null ? null : new Times(record.checkedInAt(), record.checkedOutAt());
    if (before != null && before.equals(after)) {
      throw notAllowed();
    }
    List<Warning> warnings = new ArrayList<>();
    if (before != null && before.checkedOutAt() != null && after.checkedOutAt() == null) {
      warnings.add(Warning.CHECK_OUT_REMOVED);
    }
    if (after.checkedOutAt() == null) {
      warnings.add(Warning.NO_CHECK_OUT);
    }
    if (!shift.operational()) {
      warnings.add(Warning.SCHEDULE_NOT_OPERATIONAL);
    }
    if (record != null && record.checkInEventId() != null) {
      warnings.add(Warning.ORIGINAL_EVENTS_KEPT);
    }
    boolean approved =
        reopening.approvedCovers(caller.tenant(), target.employee().id(), shift.startAt());
    if (approved) {
      warnings.add(Warning.APPROVED_TIME_MAY_REOPEN);
    }
    String digest = previewDigest(caller, target, proposal, before, approved);
    return Preview.of(
        proposal.operation(),
        record == null ? null : record.id(),
        target.version(),
        target.employee(),
        shift,
        site(caller.tenant(), shift),
        before,
        after,
        proposal.reason(),
        proposal.explanation(),
        AttendanceCorrection.exceptions(shift.startAt(), shift.endAt(), before, now),
        AttendanceCorrection.exceptions(shift.startAt(), shift.endAt(), after, now),
        warnings,
        now,
        digest);
  }

  /**
   * The preview digest: the tenant, the administrator, the target's stable bindings and version,
   * the immutable boundaries, the before and after values, the reason, the explanation's digest and
   * whether approved time covers the shift (MVP-070D). Never the preview instant, so a commit
   * re-derives it exactly from the same facts.
   */
  private static String previewDigest(
      PeopleCaller caller, Target target, Proposal proposal, Times before, boolean approved) {
    Shift shift = target.shift();
    return Fingerprints.sha256(
        "DIVALHR-ATTENDANCE-CORRECTION-PREVIEW\nversion=2\ntenant="
            + caller.tenant()
            + "\nsubject="
            + caller.subject()
            + "\noperation="
            + proposal.operation()
            + "\nrecord="
            + (target.record() == null ? "-" : target.record().id())
            + "\nslot="
            + shift.slotId()
            + "\nschedule="
            + shift.scheduleId()
            + "\nemployee="
            + target.employee().id()
            + "\nemployment="
            + target.employmentId()
            + "\nstart="
            + shift.startAt()
            + "\nend="
            + shift.endAt()
            + "\noperational="
            + shift.operational()
            + "\ntargetVersion="
            + target.version()
            + "\nbeforeIn="
            + (before == null ? "-" : before.checkedInAt())
            + "\nbeforeOut="
            + (before == null || before.checkedOutAt() == null ? "-" : before.checkedOutAt())
            + "\nafterIn="
            + proposal.after().checkedInAt()
            + "\nafterOut="
            + (proposal.after().checkedOutAt() == null ? "-" : proposal.after().checkedOutAt())
            + "\nreason="
            + proposal.reason()
            + "\nexplanation="
            + proposal.explanationDigest()
            + "\napprovedTime="
            + approved
            + "\n");
  }

  // ------------------------------------------------------------------------------------------
  // Audit, outbox and shared
  // ------------------------------------------------------------------------------------------

  /** The audit record: bounded metadata; the evidence only as a digest. */
  private void recorded(
      PeopleCaller caller,
      Entry entry,
      Record record,
      String previewDigest,
      String explanationDigest) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", WorkScheduleService.SCHEMA_VERSION);
    metadata.put("operation", entry.operation().name());
    metadata.put("reasonCode", entry.reason().name());
    metadata.put(
        "hadCheckOutBefore", entry.before() != null && entry.before().checkedOutAt() != null);
    metadata.put("hasCheckOutAfter", entry.after().checkedOutAt() != null);
    Map<String, Object> state = new TreeMap<>();
    state.put("tenantId", caller.tenant().toString());
    state.put("correctionId", entry.id().toString());
    state.put("attendanceRecordId", record.id().toString());
    state.put("scheduleId", record.scheduleId().toString());
    state.put("slotId", record.slotId().toString());
    state.put("employeeId", record.employeeId().toString());
    state.put("employmentId", record.employmentId().toString());
    state.put("correctionVersion", entry.version());
    state.put(
        "beforeCheckedInAt",
        entry.before() == null ? "" : String.valueOf(entry.before().checkedInAt()));
    state.put(
        "beforeCheckedOutAt",
        entry.before() == null ? "" : String.valueOf(entry.before().checkedOutAt()));
    state.put("afterCheckedInAt", entry.after().checkedInAt().toString());
    state.put("afterCheckedOutAt", String.valueOf(entry.after().checkedOutAt()));
    state.put("explanationDigest", explanationDigest);
    state.put("previewDigest", previewDigest);
    state.put("correctedAt", entry.correctedAt().toString());
    state.put("correctedBy", caller.subject());
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            entry.correctedAt(),
            caller.subject(),
            CREATE,
            RESOURCE,
            record.id(),
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(json.writeValueAsString(state))));
  }

  /** Stable IDs, the operation, the reason, presence flags, the version and the instant only. */
  private void event(PeopleCaller caller, Entry entry, Record record) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("attendanceRecordId", record.id().toString());
    data.put("scheduleId", record.scheduleId().toString());
    data.put("slotId", record.slotId().toString());
    data.put("employeeId", record.employeeId().toString());
    data.put("employmentId", record.employmentId().toString());
    data.put("correctionId", entry.id().toString());
    data.put("operation", entry.operation().name());
    data.put("reasonCode", entry.reason().name());
    data.put("hadRecordBefore", entry.before() != null);
    data.put("hadCheckOutBefore", entry.before() != null && entry.before().checkedOutAt() != null);
    data.put("hasCheckOutAfter", entry.after().checkedOutAt() != null);
    data.put("correctionVersion", entry.version());
    data.put("correctedAt", entry.correctedAt().toString());
    outbox.append(
        new EventEnvelope(
            UUID.randomUUID().toString(),
            CORRECTED_EVENT,
            WorkScheduleService.SCHEMA_VERSION,
            caller.tenant().toString(),
            SOURCE,
            record.id().toString(),
            entry.correctedAt().toString(),
            caller.correlationId(),
            null,
            data));
  }

  /** The fail-closed disclosure audit: the view and count; identifiers only in the digest. */
  private void disclosed(PeopleCaller caller, String action, String view, int count, String text) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", WorkScheduleService.SCHEMA_VERSION);
    metadata.put("view", view);
    metadata.put("page", "first");
    metadata.put("resultCount", count);
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            now(),
            caller.subject(),
            action,
            "organization",
            caller.tenant().value(),
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(text)));
  }

  /**
   * A stable pseudonymous reference to an administrator within the tenant: the first 8 hex digits
   * of a domain-separated digest of the tenant and subject. Never the subject.
   */
  static String administrator(TenantId tenant, String subject) {
    return Fingerprints.sha256(
            "DIVALHR-ATTENDANCE-CORRECTION-ADMINISTRATOR\ntenant="
                + tenant
                + "\nsubject="
                + subject)
        .substring(0, 8)
        .toUpperCase(Locale.ROOT);
  }

  /** The evidence's idempotency reference: a digest, never the key itself. */
  private static String digest(PeopleCaller caller, String idempotencyKey) {
    return Fingerprints.sha256(
        "DIVALHR-ATTENDANCE-CORRECTION-IDEMPOTENCY\noperation="
            + CREATE
            + "\nsubject="
            + caller.subject()
            + "\nkey="
            + idempotencyKey);
  }

  private UnitView site(TenantId tenant, Shift shift) {
    return directory.resolveIds(tenant, Set.of(shift.siteId())).get(shift.siteId());
  }

  private Instant now() {
    return calendar.now().truncatedTo(ChronoUnit.MICROS);
  }

  private ApiException notFound() {
    return new ApiException(ErrorCode.ATTENDANCE_CORRECTION_TARGET_NOT_FOUND, Map.of());
  }

  private static ApiException notAllowed() {
    return new ApiException(ErrorCode.ATTENDANCE_CORRECTION_NOT_ALLOWED, Map.of());
  }

  private static ApiException stale() {
    return new ApiException(ErrorCode.ATTENDANCE_CORRECTION_PREVIEW_STALE, Map.of());
  }

  /** The stable conflict of a constraint the locks should have made unreachable; no DB text. */
  private static ApiException mapped(DataIntegrityViolationException violated) {
    String constraint = ScheduleConstraintViolations.constraint(violated).orElse("");
    return switch (constraint) {
      case ScheduleConstraintViolations.CORRECTION_SEQUENCE,
          ScheduleConstraintViolations.CORRECTION_VERSION,
          ScheduleConstraintViolations.ATTENDANCE_SLOT_EMPLOYEE,
          ScheduleConstraintViolations.TIMESHEET_ACTION_SEQUENCE,
          ScheduleConstraintViolations.TIMESHEET_ACTION_TRANSITION,
          ScheduleConstraintViolations.TIMESHEET_ACTION_REOPENING,
          ScheduleConstraintViolations.TIMESHEET_ACTION_REOPENED_CYCLE,
          ScheduleConstraintViolations.TIMESHEET_GUARD ->
          stale();
      case ScheduleConstraintViolations.CORRECTION_CREATE,
          ScheduleConstraintViolations.ATTENDANCE_ONE_OPEN ->
          notAllowed();
      default -> throw violated;
    };
  }

  /** Records an administrator's read or preview outcome; rejections log only their code. */
  private <T> T observed(String operation, String view, Supplier<T> work) {
    try {
      T result = work.get();
      metrics.record(operation, Outcome.LISTED);
      LOG.atInfo()
          .addKeyValue("operation", operation)
          .addKeyValue("outcome", view == null ? "previewed" : "listed")
          .log(view == null ? "attendance_correction_previewed" : "attendance_correction_listed");
      return result;
    } catch (ApiException rejected) {
      Outcome outcome =
          rejected.code() == ErrorCode.ATTENDANCE_CORRECTION_TARGET_NOT_FOUND
              ? Outcome.NOT_FOUND
              : rejected.code() == ErrorCode.ATTENDANCE_CORRECTION_NOT_ALLOWED
                  ? Outcome.STATE_CONFLICT
                  : Outcome.VALIDATION_FAILED;
      metrics.record(operation, outcome);
      LOG.atInfo()
          .addKeyValue("operation", operation)
          .addKeyValue("outcome", outcome.name().toLowerCase(Locale.ROOT))
          .addKeyValue("code", rejected.code().name())
          .log("attendance_correction_rejected");
      throw rejected;
    } catch (RuntimeException failure) {
      metrics.record(operation, Outcome.FAILURE);
      throw failure;
    }
  }

  /** A bounded read transaction; the result is returned only after it committed. */
  private <T> T read(Supplier<T> work) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setTimeout((int) TRANSACTION_TIMEOUT.toSeconds());
    T result = template.execute(status -> work.get());
    return Objects.requireNonNull(result, "read transaction returned nothing");
  }
}
