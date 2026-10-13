package com.divalhr.core.people.schedule.application;

import com.divalhr.core.people.application.BusinessCalendar;
import com.divalhr.core.people.application.ManagerGraphLock;
import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.schedule.api.SupervisorCheckInRequest;
import com.divalhr.core.people.schedule.api.SupervisorCheckOutRequest;
import com.divalhr.core.people.schedule.api.TeamAttendanceResponses.Action;
import com.divalhr.core.people.schedule.api.TeamAttendanceResponses.Page;
import com.divalhr.core.people.schedule.api.TeamAttendanceResponses.Receipt;
import com.divalhr.core.people.schedule.application.TeamAttendanceCommand.CheckIn;
import com.divalhr.core.people.schedule.application.TeamAttendanceCommand.CheckOut;
import com.divalhr.core.people.schedule.domain.Attendance;
import com.divalhr.core.people.schedule.domain.Attendance.Record;
import com.divalhr.core.people.schedule.domain.Attendance.TeamAction;
import com.divalhr.core.people.schedule.domain.Substitution.Shift;
import com.divalhr.core.people.schedule.internal.JdbcAttendanceRepository;
import com.divalhr.core.people.schedule.internal.JdbcAttendanceRepository.SlotInstants;
import com.divalhr.core.people.schedule.internal.JdbcAttendanceRepository.Supervision;
import com.divalhr.core.people.schedule.internal.JdbcAttendanceRepository.TeamPosition;
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
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Supervisor-assisted attendance for direct reports (MVP-060B, B60B-1 to B60B-7; ADR 0017). An
 * employee's effective manager lists the actions their direct reports' scheduled shifts need now
 * and records a check-in or check-out on a report's behalf, with a reason, inside the unchanged
 * MVP-060A windows. Authorization (role {@code employee}, verified tenant, active membership) has
 * run before this; the caller's own employee is resolved through the identity port's current active
 * link ({@code FOR SHARE}). There is no manager role: the target is the slot's current operational
 * coverage (for a check-out, the record's employee), and the caller is its effective manager when
 * the target's one qualifying employment (the record's employment) has an active, non-superseded
 * MANAGER line naming the caller's linked employee on the shift's site-local start date, evaluated
 * on every list, command and replay. Tenant administrators, MVP-041G leave substitutes and MVP-051
 * substitution approvals have no authority here; a manager never acts for themselves.
 *
 * <p>Lock order (B60B-6): idempotency (0), the tenant's manager-graph lock (1), the tenant's
 * scheduling lock (2), the target's and the manager's employments {@code FOR SHARE} in UUID order
 * (3), the caller's link and membership {@code FOR SHARE} (4), the schedule {@code FOR UPDATE},
 * then the slot and coverage rows (5), the target's actionable open records and the record {@code
 * FOR UPDATE} (6); then the re-evaluation of the authority, coverage, state and window (7) and the
 * event, supervisor evidence, projection, deferred checks, audit record and outbox event, committed
 * with the idempotency completion (8). Employment changes and separations take (1) and then their
 * employments {@code FOR UPDATE}; cancellation, replacement and substitution decisions take (1) or
 * (2) and the schedule; self-service attendance takes (2) and the schedule: no inverse order
 * exists.
 *
 * <p>Reasons, names, numbers, labels and subjects are Restricted HR: never in logs, metrics, audit
 * metadata, events or errors. The reason and the subject are stored only as evidence.
 */
@Service
public class TeamAttendanceService {

  /** The manager's queue (interceptor operation and disclosure audit action). */
  public static final String TEAM_LIST = "attendance.team-actions-list";

  /** A supervisor's check-in (idempotency operation, interceptor operation and audit action). */
  public static final String CHECK_IN = "attendance.supervisor-check-in";

  /** A supervisor's check-out (idempotency operation, interceptor operation and audit action). */
  public static final String CHECK_OUT = "attendance.supervisor-check-out";

