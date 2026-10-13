package com.divalhr.core.people.schedule.application;

import com.divalhr.core.people.application.BusinessCalendar;
import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.schedule.api.OfflineAttendanceResponses.Result;
import com.divalhr.core.people.schedule.api.SyncOfflineAttendanceRequest;
import com.divalhr.core.people.schedule.application.OfflineAttendanceCommand.Sync;
import com.divalhr.core.people.schedule.domain.Attendance;
import com.divalhr.core.people.schedule.domain.Attendance.Record;
import com.divalhr.core.people.schedule.domain.OfflineAttendance;
import com.divalhr.core.people.schedule.domain.Substitution.Shift;
import com.divalhr.core.people.schedule.internal.JdbcAttendanceRepository;
import com.divalhr.core.people.schedule.internal.JdbcAttendanceRepository.Offline;
import com.divalhr.core.people.schedule.internal.JdbcAttendanceRepository.OfflineEvent;
import com.divalhr.core.people.schedule.internal.JdbcWorkScheduleRepository;
import com.divalhr.core.people.schedule.internal.JdbcWorkScheduleSubstitutionRepository;
import com.divalhr.core.people.schedule.internal.JdbcWorkScheduleSubstitutionRepository.Coverage;
import com.divalhr.core.people.schedule.internal.ScheduleConstraintViolations;
import com.divalhr.core.platform.access.EmployeeAccessLinks;
import com.divalhr.core.platform.access.EmployeeAccessLinks.SelfLink;
import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import com.divalhr.core.platform.security.ScopeAuthorizationInterceptor;
import com.divalhr.core.platform.tenancy.TenantId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Offline attendance synchronization (MVP-061A, A61A-1 to A61A-8; ADR 0016 amendment): the caller
 * submits one check-in or check-out captured while offline, identified by its client event ID, with
 * the claimed device time and offset. The device time is untrusted evidence: it is checked against
 * the unchanged MVP-060A windows and the fixed safeguards of {@link OfflineAttendance}; the receipt
 * instant is the server's ({@link BusinessCalendar}). The MVP-060A eligibility applies at receipt:
 * the operational schedule and the slot's current coverage for a check-in, the caller's own open
 * record for a check-out.
 *
 * <p>Idempotency (A61A-2): the durable identity is tenant, the caller's linked employee and the
 * client event ID, stored on the event itself ({@code attendance_event_client_event_unique}); the
 * HTTP {@code Idempotency-Key} is not used. The same identity and payload returns the original
 * result with the record's current status, without a new event, audit record or outbox event; a
 * changed slot, type, instant or offset is {@code 409 IDEMPOTENCY_KEY_REUSED} without params.
 *
 * <p>Lock order, as MVP-060A (A60A-6): the tenant's scheduling lock (1); the employment(s) of the
 * slot's coverage (check-in) or of the slot's records (check-out) {@code FOR SHARE} (2); the
 * caller's link and membership {@code FOR SHARE} (3); the replay lookup; the schedule {@code FOR
 * UPDATE}, then the slot and coverage rows (4); the employee's overlapping open records or the
 * record {@code FOR UPDATE} (5); the evidence with its provenance, the projection, the deferred
 * checks, the audit record and the outbox event commit together (6). Every submission of a tenant
 * serializes on (1), so two identical submissions give one event and one result, and two different
 * ones are decided one after the other by the existing invariants.
 *
 * <p>Logs and metrics carry the operation, a bounded outcome, the event type and whether it was a
 * replay; never an ID, instant, offset, subject, name or label. The request body and errors are
 * never logged.
 */
@Service
public class OfflineAttendanceService {

  /** An offline submission (interceptor operation and audit action). */
  public static final String OFFLINE_SYNC = "attendance.offline-event-sync";

  /** Submissions by operation, bounded outcome, event type and replay. */
  public static final String SYNC_METRIC = "divalhr.attendance.offline.sync";

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.schedule");
  private static final String SOURCE = "core-api/people";
  private static final String RESOURCE = "attendance-record";
  private static final Duration TRANSACTION_TIMEOUT = Duration.ofSeconds(15);

