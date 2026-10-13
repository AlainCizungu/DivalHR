package com.divalhr.core.people.schedule.application;

import com.divalhr.core.people.application.BusinessCalendar;
import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.schedule.api.AttendanceResponses.Item;
import com.divalhr.core.people.schedule.api.AttendanceResponses.Page;
import com.divalhr.core.people.schedule.api.AttendanceResponses.Receipt;
import com.divalhr.core.people.schedule.api.CheckInAttendanceRequest;
import com.divalhr.core.people.schedule.application.AttendanceCommand.CheckIn;
import com.divalhr.core.people.schedule.application.AttendanceCommand.CheckOut;
import com.divalhr.core.people.schedule.domain.Attendance;
import com.divalhr.core.people.schedule.domain.Attendance.HistoryItem;
import com.divalhr.core.people.schedule.domain.Attendance.Record;
import com.divalhr.core.people.schedule.domain.Substitution.Shift;
import com.divalhr.core.people.schedule.internal.JdbcAttendanceRepository;
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
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.security.ScopeAuthorizationInterceptor;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.UnitView;
import com.divalhr.core.platform.tenancy.TenantId;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Online attendance on scheduled shifts (MVP-060A, A60A-1 to A60A-7; ADR 0016). An employee checks
 * in on a shift of an operational schedule whose current operational coverage is their own (the
 * published assignee, or an approved MVP-051 substitute), and checks out of their own open record.
 * Authorization (role {@code employee}, verified tenant, active membership) has run before this;
 * the caller's own employee is resolved through the identity port's current active link ({@code FOR
 * SHARE}). Tenant administrators have no recording power from their role. The instant is always the
 * server's ({@link BusinessCalendar}), never the browser's.
 *
 * <p>Lock order (A60A-6): idempotency (0), the tenant's scheduling lock (1), the qualifying
 * employment(s) {@code FOR SHARE} (2), the caller's link and membership {@code FOR SHARE} (3), the
 * schedule {@code FOR UPDATE}, then the slot and coverage rows (4), the employee's actionable open
 * records and the record {@code FOR UPDATE} (5); then the re-evaluation of the link, coverage and
 * window (6), the evidence, projection, deferred checks, audit record and outbox event, committed
 * with the idempotency completion (7). Cancellation, replacement and substitution decisions take
 * the same scheduling lock first (after the manager-graph lock, which attendance never takes):
 * every race serializes to one valid outcome.
 *
 * <p>Labels, names, numbers and subjects are Restricted HR: never in logs, metrics, audit metadata,
 * events or errors. The subject is stored only in the controlled evidence field.
 */
@Service
public class AttendanceService {

  /** The employee's history (interceptor operation and disclosure audit action). */
  public static final String SELF_LIST = "attendance.self-list";

  /** A check-in (idempotency operation, interceptor operation and audit action). */
  public static final String CHECK_IN = "attendance.check-in";

  /** A check-out (idempotency operation, interceptor operation and audit action). */
  public static final String CHECK_OUT = "attendance.check-out";

  /** Outbox event of a check-in. */
  public static final String CHECKED_IN_EVENT = "people.attendance.checked-in.v1";

  /** Outbox event of a check-out. */
  public static final String CHECKED_OUT_EVENT = "people.attendance.checked-out.v1";

  /** Per-subject write bucket (check-ins and check-outs). */
  public static final String SUBJECT_WRITE_BUCKET = "attendance-write";

  /** Per-tenant write bucket (check-ins and check-outs). */
  public static final String TENANT_WRITE_BUCKET = "attendance-write";

  /** Command latency, tagged by operation and a bounded outcome. */
  public static final String LATENCY_METRIC = "divalhr.attendance.command.duration";

  /** History page size: default and maximum (A60A-4). */
  static final int DEFAULT_LIMIT = 20;