  /** Outbox event of a supervisor's check-in. */
  public static final String CHECKED_IN_EVENT = "people.attendance.supervisor-checked-in.v1";

  /** Outbox event of a supervisor's check-out. */
  public static final String CHECKED_OUT_EVENT = "people.attendance.supervisor-checked-out.v1";

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.schedule");
  private static final String SOURCE = "core-api/people";
  private static final String RESOURCE = "attendance-record";
  private static final Duration TRANSACTION_TIMEOUT = Duration.ofSeconds(15);
  private static final IdempotentOperation.Spec CHECK_IN_SPEC =
      new IdempotentOperation.Spec(CHECK_IN, "attendance", "check_in", "checked_in", 201);
  private static final IdempotentOperation.Spec CHECK_OUT_SPEC =
      new IdempotentOperation.Spec(CHECK_OUT, "attendance", "check_out", "checked_out", 200);
  private static final Pattern ANCHORED = Pattern.compile("^TA([0-9]{1,18})([IO])$");

  /** A manager is someone's manager when a line names them from this many days before today. */
  private static final int MANAGES_FROM_DAYS_BEFORE = 3;

  /** ... to this many days after the organization's business date. */
  private static final int MANAGES_UNTIL_DAYS_AFTER = 1;

  private final IdempotentOperation operations;
  private final ManagerGraphLock graph;
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
   * @param graph the tenant's manager-graph lock
   * @param schedules schedule storage (the scheduling lock and schedule locks)
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
  public TeamAttendanceService(
      IdempotentOperation operations,
      ManagerGraphLock graph,
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
    this.graph = graph;
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
   * Records a direct report's check-in on the slot's behalf, or replays an earlier identical one
   * while the caller is still the target's effective manager.
   *
   * @param caller verified employee (the manager)
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the slot and the reason
   * @return the receipt
   */
  public IdempotentOperation.Result<Receipt> checkIn(
      PeopleCaller caller, String idempotencyKey, SupervisorCheckInRequest body) {
    return timed(
        CHECK_IN,
        () -> {
          CheckIn command =
              operations.validated(
                  CHECK_IN_SPEC, () -> TeamAttendanceCommand.checkIn(idempotencyKey, body));
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
    graph.lock(tenant);
    schedules.lock(tenant);
    // Under both locks the slot's coverage and every reporting line are fixed for this
    // transaction: substitution decisions, cancellations and replacements take the scheduling
    // lock, manager changes and separations the manager-graph lock.
    Optional<Shift> seen = shifts.shift(tenant, command.slotId());
    Optional<Coverage> covered = seen.flatMap(shift -> shifts.coverage(tenant, shift.slotId()));
    List<UUID> qualifying =
        covered.isPresent()
            ? shifts.qualifyingEmployments(tenant, covered.get().employeeId(), seen.get())
            : List.of();
    Set<UUID> managers = new TreeSet<>();
    for (UUID employment : qualifying) {
      attendance.managerOf(tenant, employment, seen.get().firstDate()).ifPresent(managers::add);
    }
    covered.ifPresent(
        coverage -> {
          Set<UUID> people = new TreeSet<>(managers);
          people.add(coverage.employeeId());
          attendance.shareEmployments(
              tenant, List.of(), people, seen.get().firstDate(), seen.get().lastDate());
        });
    // The link is checked before anything about the slot is answered.
    SelfLink link = link(caller);
    UUID manager = link.employeeId();
    if (seen.isEmpty()
        || covered.isEmpty()
        || covered.get().employeeId().equals(manager)
        || !managers.contains(manager)) {
      throw TeamAttendanceCommand.notFound();
    }
    UUID target = covered.get().employeeId();
    schedules
        .lockSchedule(tenant, seen.get().scheduleId())
        .orElseThrow(TeamAttendanceCommand::notFound);
    // Re-read under the schedule lock (nothing can have moved it, B60B-1: the target is still the
    // slot's effective assignee when the check-in is committed).
    Shift shift =
        shifts.shift(tenant, command.slotId()).orElseThrow(TeamAttendanceCommand::notFound);
    Optional<Coverage> coverage = shifts.coverage(tenant, shift.slotId());
    if (!shift.operational() || coverage.isEmpty() || !coverage.get().employeeId().equals(target)) {
      throw TeamAttendanceCommand.notFound();
    }
    Instant now = now();
    if (now.isBefore(Attendance.checkInOpensAt(shift.startAt()))) {
      throw new ApiException(ErrorCode.ATTENDANCE_CHECK_IN_TOO_EARLY, Map.of());
    }
    if (!now.isBefore(shift.endAt())) {
      throw new ApiException(ErrorCode.ATTENDANCE_CHECK_IN_WINDOW_CLOSED, Map.of());
    }
    List<UUID> open = attendance.lockActionableOpen(tenant, target, now);
    if (attendance.forSlot(tenant, shift.slotId(), target).isPresent()) {
      throw new ApiException(ErrorCode.ATTENDANCE_ALREADY_CHECKED_IN, Map.of());
    }
    if (!open.isEmpty()) {
      throw new ApiException(ErrorCode.ATTENDANCE_OPEN_RECORD_EXISTS, Map.of());
    }
    if (qualifying.size() != 1) {
      // Zero or several qualifying employments: never chosen arbitrarily.
      throw stateConflict();
    }
    UUID managerEmployment =
        attendance
            .employmentOn(tenant, manager, shift.firstDate())
            .orElseThrow(TeamAttendanceService::stateConflict);
    Record record =
        new Record(
            UUID.randomUUID(),
            shift.scheduleId(),
            shift.slotId(),
            target,
            qualifying.get(0),
            shift.startAt(),
            shift.endAt(),
            UUID.randomUUID(),
            now,
            null,
            null);
    Supervision supervision =
        new Supervision(
            UUID.randomUUID(),
            manager,
            managerEmployment,
            command.reasonLocale(),
            command.reason());
    try {
      attendance.insertCheckIn(
          tenant, record, caller.subject(), digest(CHECK_IN, caller, idempotencyKey), supervision);
      attendance.checkConsistent(tenant);
    } catch (DataIntegrityViolationException violated) {
      throw mapped(violated);
    }
    written(
        caller,
        CHECK_IN,
        CHECKED_IN_EVENT,
        record,
        supervision,
        Attendance.EventType.CHECKED_IN,
        now);
    LOG.atInfo()
        .addKeyValue("operation", CHECK_IN)
        .addKeyValue("outcome", "checked_in")
        .log("attendance_supervisor_checked_in");
    return new IdempotentOperation.Completed<>(Receipt.of(record), record.id(), Outcome.CREATED);
  }

  // ------------------------------------------------------------------------------------------
  // Check-out
  // ------------------------------------------------------------------------------------------

  /**
   * Records the single check-out of a direct report's open record on their behalf, or replays an
   * earlier identical one while the caller is still the target's effective manager.
   *
   * @param caller verified employee (the manager)
   * @param recordId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the reason
   * @return the receipt
   */
  public IdempotentOperation.Result<Receipt> checkOut(
      PeopleCaller caller, String recordId, String idempotencyKey, SupervisorCheckOutRequest body) {
    return timed(
        CHECK_OUT,
        () -> {
          CheckOut command =
              operations.validated(
                  CHECK_OUT_SPEC,
                  () -> TeamAttendanceCommand.checkOut(idempotencyKey, recordId, body));
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
    graph.lock(tenant);
    schedules.lock(tenant);
    Optional<Record> seen = attendance.record(tenant, command.recordId());
    Optional<Shift> seenShift = seen.flatMap(record -> shifts.shift(tenant, record.slotId()));
    Optional<UUID> effective =
        seen.isPresent() && seenShift.isPresent()
            ? attendance.managerOf(tenant, seen.get().employmentId(), seenShift.get().firstDate())
            : Optional.empty();
    if (seen.isPresent() && seenShift.isPresent()) {
      // The record's employment, and the manager's employments on the shift's start date.
      attendance.shareEmployments(
          tenant,
          List.of(seen.get().employmentId()),
          effective.map(List::of).orElse(List.of()),
          seenShift.get().firstDate(),
          seenShift.get().firstDate());
    }
    SelfLink link = link(caller);
    UUID manager = link.employeeId();
    if (seen.isEmpty()
        || seenShift.isEmpty()
        || effective.isEmpty()
        || !effective.get().equals(manager)
        || seen.get().employeeId().equals(manager)) {
      throw TeamAttendanceCommand.notFound();
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
      throw stateConflict();
    }
    UUID managerEmployment =
        attendance
            .employmentOn(tenant, manager, seenShift.get().firstDate())
            .orElseThrow(TeamAttendanceService::stateConflict);
    Supervision supervision =
        new Supervision(
            UUID.randomUUID(),
            manager,
            managerEmployment,
            command.reasonLocale(),
            command.reason());
    UUID eventId = UUID.randomUUID();
    try {
      int updated =
          attendance.insertCheckOut(
              tenant,
              record,
              eventId,
              now,
              caller.subject(),
              digest(CHECK_OUT, caller, idempotencyKey),
              supervision);
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
    written(
        caller,
        CHECK_OUT,
        CHECKED_OUT_EVENT,
        completed,
        supervision,
        Attendance.EventType.CHECKED_OUT,
        now);
    LOG.atInfo()
        .addKeyValue("operation", CHECK_OUT)
        .addKeyValue("outcome", "checked_out")
        .log("attendance_supervisor_checked_out");
    return new IdempotentOperation.Completed<>(
        Receipt.of(completed), completed.id(), Outcome.UPDATED);
  }

  /**
   * A stored receipt replays only while the caller's currently linked employee is still the
   * record's effective manager (re-taking the manager-graph lock); otherwise the same {@code 404}
   * as an unknown action, with no stored data (B60B-6).
   */
  private void replayable(PeopleCaller caller, UUID recordId) {
    TenantId tenant = caller.tenant();
    graph.lock(tenant);
    Optional<Record> record =
        recordId == null ? Optional.empty() : attendance.record(tenant, recordId);
    Optional<Shift> shift = record.flatMap(found -> shifts.shift(tenant, found.slotId()));
    SelfLink link = link(caller);
    if (record.isEmpty()
        || shift.isEmpty()
        || record.get().employeeId().equals(link.employeeId())
        || !attendance
            .managerOf(tenant, record.get().employmentId(), shift.get().firstDate())
            .map(link.employeeId()::equals)
            .orElse(false)) {
      throw TeamAttendanceCommand.notFound();
    }
  }

  // ------------------------------------------------------------------------------------------
  // The manager's queue
  // ------------------------------------------------------------------------------------------

  /**
   * One page of the actions the caller's direct reports need now, by deadline.
   *
   * @param caller verified employee (the manager)
   * @param cursor opaque cursor or {@code null}
   * @param limit page size (1 to 100, default 20)
   * @return the page, after its disclosure audit
   */
  public Page actions(PeopleCaller caller, String cursor, String limit) {
    FieldErrors errors = new FieldErrors();
    int size = AttendanceService.limit(limit, errors);
    errors.throwIfAny();
    try {
      Page page = counted(TEAM_LIST, () -> read(() -> listed(caller, cursor, size)));
      metrics.record(TEAM_LIST, Outcome.LISTED);
      LOG.atInfo()
          .addKeyValue("operation", TEAM_LIST)
          .addKeyValue("page", cursor == null ? "first" : "next")
          .addKeyValue("resultCount", page.items().size())
          .addKeyValue("outcome", "listed")
          .log("attendance_team_actions_listed");
      return page;
    } catch (ApiException rejected) {
      Outcome outcome =
          rejected.code() == ErrorCode.EMPLOYEE_LINK_REQUIRED
                  || rejected.code() == ErrorCode.TEAM_ATTENDANCE_MANAGER_REQUIRED
              ? Outcome.DENIED
              : Outcome.VALIDATION_FAILED;
      metrics.record(TEAM_LIST, outcome);
      LOG.atInfo()
          .addKeyValue("operation", TEAM_LIST)
          .addKeyValue("outcome", outcome.name().toLowerCase(Locale.ROOT))
          .addKeyValue("code", rejected.code().name())
          .log("attendance_team_actions_rejected");
      throw rejected;
    } catch (RuntimeException failure) {
      metrics.record(TEAM_LIST, Outcome.FAILURE);
      throw failure;
    }
  }

  private Page listed(PeopleCaller caller, String cursor, int size) {
    TenantId tenant = caller.tenant();
    SelfLink link = link(caller);
    UUID manager = link.employeeId();
    LocalDate today = calendar.today(tenant);
    if (!attendance.managesBetween(
        tenant,
        manager,
        today.minusDays(MANAGES_FROM_DAYS_BEFORE),
        today.plusDays(MANAGES_UNTIL_DAYS_AFTER))) {
      throw new ApiException(ErrorCode.TEAM_ATTENDANCE_MANAGER_REQUIRED, Map.of());
    }
    CursorScope scope =
        new CursorScope(
            TEAM_LIST,
            tenant,
            Map.of(
                "subject",
                Fingerprints.sha256("DIVALHR-TEAM-ATTENDANCE\nsubject=" + caller.subject()),
                "link",
                link.linkId().toString(),
                "manager",
                manager.toString(),
                "limit",
                Integer.toString(size)));
    Instant asOf;
    TeamPosition after = null;
    if (cursor != null) {
      KeysetPosition position = cursors.decode(cursor, scope);
      Matcher anchored = ANCHORED.matcher(position.code());
      if (!anchored.matches()) {
        throw WorkScheduleService.invalidCursor();
      }
      asOf = Instant.EPOCH.plus(Long.parseLong(anchored.group(1)), ChronoUnit.MICROS);
      Attendance.RequiredAction action =
          "I".equals(anchored.group(2))
              ? Attendance.RequiredAction.CHECK_IN
              : Attendance.RequiredAction.CHECK_OUT;
      SlotInstants slot =
          attendance
              .slotInstants(tenant, position.id())
              .orElseThrow(WorkScheduleService::invalidCursor);
      Instant deadline =
          action == Attendance.RequiredAction.CHECK_IN
              ? slot.endAt()
              : Attendance.checkOutUntil(slot.endAt());
      after = new TeamPosition(action, deadline, slot.startAt(), position.id());
    } else {
      asOf = now();
    }
    List<TeamAction> rows = attendance.teamActions(tenant, manager, asOf, after, size + 1);
    List<TeamAction> shown = rows.subList(0, Math.min(rows.size(), size));
    String next = null;
    if (rows.size() > size) {
      TeamAction last = shown.get(shown.size() - 1);
      next =
          cursors.encode(
              scope,
              new KeysetPosition(
                  "TA"
                      + ChronoUnit.MICROS.between(Instant.EPOCH, asOf)
                      + (last.action() == Attendance.RequiredAction.CHECK_IN ? "I" : "O"),
                  last.shift().slotId()));
    }
    Set<UUID> siteIds = new LinkedHashSet<>();
    shown.forEach(row -> siteIds.add(row.shift().siteId()));
    Map<UUID, UnitView> sites =
        siteIds.isEmpty() ? Map.of() : directory.resolveIds(tenant, siteIds);
    List<Action> items = new ArrayList<>(shown.size());
    for (TeamAction row : shown) {
      items.add(Action.of(row, sites.get(row.shift().siteId())));
    }
    disclosed(caller, manager, cursor, asOf, items);
    return new Page(items, next, asOf);
  }

  // ------------------------------------------------------------------------------------------
  // Shared
  // ------------------------------------------------------------------------------------------

  private static Map<String, Object> ids(Record record, Supervision supervision) {
    Map<String, Object> ids = new LinkedHashMap<>();
    ids.put("schemaVersion", WorkScheduleService.SCHEMA_VERSION);
    ids.put("attendanceRecordId", record.id().toString());
    ids.put("scheduleId", record.scheduleId().toString());
    ids.put("slotId", record.slotId().toString());
    ids.put("employeeId", record.employeeId().toString());
    ids.put("employmentId", record.employmentId().toString());
    ids.put("managerEmployeeId", supervision.managerEmployeeId().toString());
    return ids;
  }

  /**
   * The write audit (safe IDs, the event type, the method and the resulting status; the subject,
   * the manager's employment and the reason only in the state digest) and the outbox event (stable
   * IDs, the event type, the method and the instant; B60B-7).
   */
  private void written(
      PeopleCaller caller,
      String action,
      String eventType,
      Record record,
      Supervision supervision,
      Attendance.EventType type,
      Instant at) {
    Map<String, Object> ids = ids(record, supervision);
    Map<String, Object> metadata = new LinkedHashMap<>(ids);
    metadata.put("eventType", type.name());
    metadata.put("recordingMethod", Attendance.Method.SUPERVISOR.name());
    metadata.put("resultingStatus", record.status().name());
    Map<String, Object> state = new TreeMap<>(ids);
    state.remove("schemaVersion");
    state.put("tenantId", caller.tenant().toString());
    state.put("eventType", type.name());
    state.put("recordingMethod", Attendance.Method.SUPERVISOR.name());
    state.put("managerEmploymentId", supervision.managerEmploymentId().toString());
    state.put("reasonLocale", supervision.reasonLocale());
    state.put("reason", supervision.reason());
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
    Map<String, Object> data = new LinkedHashMap<>(ids);
    data.remove("schemaVersion");
    data.put("eventType", type.name());
    data.put("recordingMethod", Attendance.Method.SUPERVISOR.name());
    data.put("recordedAt", at.toString());
    outbox.append(
        new EventEnvelope(
            UUID.randomUUID().toString(),
            eventType,
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
      PeopleCaller caller, UUID managerId, String cursor, Instant asOf, List<Action> items) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", WorkScheduleService.SCHEMA_VERSION);
    metadata.put("view", "team-attendance-actions");
    metadata.put("page", cursor == null ? "first" : "next");
    metadata.put("resultCount", items.size());
    StringBuilder text =
        new StringBuilder(
                "DIVALHR-TEAM-ATTENDANCE-DISCLOSURE\nversion=1\nview=team-attendance-actions\n")
            .append("manager=")
            .append(managerId)
            .append("\nasOf=")
            .append(asOf)
            .append('\n');
    items.forEach(
        item ->
            text.append("slot=")
                .append(item.slotId())
                .append(";employee=")
                .append(item.employee().id())
                .append(";action=")
                .append(item.requiredAction())
                .append('\n'));
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            now(),
            caller.subject(),
            TEAM_LIST,
            "employee",
            managerId,
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
      } else if (denied.code() == ErrorCode.TEAM_ATTENDANCE_ACTION_NOT_FOUND) {
        ScopeAuthorizationInterceptor.selfServiceDenied(meters, operation, "not_found");
      } else if (denied.code() == ErrorCode.TEAM_ATTENDANCE_MANAGER_REQUIRED) {
        ScopeAuthorizationInterceptor.selfServiceDenied(meters, operation, "manager_required");
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
          Timer.builder(AttendanceService.LATENCY_METRIC)
              .tag("operation", operation)
              .tag("outcome", outcome)
              .register(meters));
    }
  }
}