  private final JdbcWorkScheduleRepository schedules;
  private final JdbcWorkScheduleSubstitutionRepository shifts;
  private final JdbcAttendanceRepository attendance;
  private final EmployeeAccessLinks links;
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
   * @param schedules schedule storage (the scheduling lock and schedule locks)
   * @param shifts shift, coverage and qualifying-employment reads and employment locks
   * @param attendance attendance storage
   * @param links the identity port (joins this module's transactions)
   * @param calendar the platform clock (the receipt instant)
   * @param audit audit recorder
   * @param outbox outbox writer
   * @param metrics operation metrics
   * @param meters meter registry
   * @param transactionManager transaction manager
   * @param json JSON mapper
   */
  public OfflineAttendanceService(
      JdbcWorkScheduleRepository schedules,
      JdbcWorkScheduleSubstitutionRepository shifts,
      JdbcAttendanceRepository attendance,
      EmployeeAccessLinks links,
      BusinessCalendar calendar,
      AuditRecorder audit,
      OutboxWriter outbox,
      OperationMetrics metrics,
      MeterRegistry meters,
      PlatformTransactionManager transactionManager,
      JsonMapper json) {
    this.schedules = schedules;
    this.shifts = shifts;
    this.attendance = attendance;
    this.links = links;
    this.calendar = calendar;
    this.audit = audit;
    this.outbox = outbox;
    this.metrics = metrics;
    this.meters = meters;
    this.transactionManager = transactionManager;
    this.json = json;
  }

  /**
   * The result of a submission and whether it replayed an earlier one.
   *
   * @param result the response body
   * @param replayed true for an identical earlier submission (200), false when applied now (201)
   */
  public record Synced(Result result, boolean replayed) {}

  /**
   * Applies one offline event of the caller, or returns the original result of the identical
   * earlier submission.
   *
   * @param caller verified employee
   * @param body the submission
   * @return the result
   */
  public Synced sync(PeopleCaller caller, SyncOfflineAttendanceRequest body) {
    Timer.Sample sample = Timer.start(meters);
    String type = "unknown";
    String outcome = "failure";
    boolean replayed = false;
    try {
      Sync command = OfflineAttendanceCommand.sync(body);
      type = command.type().name().toLowerCase(Locale.ROOT);
      Synced synced = counted(() -> transact(caller, command));
      replayed = synced.replayed();
      outcome = replayed ? "replayed" : "applied";
      metrics.record(OFFLINE_SYNC, replayed ? Outcome.REPLAYED : Outcome.CREATED);
      return synced;
    } catch (ApiException refused) {
      outcome = refused.code().name().toLowerCase(Locale.ROOT);
      metrics.record(OFFLINE_SYNC, outcomeOf(refused.code()));
      throw refused;
    } catch (RuntimeException failure) {
      metrics.record(OFFLINE_SYNC, Outcome.FAILURE);
      throw failure;
    } finally {
      Counter.builder(SYNC_METRIC)
          .description("Offline attendance submissions")
          .tag("operation", OFFLINE_SYNC)
          .tag("outcome", outcome)
          .tag("type", type)
          .tag("replay", Boolean.toString(replayed))
          .register(meters)
          .increment();
      sample.stop(
          Timer.builder(AttendanceService.LATENCY_METRIC)
              .tag("operation", OFFLINE_SYNC)
              .tag("outcome", latencyOutcome(outcome))
              .register(meters));
      LOG.atInfo()
          .addKeyValue("operation", OFFLINE_SYNC)
          .addKeyValue("outcome", outcome)
          .addKeyValue("type", type)
          .addKeyValue("replay", replayed)
          .log("attendance_offline_synced");
    }
  }

  private Synced transact(PeopleCaller caller, Sync command) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setTimeout((int) TRANSACTION_TIMEOUT.toSeconds());
    Synced synced =
        template.execute(
            status -> {
              schedules.lock(caller.tenant());
              return command.type() == OfflineAttendance.Type.CHECK_IN
                  ? checkIn(caller, command)
                  : checkOut(caller, command);
            });
    if (synced == null) {
      throw new IllegalStateException("transaction returned no result");
    }
    return synced;
  }

  // ------------------------------------------------------------------------------------------
  // Check-in
  // ------------------------------------------------------------------------------------------