  static final int MAX_LIMIT = 100;

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.schedule");
  private static final String SOURCE = "core-api/people";
  private static final String RESOURCE = "attendance-record";
  private static final Duration TRANSACTION_TIMEOUT = Duration.ofSeconds(15);
  private static final IdempotentOperation.Spec CHECK_IN_SPEC =
      new IdempotentOperation.Spec(CHECK_IN, "attendance", "check_in", "checked_in", 201);
  private static final IdempotentOperation.Spec CHECK_OUT_SPEC =
      new IdempotentOperation.Spec(CHECK_OUT, "attendance", "check_out", "checked_out", 200);
  private static final String HISTORY = "AH";
  private static final Pattern ANCHORED = Pattern.compile("^AH[0-9]{1,18}$");
  private static final Pattern LIMIT = Pattern.compile("^[0-9]{1,3}$");

  private final IdempotentOperation operations;
  private final JdbcWorkScheduleRepository schedules;
  private final JdbcWorkScheduleSubstitutionRepository shifts;
  private final JdbcAttendanceRepository attendance;
  private final EmployeeAccessLinks links;
  private final OrganizationPlacementDirectory directory;
  private final BusinessCalendar calendar;
  private final CursorCodec cursors;
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
   * @param schedules schedule storage (the scheduling lock, schedule locks, employment locks)
   * @param shifts shift, coverage and qualifying-employment reads
   * @param attendance attendance storage
   * @param links the identity port (joins this module's transactions)
   * @param directory the tenant module's unit port (site codes and names)
   * @param calendar the platform clock (the authoritative instant)
   * @param cursors signed cursors
   * @param audit audit recorder
   * @param outbox outbox writer
   * @param metrics operation metrics
   * @param meters meter registry (latency and the self-service denial counter)
   * @param transactionManager transaction manager
   * @param json JSON mapper
   */
  public AttendanceService(
      IdempotentOperation operations,
      JdbcWorkScheduleRepository schedules,
      JdbcWorkScheduleSubstitutionRepository shifts,
      JdbcAttendanceRepository attendance,
      EmployeeAccessLinks links,
      OrganizationPlacementDirectory directory,
      BusinessCalendar calendar,
      CursorCodec cursors,
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
    this.links = links;
    this.directory = directory;
    this.calendar = calendar;
    this.cursors = cursors;
    this.audit = audit;
    this.outbox = outbox;
    this.metrics = metrics;
    this.meters = meters;
    this.transactionManager = transactionManager;
    this.json = json;
  }

  // ------------------------------------------------------------------------------------------
  // Check-in
  // ------------------------------------------------------------------------------------------

  /**
   * Records the caller's check-in on one of their shifts, or replays an earlier identical check-in
   * while the caller is still linked to the recording employee.
   *
   * @param caller verified employee
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the slot
   * @return the receipt
   */
  public IdempotentOperation.Result<Receipt> checkIn(
      PeopleCaller caller, String idempotencyKey, CheckInAttendanceRequest body) {
    return timed(
        CHECK_IN,
        () -> {
          CheckIn command =
              operations.validated(
                  CHECK_IN_SPEC, () -> AttendanceCommand.checkIn(idempotencyKey, body));
          return counted(
              CHECK_IN,
              () ->
                  operations.execute(
                      CHECK_IN_SPEC,
                      caller.subject(),
                      idempotencyKey,
                      command.canonical(caller.tenant()),
                      Receipt.class,
                      TRANSACTION_TIMEOUT,
                      stored -> replayable(caller, stored),
                      () -> checkedIn(caller, idempotencyKey, command)));
        });
  }

  private IdempotentOperation.Completed<Receipt> checkedIn(
      PeopleCaller caller, String idempotencyKey, CheckIn command) {
    TenantId tenant = caller.tenant();
    schedules.lock(tenant);
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
    // The link is checked before anything about the shift is answered.
    SelfLink link = link(caller);
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
    Instant now = now();
    if (now.isBefore(Attendance.checkInOpensAt(shift.startAt()))) {
      throw new ApiException(ErrorCode.ATTENDANCE_CHECK_IN_TOO_EARLY, Map.of());
    }
    if (!now.isBefore(shift.endAt())) {
      throw new ApiException(ErrorCode.ATTENDANCE_CHECK_IN_WINDOW_CLOSED, Map.of());
    }
    List<UUID> open = attendance.lockActionableOpen(tenant, link.employeeId(), now);
    if (attendance.forSlot(tenant, shift.slotId(), link.employeeId()).isPresent()) {
      throw new ApiException(ErrorCode.ATTENDANCE_ALREADY_CHECKED_IN, Map.of());
    }
    if (!open.isEmpty()) {
      throw new ApiException(ErrorCode.ATTENDANCE_OPEN_RECORD_EXISTS, Map.of());
    }
    List<UUID> qualifying = shifts.qualifyingEmployments(tenant, link.employeeId(), shift);
    if (qualifying.size() != 1) {
      // Zero or several: never chosen arbitrarily.
      throw stateConflict();
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
            now,
            null,
            null);
    try {
      attendance.insertCheckIn(
          tenant, record, caller.subject(), digest(CHECK_IN, caller, idempotencyKey));
      attendance.checkConsistent(tenant);
    } catch (DataIntegrityViolationException violated) {
      throw mapped(violated);
    }
    Map<String, Object> ids = ids(record);
    Map<String, Object> metadata = new LinkedHashMap<>(ids);
    metadata.put("resultingStatus", Attendance.Status.OPEN.name());
    recorded(caller, CHECK_IN, record, metadata, Attendance.EventType.CHECKED_IN, now);
    event(caller, CHECKED_IN_EVENT, record, Attendance.EventType.CHECKED_IN, now);
    LOG.atInfo()
        .addKeyValue("operation", CHECK_IN)
        .addKeyValue("outcome", "checked_in")
        .log("attendance_checked_in");
    return new IdempotentOperation.Completed<>(Receipt.of(record), record.id(), Outcome.CREATED);
  }

  // ------------------------------------------------------------------------------------------
  // Check-out
  // ------------------------------------------------------------------------------------------

  /**
   * Records the single check-out of the caller's own open record, or replays an earlier identical
   * check-out while the caller is still linked to the record's employee.
   *
   * @param caller verified employee
   * @param recordId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @return the receipt
   */
  public IdempotentOperation.Result<Receipt> checkOut(
      PeopleCaller caller, String recordId, String idempotencyKey) {
    return timed(
        CHECK_OUT,
        () -> {
          CheckOut command =
              operations.validated(
                  CHECK_OUT_SPEC, () -> AttendanceCommand.checkOut(idempotencyKey, recordId));
          return counted(
              CHECK_OUT,
              () ->
                  operations.execute(
                      CHECK_OUT_SPEC,
                      caller.subject(),
                      idempotencyKey,
                      command.canonical(caller.tenant()),
                      Receipt.class,
                      TRANSACTION_TIMEOUT,
                      stored -> replayable(caller, stored),
                      () -> checkedOut(caller, idempotencyKey, command)));
        });
  }

  private IdempotentOperation.Completed<Receipt> checkedOut(
      PeopleCaller caller, String idempotencyKey, CheckOut command) {
    TenantId tenant = caller.tenant();
    schedules.lock(tenant);
    Optional<Record> seen = attendance.record(tenant, command.recordId());
    // The record's employment only (no employee-wide range: the dates are unused).
    seen.ifPresent(
        record ->
            shifts.shareEmployments(
                tenant, List.of(record.employmentId()), null, LocalDate.EPOCH, LocalDate.EPOCH));
    SelfLink link = link(caller);
    if (seen.isEmpty() || !seen.get().employeeId().equals(link.employeeId())) {
      throw recordNotFound();
    }
    schedules.lockSchedule(tenant, seen.get().scheduleId());
    Record record = attendance.lockRecord(tenant, command.recordId()).orElseThrow();
    if (!record.acceptsCheckOut()) {
      throw new ApiException(ErrorCode.ATTENDANCE_ALREADY_CHECKED_OUT, Map.of());
    }
    Instant now = now();
    if (now.isAfter(Attendance.checkOutUntil(record.slotEndAt()))) {
      throw new ApiException(ErrorCode.ATTENDANCE_CHECK_OUT_WINDOW_CLOSED, Map.of());
    }
    if (!now.isAfter(record.checkedInAt())) {
      // Strictly after the check-in.
      throw stateConflict();
    }
    UUID eventId = UUID.randomUUID();
    try {
      int updated =
          attendance.insertCheckOut(
              tenant,
              record,
              eventId,
              now,
              caller.subject(),
              digest(CHECK_OUT, caller, idempotencyKey));
      if (updated != 1) {
        throw stateConflict();
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
            now);
    Map<String, Object> metadata = new LinkedHashMap<>(ids(completed));
    metadata.put("resultingStatus", Attendance.Status.COMPLETED.name());
    recorded(caller, CHECK_OUT, completed, metadata, Attendance.EventType.CHECKED_OUT, now);
    event(caller, CHECKED_OUT_EVENT, completed, Attendance.EventType.CHECKED_OUT, now);
    LOG.atInfo()
        .addKeyValue("operation", CHECK_OUT)
        .addKeyValue("outcome", "checked_out")
        .log("attendance_checked_out");
    return new IdempotentOperation.Completed<>(
        Receipt.of(completed), completed.id(), Outcome.UPDATED);
  }

  /**
   * A stored receipt is returned only to the employee it was recorded for, through the caller's
   * current link: no link is {@code 403 EMPLOYEE_LINK_REQUIRED}; a link to another employee is
   * {@code 409 IDEMPOTENCY_KEY_REUSED} without params, so neither the receipt nor its IDs leak.
   */
  private void replayable(PeopleCaller caller, UUID recordId) {
    SelfLink link = link(caller);
    Optional<Record> record =
        recordId == null ? Optional.empty() : attendance.record(caller.tenant(), recordId);
    if (record.isEmpty() || !record.get().employeeId().equals(link.employeeId())) {
      throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED, Map.of());
    }
  }

  // ------------------------------------------------------------------------------------------
  // History
  // ------------------------------------------------------------------------------------------

  /**
   * One page of the caller's own records, newest shift first.
   *
   * @param caller verified employee
   * @param cursor opaque cursor or {@code null}
   * @param limit page size (1 to 100, default 20)
   * @return the page
   */
  public Page history(PeopleCaller caller, String cursor, String limit) {
    FieldErrors errors = new FieldErrors();
    int size = limit(limit, errors);
    errors.throwIfAny();
    try {
      Page page = counted(SELF_LIST, () -> read(() -> listed(caller, cursor, size)));
      metrics.record(SELF_LIST, Outcome.LISTED);
      LOG.atInfo()
          .addKeyValue("operation", SELF_LIST)
          .addKeyValue("page", cursor == null ? "first" : "next")
          .addKeyValue("resultCount", page.items().size())
          .addKeyValue("outcome", "listed")
          .log("attendance_self_listed");
      return page;
    } catch (ApiException rejected) {
      Outcome outcome =
          rejected.code() == ErrorCode.EMPLOYEE_LINK_REQUIRED
              ? Outcome.DENIED
              : Outcome.VALIDATION_FAILED;
      metrics.record(SELF_LIST, outcome);
      LOG.atInfo()
          .addKeyValue("operation", SELF_LIST)
          .addKeyValue("outcome", outcome.name().toLowerCase(Locale.ROOT))
          .addKeyValue("code", rejected.code().name())
          .log("attendance_self_list_rejected");
      throw rejected;
    } catch (RuntimeException failure) {
      metrics.record(SELF_LIST, Outcome.FAILURE);
      throw failure;
    }
  }

  private Page listed(PeopleCaller caller, String cursor, int size) {
    TenantId tenant = caller.tenant();
    SelfLink link = link(caller);
    CursorScope scope =
        new CursorScope(
            SELF_LIST,
            tenant,
            Map.of(
                "subject",
                Fingerprints.sha256("DIVALHR-ATTENDANCE\nsubject=" + caller.subject()),
                "link",
                link.linkId().toString(),
                "employee",
                link.employeeId().toString(),
                "limit",
                Integer.toString(size)));
    Instant asOf;
    Instant afterAt = null;
    UUID afterId = null;
    if (cursor != null) {
      KeysetPosition position = cursors.decode(cursor, scope);
      asOf = anchorOf(position);
      afterAt =
          attendance
              .ownStart(tenant, link.employeeId(), position.id())
              .orElseThrow(WorkScheduleService::invalidCursor);
      afterId = position.id();
    } else {
      asOf = now();
    }
    List<HistoryItem> rows =
        attendance.history(tenant, link.employeeId(), afterAt, afterId, size + 1);
    List<HistoryItem> shown = rows.subList(0, Math.min(rows.size(), size));
    String next =
        rows.size() > size
            ? cursors.encode(
                scope,
                new KeysetPosition(anchorCode(asOf), shown.get(shown.size() - 1).record().id()))
            : null;
    Set<UUID> siteIds = new LinkedHashSet<>();
    shown.forEach(item -> siteIds.add(item.shift().siteId()));
    Map<UUID, UnitView> sites =
        siteIds.isEmpty() ? Map.of() : directory.resolveIds(tenant, siteIds);
    List<Item> items = new ArrayList<>(shown.size());
    for (HistoryItem item : shown) {
      items.add(Item.of(item, sites.get(item.shift().siteId()), asOf));
    }
    disclosed(caller, link.employeeId(), cursor, asOf, items);
    return new Page(items, next, asOf);
  }

  static int limit(String raw, FieldErrors errors) {
    if (raw == null) {
      return DEFAULT_LIMIT;
    }
    if (LIMIT.matcher(raw).matches()) {
      int value = Integer.parseInt(raw);
      if (value >= 1 && value <= MAX_LIMIT) {
        return value;
      }
    }
    errors.add("limit", Constraint.RANGE);
    return DEFAULT_LIMIT;
  }

  /** The keyset code of a page: the prefix and the anchor instant in epoch microseconds. */
  static String anchorCode(Instant anchor) {
    return HISTORY + ChronoUnit.MICROS.between(Instant.EPOCH, anchor);
  }

  /** The anchor pinned in a cursor; anything else is {@code CURSOR_INVALID}. */
  static Instant anchorOf(KeysetPosition position) {
    String code = position.code();
    if (!ANCHORED.matcher(code).matches()) {
      throw WorkScheduleService.invalidCursor();
    }
    return Instant.EPOCH.plus(Long.parseLong(code.substring(HISTORY.length())), ChronoUnit.MICROS);
  }

  // ------------------------------------------------------------------------------------------
  // Shared
  // ------------------------------------------------------------------------------------------

  private static Map<String, Object> ids(Record record) {
    Map<String, Object> ids = new LinkedHashMap<>();
    ids.put("schemaVersion", WorkScheduleService.SCHEMA_VERSION);
    ids.put("attendanceRecordId", record.id().toString());
    ids.put("scheduleId", record.scheduleId().toString());
    ids.put("slotId", record.slotId().toString());
    ids.put("employeeId", record.employeeId().toString());
    ids.put("employmentId", record.employmentId().toString());
    return ids;
  }

  /**
   * The write audit: safe IDs and the resulting status; the evidence (subject, instants) only as a
   * digest.
   */
  private void recorded(
      PeopleCaller caller,
      String action,
      Record record,
      Map<String, Object> metadata,
      Attendance.EventType type,
      Instant at) {
    Map<String, Object> state = new TreeMap<>(ids(record));
    state.remove("schemaVersion");
    state.put("tenantId", caller.tenant().toString());
    state.put("eventType", type.name());
    state.put("recordedAt", at.toString());
    state.put("recordedBy", caller.subject());
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            at,
            caller.subject(),
            action,
            RESOURCE,
            record.id(),
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(json.writeValueAsString(state))));
  }

  /** Stable IDs, the event type and the recorded instant only (A60A-7). */
  private void event(
      PeopleCaller caller, String type, Record record, Attendance.EventType eventType, Instant at) {
    Map<String, Object> data = new LinkedHashMap<>(ids(record));
    data.remove("schemaVersion");
    data.put("eventType", eventType.name());
    data.put("recordedAt", at.toString());
    outbox.append(
        new EventEnvelope(
            UUID.randomUUID().toString(),
            type,
            WorkScheduleService.SCHEMA_VERSION,
            caller.tenant().toString(),
            SOURCE,
            record.id().toString(),
            at.toString(),
            caller.correlationId(),
            null,
            data));
  }

  /** A fail-closed disclosure audit: the view, page and count; the IDs only in the digest. */
  private void disclosed(
      PeopleCaller caller, UUID employeeId, String cursor, Instant asOf, List<Item> items) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", WorkScheduleService.SCHEMA_VERSION);
    metadata.put("view", "my-attendance");
    metadata.put("page", cursor == null ? "first" : "next");
    metadata.put("resultCount", items.size());
    StringBuilder text =
        new StringBuilder("DIVALHR-ATTENDANCE-DISCLOSURE\nversion=1\nview=my-attendance\n")
            .append("employee=")
            .append(employeeId)
            .append("\nasOf=")
            .append(asOf)
            .append('\n');
    items.forEach(
        item ->
            text.append("id=")
                .append(item.id())
                .append(";status=")
                .append(item.status())
                .append('\n'));
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            now(),
            caller.subject(),
            SELF_LIST,
            "employee",
            employeeId,
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(text.toString())));
  }

  /** The evidence's idempotency reference: a digest, never the key itself. */
  private static String digest(String operation, PeopleCaller caller, String idempotencyKey) {
    return Fingerprints.sha256(
        "DIVALHR-ATTENDANCE-IDEMPOTENCY\noperation="
            + operation
            + "\nsubject="
            + caller.subject()
            + "\nkey="
            + idempotencyKey);
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

  private static ApiException stateConflict() {
    return new ApiException(ErrorCode.ATTENDANCE_STATE_CONFLICT, Map.of());
  }

  /** The stable conflict of a constraint the locks should have made unreachable; no DB text. */
  private static ApiException mapped(DataIntegrityViolationException violated) {
    String constraint = ScheduleConstraintViolations.constraint(violated).orElse("");
    return switch (constraint) {
      case ScheduleConstraintViolations.ATTENDANCE_SLOT_EMPLOYEE ->
          new ApiException(ErrorCode.ATTENDANCE_ALREADY_CHECKED_IN, Map.of());
      case ScheduleConstraintViolations.ATTENDANCE_ONE_OPEN ->
          new ApiException(ErrorCode.ATTENDANCE_OPEN_RECORD_EXISTS, Map.of());
      case ScheduleConstraintViolations.ATTENDANCE_EVENT_TYPE ->
          new ApiException(ErrorCode.ATTENDANCE_ALREADY_CHECKED_OUT, Map.of());
      default -> throw violated;
    };
  }

  /** A bounded read transaction; the result is returned only after it committed. */
  private <T> T read(Supplier<T> work) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setTimeout((int) TRANSACTION_TIMEOUT.toSeconds());
    T result = template.execute(status -> work.get());
    if (result == null) {
      throw new IllegalStateException("read transaction returned nothing");
    }
    return result;
  }

  /** Counts link denials and undisclosable targets on the bounded self-service counter only. */
  private <T> T counted(String operation, Supplier<T> work) {
    try {
      return work.get();
    } catch (ApiException denied) {
      if (denied.code() == ErrorCode.EMPLOYEE_LINK_REQUIRED) {
        ScopeAuthorizationInterceptor.selfServiceDenied(meters, operation, "link_required");
      } else if (denied.code() == ErrorCode.ATTENDANCE_SHIFT_NOT_FOUND
          || denied.code() == ErrorCode.ATTENDANCE_RECORD_NOT_FOUND) {
        ScopeAuthorizationInterceptor.selfServiceDenied(meters, operation, "not_found");
      }
      throw denied;
    }
  }

  /** The command's latency, tagged by operation and a bounded outcome only. */
  private IdempotentOperation.Result<Receipt> timed(
      String operation, Supplier<IdempotentOperation.Result<Receipt>> work) {
    Timer.Sample sample = Timer.start(meters);
    String outcome = "failure";
    try {
      IdempotentOperation.Result<Receipt> result = work.get();
      outcome = result.replayed() ? "replayed" : "success";
      return result;
    } catch (ApiException refused) {
      outcome = "refused";
      throw refused;
    } finally {
      sample.stop(
          Timer.builder(LATENCY_METRIC)
              .tag("operation", operation)
              .tag("outcome", outcome)
              .register(meters));
    }
  }
}