  private Synced checkIn(PeopleCaller caller, Sync command) {
    TenantId tenant = caller.tenant();
    Optional<Shift> seen = shifts.shift(tenant, command.slotId());
    Optional<Coverage> covered = seen.flatMap(shift -> shifts.coverage(tenant, shift.slotId()));
    covered.ifPresent(
        coverage ->
            shifts.shareEmployments(
                tenant,
                List.of(),
                coverage.employeeId(),
                seen.get().firstDate(),
                seen.get().lastDate()));
    // The link is checked before anything about the shift or the client event is answered.
    SelfLink link = link(caller);
    Optional<Synced> replay = replay(tenant, link, command);
    if (replay.isPresent()) {
      return replay.get();
    }
    if (seen.isEmpty()
        || covered.isEmpty()
        || !covered.get().employeeId().equals(link.employeeId())) {
      throw shiftNotFound();
    }
    schedules.lockSchedule(tenant, seen.get().scheduleId()).orElseThrow(this::shiftNotFound);
    // Re-read under the schedule lock: a terminal action or a substitution may have committed.
    Shift shift = shifts.shift(tenant, command.slotId()).orElseThrow(this::shiftNotFound);
    Optional<Coverage> coverage = shifts.coverage(tenant, shift.slotId());
    if (!shift.operational()
        || coverage.isEmpty()
        || !coverage.get().employeeId().equals(link.employeeId())) {
      throw shiftNotFound();
    }
    Instant received = now();
    Instant at = command.occurredAt();
    safeguarded(at, received, shift.endAt());
    if (at.isBefore(Attendance.checkInOpensAt(shift.startAt()))) {
      throw new ApiException(ErrorCode.ATTENDANCE_CHECK_IN_TOO_EARLY, Map.of());
    }
    if (!at.isBefore(shift.endAt())) {
      throw new ApiException(ErrorCode.ATTENDANCE_CHECK_IN_WINDOW_CLOSED, Map.of());
    }
    // Exactly the open records attendance_record_one_open would refuse this record against.
    List<UUID> open =
        attendance.lockOpenOverlapping(
            tenant, link.employeeId(), at, Attendance.checkOutUntil(shift.endAt()));
    if (attendance.forSlot(tenant, shift.slotId(), link.employeeId()).isPresent()
        || !open.isEmpty()) {
      throw conflict();
    }
    List<UUID> qualifying = shifts.qualifyingEmployments(tenant, link.employeeId(), shift);
    if (qualifying.size() != 1) {
      // Zero or several: never chosen arbitrarily (as MVP-060A).
      throw new ApiException(ErrorCode.ATTENDANCE_STATE_CONFLICT, Map.of());
    }
    Record record =
        new Record(
            UUID.randomUUID(),
            shift.scheduleId(),
            shift.slotId(),
            link.employeeId(),
            qualifying.get(0),
            shift.startAt(),
            shift.endAt(),
            UUID.randomUUID(),
            at,
            null,
            null);
    try {
      attendance.insertOfflineCheckIn(
          tenant,
          record,
          caller.subject(),
          digest(caller, command),
          new Offline(command.clientEventId(), command.offsetMinutes(), received));
      attendance.checkConsistent(tenant);
    } catch (DataIntegrityViolationException violated) {
      throw mapped(violated);
    }
    return applied(
        caller,
        command,
        record,
        record.checkInEventId(),
        received,
        AttendanceService.CHECKED_IN_EVENT);
  }

  // ------------------------------------------------------------------------------------------
  // Check-out
  // ------------------------------------------------------------------------------------------

  private Synced checkOut(PeopleCaller caller, Sync command) {
    TenantId tenant = caller.tenant();
    List<Record> onSlot = attendance.slotRecords(tenant, command.slotId());
    List<UUID> employments = onSlot.stream().map(Record::employmentId).distinct().toList();
    if (!employments.isEmpty()) {
      // The records' employments only (no employee-wide range: the dates are unused).
      shifts.shareEmployments(tenant, employments, null, LocalDate.EPOCH, LocalDate.EPOCH);
    }
    SelfLink link = link(caller);
    Optional<Synced> replay = replay(tenant, link, command);
    if (replay.isPresent()) {
      return replay.get();
    }
    // No record of the caller's on the slot: the same 404 as an unknown or foreign slot.
    Record seen =
        onSlot.stream()
            .filter(candidate -> candidate.employeeId().equals(link.employeeId()))
            .findFirst()
            .orElseThrow(this::recordNotFound);
    schedules.lockSchedule(tenant, seen.scheduleId());
    Record record = attendance.lockRecord(tenant, seen.id()).orElseThrow();
    Instant received = now();
    Instant at = command.occurredAt();
    Instant until = Attendance.checkOutUntil(record.slotEndAt());
    safeguarded(at, received, until);
    if (record.status() == Attendance.Status.COMPLETED) {
      throw conflict();
    }
    if (at.isAfter(until)) {
      throw new ApiException(ErrorCode.ATTENDANCE_CHECK_OUT_WINDOW_CLOSED, Map.of());
    }
    if (!at.isAfter(record.checkedInAt())) {
      // Never reordered: the accepted check-in stands.
      throw conflict();
    }
    UUID eventId = UUID.randomUUID();
    try {
      int updated =
          attendance.insertOfflineCheckOut(
              tenant,
              record,
              eventId,
              at,
              caller.subject(),
              digest(caller, command),
              new Offline(command.clientEventId(), command.offsetMinutes(), received));
      if (updated != 1) {
        throw conflict();
      }
      attendance.checkConsistent(tenant);
    } catch (DataIntegrityViolationException violated) {
      throw mapped(violated);
    }
    Record completed =
        new Record(
            record.id(),
            record.scheduleId(),
            record.slotId(),
            record.employeeId(),
            record.employmentId(),
            record.slotStartAt(),
            record.slotEndAt(),
            record.checkInEventId(),
            record.checkedInAt(),
            eventId,
            at);
    return applied(
        caller, command, completed, eventId, received, AttendanceService.CHECKED_OUT_EVENT);
  }

  // ------------------------------------------------------------------------------------------
  // Shared
  // ------------------------------------------------------------------------------------------

  /**
   * The original result of the caller's earlier submission with this client event ID, if any. Any
   * difference in the slot, type, claimed instant or offset is {@code IDEMPOTENCY_KEY_REUSED}
   * without params: the stored payload is never revealed.
   */
  private Optional<Synced> replay(TenantId tenant, SelfLink link, Sync command) {
    Optional<OfflineEvent> stored =
        attendance.offlineEvent(tenant, link.employeeId(), command.clientEventId());
    if (stored.isEmpty()) {
      return Optional.empty();
    }
    OfflineEvent event = stored.get();
    if (!event.slotId().equals(command.slotId())
        || event.type() != command.type().eventType()
        || !event.occurredAt().equals(command.occurredAt())
        || event.offsetMinutes() != command.offsetMinutes()) {
      throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED, Map.of());
    }
    return Optional.of(
        new Synced(
            Result.of(
                OfflineAttendance.Outcome.ALREADY_APPLIED,
                command.clientEventId(),
                event.recordId(),
                event.eventId(),
                OfflineAttendance.Type.of(event.type()),
                event.occurredAt(),
                event.receivedAt(),
                event.recordStatus()),
            true));
  }

  /** The audit record, the outbox event and the result of a first application. */
  private Synced applied(
      PeopleCaller caller,
      Sync command,
      Record record,
      UUID eventId,
      Instant received,
      String eventType) {
    Attendance.EventType type = command.type().eventType();
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", WorkScheduleService.SCHEMA_VERSION);
    metadata.put("eventType", type.name());
    metadata.put("outcome", OfflineAttendance.Outcome.APPLIED.name());
    metadata.put("recordingMethod", Attendance.Method.EMPLOYEE_OFFLINE_SYNC.name());
    metadata.put("replay", false);
    Map<String, Object> state = new TreeMap<>(ids(record));
    state.put("tenantId", caller.tenant().toString());
    state.put("attendanceEventId", eventId.toString());
    state.put("clientEventId", command.clientEventId().toString());
    state.put("eventType", type.name());
    state.put("deviceOccurredAt", command.occurredAt().toString());
    state.put("serverReceivedAt", received.toString());
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            received,
            caller.subject(),
            OFFLINE_SYNC,
            RESOURCE,
            record.id(),
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(json.writeValueAsString(state))));
    // The existing attendance envelope, unchanged: the IDs, the type and the record's instant
    // (the claimed device time). No consumer needs the method or the receipt; never the offset.
    Map<String, Object> data = new LinkedHashMap<>(ids(record));
    data.put("eventType", type.name());
    data.put("recordedAt", command.occurredAt().toString());
    outbox.append(
        new EventEnvelope(
            UUID.randomUUID().toString(),
            eventType,
            WorkScheduleService.SCHEMA_VERSION,
            caller.tenant().toString(),
            SOURCE,
            record.id().toString(),
            received.toString(),
            caller.correlationId(),
            null,
            data));
    return new Synced(
        Result.of(
            OfflineAttendance.Outcome.APPLIED,
            command.clientEventId(),
            record.id(),
            eventId,
            command.type(),
            command.occurredAt(),
            received,
            record.status()),
        false);
  }

  private static Map<String, Object> ids(Record record) {
    Map<String, Object> ids = new LinkedHashMap<>();
    ids.put("attendanceRecordId", record.id().toString());
    ids.put("scheduleId", record.scheduleId().toString());
    ids.put("slotId", record.slotId().toString());
    ids.put("employeeId", record.employeeId().toString());
    ids.put("employmentId", record.employmentId().toString());
    return ids;
  }

  /** The fixed safeguards of the claimed device time (A61A-3). */
  private static void safeguarded(Instant occurredAt, Instant receivedAt, Instant windowClosesAt) {
    OfflineAttendance.refusal(occurredAt, receivedAt, windowClosesAt)
        .ifPresent(
            safeguard -> {
              throw new ApiException(
                  safeguard == OfflineAttendance.Safeguard.FUTURE_TIME
                      ? ErrorCode.OFFLINE_ATTENDANCE_FUTURE_TIME
                      : ErrorCode.OFFLINE_ATTENDANCE_TOO_OLD,
                  Map.of());
            });
  }

  /** The evidence's client-event reference: a digest, never the identifier itself. */
  private static String digest(PeopleCaller caller, Sync command) {
    return Fingerprints.sha256(
        "DIVALHR-ATTENDANCE-OFFLINE\noperation="
            + OFFLINE_SYNC
            + "\nsubject="
            + caller.subject()
            + "\nclientEventId="
            + command.clientEventId());
  }

  private Instant now() {
    return calendar.now().truncatedTo(ChronoUnit.MICROS);
  }

  /** The caller's own active link ({@code FOR SHARE}), or {@code 403 EMPLOYEE_LINK_REQUIRED}. */
  private SelfLink link(PeopleCaller caller) {
    return links
        .linkedEmployee(caller.tenant(), caller.subject())
        .orElseThrow(() -> new ApiException(ErrorCode.EMPLOYEE_LINK_REQUIRED, Map.of()));
  }

  private ApiException shiftNotFound() {
    return new ApiException(ErrorCode.ATTENDANCE_SHIFT_NOT_FOUND, Map.of());
  }

  private ApiException recordNotFound() {
    return new ApiException(ErrorCode.ATTENDANCE_RECORD_NOT_FOUND, Map.of());
  }

  private static ApiException conflict() {
    return new ApiException(ErrorCode.OFFLINE_ATTENDANCE_CONFLICT, Map.of());
  }

  /**
   * The stable answer to a constraint the locks should have made unreachable; no database text.
   * Another accepted event is a conflict; anything else is rethrown.
   */
  private static ApiException mapped(DataIntegrityViolationException violated) {
    String constraint = ScheduleConstraintViolations.constraint(violated).orElse("");
    return switch (constraint) {
      case ScheduleConstraintViolations.ATTENDANCE_SLOT_EMPLOYEE,
          ScheduleConstraintViolations.ATTENDANCE_ONE_OPEN,
          ScheduleConstraintViolations.ATTENDANCE_EVENT_TYPE ->
          conflict();
      default -> throw violated;
    };
  }

  /** Counts link denials and undisclosable targets on the bounded self-service counter only. */
  private <T> T counted(java.util.function.Supplier<T> work) {
    try {
      return work.get();
    } catch (ApiException denied) {
      if (denied.code() == ErrorCode.EMPLOYEE_LINK_REQUIRED) {
        ScopeAuthorizationInterceptor.selfServiceDenied(meters, OFFLINE_SYNC, "link_required");
      } else if (denied.code() == ErrorCode.ATTENDANCE_SHIFT_NOT_FOUND
          || denied.code() == ErrorCode.ATTENDANCE_RECORD_NOT_FOUND) {
        ScopeAuthorizationInterceptor.selfServiceDenied(meters, OFFLINE_SYNC, "not_found");
      }
      throw denied;
    }
  }

  private static String latencyOutcome(String outcome) {
    return switch (outcome) {
      case "applied" -> "success";
      case "replayed", "failure" -> outcome;
      default -> "refused";
    };
  }

  private static Outcome outcomeOf(ErrorCode code) {
    return switch (code) {
      case VALIDATION_FAILED, OFFLINE_ATTENDANCE_OFFSET_MISMATCH -> Outcome.VALIDATION_FAILED;
      case EMPLOYEE_LINK_REQUIRED -> Outcome.DENIED;
      case ATTENDANCE_SHIFT_NOT_FOUND, ATTENDANCE_RECORD_NOT_FOUND -> Outcome.NOT_FOUND;
      case IDEMPOTENCY_KEY_REUSED -> Outcome.IDEMPOTENCY_CONFLICT;
      default -> Outcome.STATE_CONFLICT;
    };
  }
}
