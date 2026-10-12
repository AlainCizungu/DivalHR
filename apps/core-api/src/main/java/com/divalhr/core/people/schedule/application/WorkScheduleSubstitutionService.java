package com.divalhr.core.people.schedule.application;

import com.divalhr.core.people.application.BusinessCalendar;
import com.divalhr.core.people.application.ManagerGraphLock;
import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.domain.EmployeeSearchKey;
import com.divalhr.core.people.schedule.api.CreateWorkScheduleSubstitutionRequest;
import com.divalhr.core.people.schedule.api.DecideWorkScheduleSubstitutionRequest;
import com.divalhr.core.people.schedule.api.WorkScheduleSubstitutionResponses.Approval;
import com.divalhr.core.people.schedule.api.WorkScheduleSubstitutionResponses.ApprovalEmployee;
import com.divalhr.core.people.schedule.api.WorkScheduleSubstitutionResponses.ApprovalPage;
import com.divalhr.core.people.schedule.api.WorkScheduleSubstitutionResponses.CandidatePage;
import com.divalhr.core.people.schedule.api.WorkScheduleSubstitutionResponses.DecisionReceipt;
import com.divalhr.core.people.schedule.api.WorkScheduleSubstitutionResponses.MyDecision;
import com.divalhr.core.people.schedule.api.WorkScheduleSubstitutionResponses.MyPage;
import com.divalhr.core.people.schedule.api.WorkScheduleSubstitutionResponses.MyRequest;
import com.divalhr.core.people.schedule.api.WorkScheduleSubstitutionResponses.Person;
import com.divalhr.core.people.schedule.api.WorkScheduleSubstitutionResponses.ShiftView;
import com.divalhr.core.people.schedule.application.WorkScheduleSubstitutionCommand.Create;
import com.divalhr.core.people.schedule.application.WorkScheduleSubstitutionCommand.Decide;
import com.divalhr.core.people.schedule.domain.Substitution;
import com.divalhr.core.people.schedule.domain.Substitution.Decision;
import com.divalhr.core.people.schedule.domain.Substitution.HistoryItem;
import com.divalhr.core.people.schedule.domain.Substitution.InboxItem;
import com.divalhr.core.people.schedule.domain.Substitution.Request;
import com.divalhr.core.people.schedule.domain.Substitution.Shift;
import com.divalhr.core.people.schedule.domain.Substitution.State;
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
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
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
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shift substitution (MVP-051, D51-1 to D51-8; ADR 0015). An employee requests a substitute for one
 * of their own future published shifts, still covered by them, giving a reason; their effective
 * manager approves by choosing an eligible direct report, or rejects, with a reason. Approval moves
 * the slot's operational coverage to the substitute; the published schedule and slot never change.
 * Authorization (role {@code employee}, verified tenant, active membership) has run before this;
 * the caller's own employee is resolved through the identity port's current active link ({@code FOR
 * SHARE}). There is no manager role: a caller is a request's manager when the request's stored
 * employment has an active, non-superseded MANAGER assignment naming the caller's linked employee
 * on the shift's site-local start date, evaluated on every read, decision and replay. Tenant
 * administrators and MVP-041G leave substitutes have no authority here.
 *
 * <p>Lock order (D51-7): idempotency (0), the tenant's manager-graph lock (1), the tenant's
 * scheduling lock (2), the original's and, for an approval, the substitute's employments {@code FOR
 * SHARE} in UUID order (3), the caller's link and membership {@code FOR SHARE} (4), the schedule
 * and then the request {@code FOR UPDATE} (5); then the re-evaluation, the evidence, projections
 * and coverage, the deferred checks, the audit record and the outbox event, committed with the
 * idempotency completion. Schedule cancellation and replacement take only (2) and the schedule (5),
 * employment changes (1) and their employment {@code FOR UPDATE}: no inverse order exists.
 *
 * <p>Reasons, names, numbers, labels and the candidate query are Restricted HR: never in logs,
 * metrics, audit metadata, events, URLs (except the candidate query parameter, which the edge
 * proxies drop from their logs) or errors.
 */
@Service
public class WorkScheduleSubstitutionService {

  /** The employee's history (interceptor operation and disclosure audit action). */
  public static final String SELF_LIST = "work-schedule-substitution.self-list";

  /** The manager's inbox (interceptor operation and disclosure audit action). */
  public static final String MANAGER_LIST = "work-schedule-substitution.manager-list";

  /** The candidate search (interceptor operation and disclosure audit action). */
  public static final String CANDIDATE_LIST = "work-schedule-substitution.candidate-list";

  /** A request (idempotency operation, interceptor operation and audit action). */
  public static final String REQUEST = "work-schedule-substitution.request";

  /** A decision (idempotency and interceptor operation). */
  public static final String DECIDE = "work-schedule-substitution.decide";

  /** Audit action of an approval. */
  public static final String APPROVE = "work-schedule-substitution.approve";

  /** Audit action of a rejection. */
  public static final String REJECT = "work-schedule-substitution.reject";

  /** Outbox event of a request. */
  public static final String REQUESTED_EVENT = "people.work-schedule-substitution.requested.v1";

  /** Outbox event of an approval. */
  public static final String APPROVED_EVENT = "people.work-schedule-substitution.approved.v1";

  /** Outbox event of a rejection. */
  public static final String REJECTED_EVENT = "people.work-schedule-substitution.rejected.v1";

  /** Per-subject write bucket (requests and decisions). */
  public static final String SUBJECT_WRITE_BUCKET = "work-schedule-substitution-write";

  /** Per-tenant write bucket (requests and decisions). */
  public static final String TENANT_WRITE_BUCKET = "work-schedule-substitution-write";

  /** Candidate page size: default and maximum (D51-5). */
  static final int CANDIDATE_LIMIT = 20;

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.schedule");
  private static final String SOURCE = "core-api/people";
  private static final String RESOURCE = "work-schedule-substitution";
  private static final Duration TRANSACTION_TIMEOUT = Duration.ofSeconds(15);
  private static final IdempotentOperation.Spec REQUEST_SPEC =
      new IdempotentOperation.Spec(
          REQUEST, "work_schedule_substitution", "request", "requested", 201);
  private static final IdempotentOperation.Spec DECIDE_SPEC =
      new IdempotentOperation.Spec(DECIDE, "work_schedule_substitution", "decide", "decided", 200);
  private static final String HISTORY = "SH";
  private static final String INBOX = "SA";
  private static final String CANDIDATES = "SC";
  private static final Pattern ANCHORED = Pattern.compile("^S[HA][0-9]{1,18}$");
  private static final Pattern EMPLOYEE_NUMBER = Pattern.compile("^[A-Z0-9][A-Z0-9._/-]{0,31}$");
  private static final Pattern LIMIT = Pattern.compile("^[0-9]{1,3}$");

  private final IdempotentOperation operations;
  private final JdbcWorkScheduleRepository schedules;
  private final JdbcWorkScheduleSubstitutionRepository substitutions;
  private final ManagerGraphLock graph;
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
   * @param schedules schedule storage (the scheduling lock, schedule locks, deferred checks)
   * @param substitutions substitution storage
   * @param graph the tenant's manager-graph lock
   * @param links the identity port (joins this module's transactions)
   * @param directory the tenant module's unit port (site codes and names)
   * @param calendar the platform clock
   * @param cursors signed cursors
   * @param audit audit recorder
   * @param outbox outbox writer
   * @param metrics operation metrics
   * @param meters meter registry (self-service denial counter)
   * @param transactionManager transaction manager
   * @param json JSON mapper
   */
  public WorkScheduleSubstitutionService(
      IdempotentOperation operations,
      JdbcWorkScheduleRepository schedules,
      JdbcWorkScheduleSubstitutionRepository substitutions,
      ManagerGraphLock graph,
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
    this.substitutions = substitutions;
    this.graph = graph;
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
  // Request
  // ------------------------------------------------------------------------------------------

  /**
   * Records a pending request for the caller's own shift, or replays an earlier identical request
   * while the caller is still linked to the requesting employee.
   *
   * @param caller verified employee
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the request
   * @return the created or replayed request
   */
  public IdempotentOperation.Result<MyRequest> request(
      PeopleCaller caller, String idempotencyKey, CreateWorkScheduleSubstitutionRequest body) {
    Create command =
        operations.validated(
            REQUEST_SPEC, () -> WorkScheduleSubstitutionCommand.create(idempotencyKey, body));
    return counted(
        REQUEST,
        () ->
            operations.execute(
                REQUEST_SPEC,
                caller.subject(),
                idempotencyKey,
                command.canonical(caller.tenant()),
                MyRequest.class,
                TRANSACTION_TIMEOUT,
                stored -> requestReplayable(caller, stored),
                () -> requested(caller, command)));
  }

  /**
   * A stored request is returned only to the employee it was created for, through the caller's
   * current link: no link is {@code 403 EMPLOYEE_LINK_REQUIRED}; a link to another employee is
   * {@code 409 IDEMPOTENCY_KEY_REUSED} without params, so neither the response nor its IDs leak.
   */
  private void requestReplayable(PeopleCaller caller, UUID requestId) {
    SelfLink link = link(caller);
    Optional<Request> request =
        requestId == null ? Optional.empty() : substitutions.request(caller.tenant(), requestId);
    if (request.isEmpty() || !request.get().employeeId().equals(link.employeeId())) {
      throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED, Map.of());
    }
  }

  private IdempotentOperation.Completed<MyRequest> requested(PeopleCaller caller, Create command) {
    TenantId tenant = caller.tenant();
    graph.lock(tenant);
    schedules.lock(tenant);
    Optional<Shift> seen = substitutions.shift(tenant, command.slotId());
    seen.filter(shift -> shift.assignedEmployeeId() != null)
        .ifPresent(
            shift ->
                schedules.shareEmployments(
                    tenant,
                    Set.of(shift.assignedEmployeeId()),
                    shift.firstDate(),
                    shift.lastDate()));
    // The link is checked before anything about the shift is answered.
    SelfLink link = link(caller);
    if (seen.isEmpty() || !link.employeeId().equals(seen.get().assignedEmployeeId())) {
      throw shiftNotFound();
    }
    schedules.lockSchedule(tenant, seen.get().scheduleId()).orElseThrow(this::shiftNotFound);
    Shift shift = substitutions.shift(tenant, command.slotId()).orElseThrow(this::shiftNotFound);
    Instant now = now();
    if (!shift.operational()) {
      throw notAvailable("NOT_OPERATIONAL");
    }
    if (!shift.startAt().isAfter(now)) {
      throw windowClosed();
    }
    Optional<Coverage> coverage = substitutions.coverage(tenant, shift.slotId());
    if (substitutions.substituted(tenant, shift.slotId())
        || coverage.isEmpty()
        || coverage.get().decisionId() != null
        || !coverage.get().employeeId().equals(link.employeeId())) {
      throw notAvailable("SUBSTITUTED");
    }
    if (substitutions.slotPending(tenant, shift.slotId())) {
      throw new ApiException(ErrorCode.WORK_SCHEDULE_SUBSTITUTION_ALREADY_PENDING, Map.of());
    }
    List<UUID> qualifying = substitutions.qualifyingEmployments(tenant, link.employeeId(), shift);
    if (qualifying.size() != 1) {
      // Zero or several: never chosen arbitrarily.
      throw notAvailable("EMPLOYMENT");
    }
    UUID employment = qualifying.get(0);
    if (!substitutions.managed(tenant, employment, shift.firstDate())) {
      throw notAvailable("NO_MANAGER");
    }
    Request request =
        new Request(
            UUID.randomUUID(),
            shift.scheduleId(),
            shift.slotId(),
            link.employeeId(),
            employment,
            command.reasonLocale(),
            command.reason(),
            now);
    try {
      substitutions.insertRequest(tenant, request, caller.subject());
      substitutions.checkConsistent(tenant);
    } catch (DataIntegrityViolationException violated) {
      throw mapped(violated);
    }
    Map<String, Object> ids = ids(request);
    Map<String, Object> metadata = new LinkedHashMap<>(ids);
    metadata.put("resultingState", State.PENDING.name());
    Map<String, Object> state = new TreeMap<>(ids);
    state.remove("schemaVersion");
    state.put("tenantId", tenant.toString());
    state.put("state", State.PENDING.name());
    state.put("reasonLocale", request.reasonLocale());
    state.put("reason", request.reason());
    state.put("requestedAt", now.toString());
    state.put("requestedBy", caller.subject());
    recorded(caller, REQUEST, request.id(), metadata, state, now);
    Map<String, Object> data = new LinkedHashMap<>(ids);
    data.remove("schemaVersion");
    data.put("state", State.PENDING.name());
    event(caller, REQUESTED_EVENT, request.id(), data, now);
    LOG.atInfo()
        .addKeyValue("operation", REQUEST)
        .addKeyValue("outcome", "requested")
        .log("work_schedule_substitution_requested");
    Map<UUID, UnitView> sites = sites(tenant, List.of(shift));
    return new IdempotentOperation.Completed<>(
        new MyRequest(
            request.id(),
            now,
            State.PENDING,
            ShiftView.of(shift, sites.get(shift.siteId())),
            request.reasonLocale(),
            request.reason(),
            null),
        request.id(),
        Outcome.CREATED);
  }

  // ------------------------------------------------------------------------------------------
  // Decision
  // ------------------------------------------------------------------------------------------

  /**
   * Approves (with a substitute) or rejects a pending request routed to the caller, or replays an
   * earlier identical decision while the caller's linked employee is still the request's effective
   * manager.
   *
   * @param caller verified employee
   * @param requestId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the decision
   * @return the receipt
   */
  public IdempotentOperation.Result<DecisionReceipt> decide(
      PeopleCaller caller,
      String requestId,
      String idempotencyKey,
      DecideWorkScheduleSubstitutionRequest body) {
    Decide command =
        operations.validated(
            DECIDE_SPEC,
            () -> WorkScheduleSubstitutionCommand.decide(idempotencyKey, requestId, body));
    return counted(
        DECIDE,
        () ->
            operations.execute(
                DECIDE_SPEC,
                caller.subject(),
                idempotencyKey,
                command.canonical(caller.tenant()),
                DecisionReceipt.class,
                TRANSACTION_TIMEOUT,
                stored -> decisionReplayable(caller, stored),
                () -> decided(caller, command)));
  }

  /**
   * A decision replays only while the caller's currently linked employee is still the request's
   * effective manager; otherwise the same {@code 404} as an unknown request, with no stored data.
   */
  private void decisionReplayable(PeopleCaller caller, UUID requestId) {
    TenantId tenant = caller.tenant();
    graph.lock(tenant);
    Optional<Request> request =
        requestId == null ? Optional.empty() : substitutions.request(tenant, requestId);
    Optional<Shift> shift = request.flatMap(found -> substitutions.shift(tenant, found.slotId()));
    SelfLink link = link(caller);
    if (request.isEmpty()
        || shift.isEmpty()
        || !substitutions.reportsTo(
            tenant, request.get().employmentId(), shift.get().firstDate(), link.employeeId())) {
      throw WorkScheduleSubstitutionCommand.notFound();
    }
  }

  private IdempotentOperation.Completed<DecisionReceipt> decided(
      PeopleCaller caller, Decide command) {
    TenantId tenant = caller.tenant();
    graph.lock(tenant);
    schedules.lock(tenant);
    Optional<Request> seen = substitutions.request(tenant, command.requestId());
    Optional<Shift> seenShift = seen.flatMap(found -> substitutions.shift(tenant, found.slotId()));
    if (seen.isPresent() && seenShift.isPresent()) {
      substitutions.shareEmployments(
          tenant,
          List.of(seen.get().employmentId()),
          command.approve() ? command.substituteId() : null,
          seenShift.get().firstDate(),
          seenShift.get().lastDate());
    }
    // The link is checked before anything about the request is answered.
    SelfLink link = link(caller);
    if (seen.isEmpty() || seenShift.isEmpty()) {
      throw WorkScheduleSubstitutionCommand.notFound();
    }
    schedules
        .lockSchedule(tenant, seen.get().scheduleId())
        .orElseThrow(WorkScheduleSubstitutionCommand::notFound);
    Request request =
        substitutions
            .lockRequest(tenant, command.requestId())
            .orElseThrow(WorkScheduleSubstitutionCommand::notFound);
    Shift shift =
        substitutions
            .shift(tenant, request.slotId())
            .orElseThrow(WorkScheduleSubstitutionCommand::notFound);
    UUID manager = link.employeeId();
    if (request.employeeId().equals(manager)
        || !substitutions.reportsTo(tenant, request.employmentId(), shift.firstDate(), manager)) {
      throw WorkScheduleSubstitutionCommand.notFound();
    }
    if (substitutions.decision(tenant, request.id()).isPresent()) {
      throw alreadyDecided();
    }
    if (!shift.operational() || !substitutions.pending(tenant, request.id())) {
      throw notAvailable("NOT_OPERATIONAL");
    }
    Instant now = now();
    if (!shift.startAt().isAfter(now)) {
      throw windowClosed();
    }
    UUID substituteEmployment = null;
    if (command.approve()) {
      UUID substitute = command.substituteId();
      if (substitute.equals(request.employeeId()) || substitute.equals(manager)) {
        throw notEligible();
      }
      substituteEmployment =
          substitutions
              .eligibleEmployment(tenant, shift, manager, request.employeeId(), substitute)
              .orElseThrow(WorkScheduleSubstitutionService::notEligible);
      if (substitutions.overlaps(tenant, substitute, shift.startAt(), shift.endAt())) {
        throw new ApiException(ErrorCode.WORK_SCHEDULE_SUBSTITUTE_CONFLICT, Map.of());
      }
    }
    Decision decision =
        new Decision(
            UUID.randomUUID(),
            request.id(),
            command.outcome(),
            manager,
            command.approve() ? command.substituteId() : null,
            substituteEmployment,
            command.reasonLocale(),
            command.reason(),
            now);
    try {
      // The evidence first: the coverage and pending guards require the decision to exist.
      substitutions.insertDecision(tenant, request, decision, caller.subject());
      if (command.approve() && substitutions.moveCoverage(tenant, shift, decision) != 1) {
        throw new IllegalStateException("an approved slot had no original coverage");
      }
      substitutions.removePending(tenant, request.id());
      substitutions.checkConsistent(tenant);
    } catch (DataIntegrityViolationException violated) {
      throw mapped(violated);
    }
    Map<String, Object> ids = ids(request);
    ids.put("decisionId", decision.id().toString());
    ids.put("managerEmployeeId", manager.toString());
    if (command.approve()) {
      ids.put("substituteEmployeeId", decision.substituteEmployeeId().toString());
      ids.put("substituteEmploymentId", substituteEmployment.toString());
    }
    Map<String, Object> metadata = new LinkedHashMap<>(ids);
    metadata.put("authority", "MANAGER");
    metadata.put("priorState", State.PENDING.name());
    metadata.put("resultingState", decision.outcome());
    Map<String, Object> state = new TreeMap<>(ids);
    state.remove("schemaVersion");
    state.put("tenantId", tenant.toString());
    state.put("state", decision.outcome());
    state.put("reasonLocale", decision.reasonLocale());
    state.put("reason", decision.reason());
    state.put("decidedAt", now.toString());
    state.put("decidedBy", caller.subject());
    String action = command.approve() ? APPROVE : REJECT;
    recorded(caller, action, request.id(), metadata, state, now);
    Map<String, Object> data = new LinkedHashMap<>(ids);
    data.remove("schemaVersion");
    data.put("state", decision.outcome());
    event(caller, command.approve() ? APPROVED_EVENT : REJECTED_EVENT, request.id(), data, now);
    LOG.atInfo()
        .addKeyValue("operation", action)
        .addKeyValue("outcome", command.approve() ? "approved" : "rejected")
        .log(
            command.approve()
                ? "work_schedule_substitution_approved"
                : "work_schedule_substitution_rejected");
    return new IdempotentOperation.Completed<>(
        new DecisionReceipt(request.id(), decision.id(), decision.outcome(), now),
        request.id(),
        Outcome.UPDATED);
  }

  // ------------------------------------------------------------------------------------------
  // The employee's history
  // ------------------------------------------------------------------------------------------

  /**
   * One page of the caller's own requests, newest first, each state derived at one pinned server
   * instant.
   *
   * @param caller verified employee
   * @param cursor opaque cursor or {@code null}
   * @param limit page size (1 to 50, default 25)
   * @return the page
   */
  public MyPage history(PeopleCaller caller, String cursor, String limit) {
    FieldErrors errors = new FieldErrors();
    int size = WorkScheduleService.limit(limit, errors);
    errors.throwIfAny();
    return listed(
        SELF_LIST,
        cursor,
        () ->
            read(
                () -> {
                  TenantId tenant = caller.tenant();
                  SelfLink link = link(caller);
                  CursorScope scope =
                      new CursorScope(
                          SELF_LIST,
                          tenant,
                          Map.of(
                              "subject", subjectDigest(caller),
                              "employee", link.employeeId().toString(),
                              "limit", Integer.toString(size)));
                  Instant asOf;
                  Instant afterAt = null;
                  UUID afterId = null;
                  if (cursor != null) {
                    KeysetPosition position = cursors.decode(cursor, scope);
                    asOf = anchorOf(position, HISTORY);
                    afterAt =
                        substitutions
                            .ownRequestedAt(tenant, link.employeeId(), position.id())
                            .orElseThrow(WorkScheduleService::invalidCursor);
                    afterId = position.id();
                  } else {
                    asOf = now();
                  }
                  List<HistoryItem> rows =
                      substitutions.history(tenant, link.employeeId(), afterAt, afterId, size + 1);
                  List<HistoryItem> shown = rows.subList(0, Math.min(rows.size(), size));
                  String next =
                      rows.size() > size
                          ? cursors.encode(
                              scope,
                              new KeysetPosition(
                                  anchorCode(HISTORY, asOf),
                                  shown.get(shown.size() - 1).request().id()))
                          : null;
                  Map<UUID, UnitView> sites =
                      sites(tenant, shown.stream().map(HistoryItem::shift).toList());
                  List<MyRequest> items = new ArrayList<>(shown.size());
                  for (HistoryItem item : shown) {
                    items.add(mine(item, asOf, sites));
                  }
                  StringBuilder text =
                      new StringBuilder("DIVALHR-WORK-SCHEDULE-SUBSTITUTION-DISCLOSURE\n")
                          .append("version=1\nview=my-substitutions\nemployee=")
                          .append(link.employeeId())
                          .append("\nasOf=")
                          .append(asOf)
                          .append('\n');
                  items.forEach(
                      item ->
                          text.append("id=")
                              .append(item.id())
                              .append(";state=")
                              .append(item.state())
                              .append('\n'));
                  disclosed(
                      caller,
                      SELF_LIST,
                      "employee",
                      link.employeeId(),
                      "my-substitutions",
                      cursor,
                      items.size(),
                      text.toString());
                  return new MyPage(items, next, asOf);
                }),
        page -> page.items().size());
  }

  private static MyRequest mine(HistoryItem item, Instant asOf, Map<UUID, UnitView> sites) {
    Request request = item.request();
    Decision decision = item.decision();
    State state =
        State.of(
            decision == null ? null : decision.outcome(),
            item.pending(),
            item.shift().startAt(),
            asOf);
    return new MyRequest(
        request.id(),
        request.requestedAt(),
        state,
        ShiftView.of(item.shift(), sites.get(item.shift().siteId())),
        request.reasonLocale(),
        request.reason(),
        decision == null
            ? null
            : new MyDecision(
                decision.outcome(),
                decision.decidedAt(),
                decision.reasonLocale(),
                decision.reason(),
                item.substitute() == null ? null : Person.of(item.substitute())));
  }

  // ------------------------------------------------------------------------------------------
  // The manager's inbox
  // ------------------------------------------------------------------------------------------

  /**
   * One page of the pending, not yet started requests routed to the caller as manager, oldest
   * first; the routing is re-evaluated on every page.
   *
   * @param caller verified employee
   * @param cursor opaque cursor or {@code null}
   * @param limit page size (1 to 50, default 25)
   * @return the page
   */
  public ApprovalPage inbox(PeopleCaller caller, String cursor, String limit) {
    FieldErrors errors = new FieldErrors();
    int size = WorkScheduleService.limit(limit, errors);
    errors.throwIfAny();
    return listed(
        MANAGER_LIST,
        cursor,
        () ->
            read(
                () -> {
                  TenantId tenant = caller.tenant();
                  SelfLink link = link(caller);
                  CursorScope scope =
                      new CursorScope(
                          MANAGER_LIST,
                          tenant,
                          Map.of(
                              "subject", subjectDigest(caller),
                              "manager", link.employeeId().toString(),
                              "link", link.linkId().toString(),
                              "limit", Integer.toString(size)));
                  Instant asOf;
                  Instant afterAt = null;
                  UUID afterId = null;
                  if (cursor != null) {
                    KeysetPosition position = cursors.decode(cursor, scope);
                    asOf = anchorOf(position, INBOX);
                    afterAt =
                        substitutions
                            .requestedAt(tenant, position.id())
                            .orElseThrow(WorkScheduleService::invalidCursor);
                    afterId = position.id();
                  } else {
                    asOf = now();
                  }
                  List<InboxItem> rows =
                      substitutions.inbox(
                          tenant, link.employeeId(), asOf, afterAt, afterId, size + 1);
                  List<InboxItem> shown = rows.subList(0, Math.min(rows.size(), size));
                  String next =
                      rows.size() > size
                          ? cursors.encode(
                              scope,
                              new KeysetPosition(
                                  anchorCode(INBOX, asOf),
                                  shown.get(shown.size() - 1).request().id()))
                          : null;
                  Map<UUID, UnitView> sites =
                      sites(tenant, shown.stream().map(InboxItem::shift).toList());
                  List<Approval> items =
                      shown.stream()
                          .map(
                              item ->
                                  new Approval(
                                      item.request().id(),
                                      item.request().requestedAt(),
                                      new ApprovalEmployee(
                                          item.employee().employeeNumber(),
                                          item.employee().givenNames(),
                                          item.employee().familyName()),
                                      item.request().reasonLocale(),
                                      item.request().reason(),
                                      ShiftView.of(item.shift(), sites.get(item.shift().siteId()))))
                          .toList();
                  StringBuilder text =
                      new StringBuilder("DIVALHR-WORK-SCHEDULE-SUBSTITUTION-DISCLOSURE\n")
                          .append("version=1\nview=manager-inbox\nmanager=")
                          .append(link.employeeId())
                          .append("\nasOf=")
                          .append(asOf)
                          .append('\n');
                  items.forEach(item -> text.append("id=").append(item.id()).append('\n'));
                  disclosed(
                      caller,
                      MANAGER_LIST,
                      "employee",
                      link.employeeId(),
                      "manager-inbox",
                      cursor,
                      items.size(),
                      text.toString());
                  return new ApprovalPage(items, next, asOf);
                }),
        page -> page.items().size());
  }

  // ------------------------------------------------------------------------------------------
  // Candidates
  // ------------------------------------------------------------------------------------------

  /**
   * One page of eligible substitutes for a pending, not yet started request routed to the caller.
   *
   * @param caller verified employee
   * @param requestId path value
   * @param query the search query (never logged, audited or echoed)
   * @param cursor opaque cursor or {@code null}
   * @param limit page size (1 to 20, default 20)
   * @return the page
   */
  public CandidatePage candidates(
      PeopleCaller caller, String requestId, String query, String cursor, String limit) {
    return listed(
        CANDIDATE_LIST,
        cursor,
        () -> {
          UUID id = WorkScheduleSubstitutionCommand.requestId(requestId);
          FieldErrors errors = new FieldErrors();
          String normalized = query(errors, query);
          int size = candidateLimit(errors, limit);
          errors.throwIfAny();
          return read(
              () -> {
                TenantId tenant = caller.tenant();
                SelfLink link = link(caller);
                Request request =
                    substitutions
                        .request(tenant, id)
                        .orElseThrow(WorkScheduleSubstitutionCommand::notFound);
                Shift shift =
                    substitutions
                        .shift(tenant, request.slotId())
                        .orElseThrow(WorkScheduleSubstitutionCommand::notFound);
                UUID manager = link.employeeId();
                if (request.employeeId().equals(manager)
                    || !substitutions.reportsTo(
                        tenant, request.employmentId(), shift.firstDate(), manager)
                    || !substitutions.pending(tenant, request.id())
                    || !shift.operational()
                    || !shift.startAt().isAfter(now())) {
                  throw WorkScheduleSubstitutionCommand.notFound();
                }
                String queryDigest =
                    Fingerprints.sha256(
                        "DIVALHR-WORK-SCHEDULE-SUBSTITUTION-QUERY\nquery=" + normalized);
                CursorScope scope =
                    new CursorScope(
                        CANDIDATE_LIST,
                        tenant,
                        Map.of(
                            "subject", subjectDigest(caller),
                            "manager", manager.toString(),
                            "link", link.linkId().toString(),
                            "request", request.id().toString(),
                            "query", queryDigest,
                            "limit", Integer.toString(size)));
                String afterNumber = null;
                UUID afterId = null;
                if (cursor != null) {
                  KeysetPosition position = cursors.decode(cursor, scope);
                  if (!CANDIDATES.equals(position.code())) {
                    throw WorkScheduleService.invalidCursor();
                  }
                  afterNumber =
                      substitutions
                          .employeeNumber(tenant, position.id())
                          .orElseThrow(WorkScheduleService::invalidCursor);
                  afterId = position.id();
                }
                String upper = normalized.toUpperCase(Locale.ROOT);
                List<Substitution.Person> rows =
                    substitutions.candidates(
                        tenant,
                        shift,
                        manager,
                        request.employeeId(),
                        EMPLOYEE_NUMBER.matcher(upper).matches() ? upper : null,
                        EmployeeSearchKey.words(normalized),
                        afterNumber,
                        afterId,
                        size + 1);
                List<Substitution.Person> shown = rows.subList(0, Math.min(rows.size(), size));
                String next =
                    rows.size() > size
                        ? cursors.encode(
                            scope, new KeysetPosition(CANDIDATES, shown.get(shown.size() - 1).id()))
                        : null;
                StringBuilder text =
                    new StringBuilder("DIVALHR-WORK-SCHEDULE-SUBSTITUTION-DISCLOSURE\n")
                        .append("version=1\nview=substitute-candidates\nrequest=")
                        .append(request.id())
                        .append("\nmanager=")
                        .append(manager)
                        .append("\nquery=")
                        .append(queryDigest)
                        .append('\n');
                shown.forEach(person -> text.append("id=").append(person.id()).append('\n'));
                disclosed(
                    caller,
                    CANDIDATE_LIST,
                    RESOURCE,
                    request.id(),
                    "substitute-candidates",
                    cursor,
                    shown.size(),
                    text.toString());
                return new CandidatePage(shown.stream().map(Person::of).toList(), next);
              });
        },
        page -> page.items().size());
  }

  /** The normalized query: NFC, trimmed, 2 to 100 code points; never echoed. */
  private static String query(FieldErrors errors, String raw) {
    if (raw == null) {
      errors.add("query", Constraint.REQUIRED);
      return null;
    }
    String normalized = Normalizer.normalize(raw, Normalizer.Form.NFC).strip();
    int length = normalized.codePointCount(0, normalized.length());
    if (length < 2 || length > 100) {
      errors.add("query", Constraint.LENGTH);
      return null;
    }
    return normalized;
  }

  private static int candidateLimit(FieldErrors errors, String raw) {
    if (raw == null || raw.isEmpty()) {
      return CANDIDATE_LIMIT;
    }
    if (LIMIT.matcher(raw).matches()) {
      int value = Integer.parseInt(raw);
      if (value >= 1 && value <= CANDIDATE_LIMIT) {
        return value;
      }
    }
    errors.add("limit", Constraint.RANGE);
    return CANDIDATE_LIMIT;
  }

  // ------------------------------------------------------------------------------------------
  // Shared
  // ------------------------------------------------------------------------------------------

  private static Map<String, Object> ids(Request request) {
    Map<String, Object> ids = new LinkedHashMap<>();
    ids.put("schemaVersion", WorkScheduleService.SCHEMA_VERSION);
    ids.put("requestId", request.id().toString());
    ids.put("scheduleId", request.scheduleId().toString());
    ids.put("slotId", request.slotId().toString());
    ids.put("employeeId", request.employeeId().toString());
    ids.put("employmentId", request.employmentId().toString());
    return ids;
  }

  /** The write audit: safe IDs and states; the whole state (reason, subject) only as a digest. */
  private void recorded(
      PeopleCaller caller,
      String action,
      UUID requestId,
      Map<String, Object> metadata,
      Map<String, Object> state,
      Instant at) {
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            at,
            caller.subject(),
            action,
            RESOURCE,
            requestId,
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(json.writeValueAsString(state))));
  }

  private void event(
      PeopleCaller caller, String type, UUID requestId, Map<String, Object> data, Instant at) {
    outbox.append(
        new EventEnvelope(
            UUID.randomUUID().toString(),
            type,
            WorkScheduleService.SCHEMA_VERSION,
            caller.tenant().toString(),
            SOURCE,
            requestId.toString(),
            at.toString(),
            caller.correlationId(),
            null,
            data));
  }

  /** A fail-closed disclosure audit: the view, page and count; the IDs only in the digest. */
  private void disclosed(
      PeopleCaller caller,
      String action,
      String resourceType,
      UUID resourceId,
      String view,
      String cursor,
      int count,
      String text) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", WorkScheduleService.SCHEMA_VERSION);
    metadata.put("view", view);
    metadata.put("page", cursor == null ? "first" : "next");
    metadata.put("resultCount", count);
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            now(),
            caller.subject(),
            action,
            resourceType,
            resourceId,
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(text)));
  }

  private Map<UUID, UnitView> sites(TenantId tenant, Collection<Shift> shifts) {
    Set<UUID> ids = new LinkedHashSet<>();
    shifts.forEach(shift -> ids.add(shift.siteId()));
    return ids.isEmpty() ? Map.of() : directory.resolveIds(tenant, ids);
  }

  private static String subjectDigest(PeopleCaller caller) {
    return Fingerprints.sha256("DIVALHR-WORK-SCHEDULE-SUBSTITUTION\nsubject=" + caller.subject());
  }

  /**
   * The keyset code of an anchored page: the view's prefix and the anchor instant in epoch
   * microseconds, inside the signed payload, so later pages reuse exactly the server's instant.
   */
  static String anchorCode(String prefix, Instant anchor) {
    return prefix + ChronoUnit.MICROS.between(Instant.EPOCH, anchor);
  }

  /** The anchor pinned in a cursor of the view; anything else is {@code CURSOR_INVALID}. */
  static Instant anchorOf(KeysetPosition position, String prefix) {
    String code = position.code();
    if (!ANCHORED.matcher(code).matches() || !code.startsWith(prefix)) {
      throw WorkScheduleService.invalidCursor();
    }
    return Instant.EPOCH.plus(Long.parseLong(code.substring(prefix.length())), ChronoUnit.MICROS);
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
    return new ApiException(ErrorCode.WORK_SCHEDULE_SHIFT_NOT_FOUND, Map.of());
  }

  private static ApiException notAvailable(String reason) {
    return new ApiException(
        ErrorCode.WORK_SCHEDULE_SUBSTITUTION_NOT_AVAILABLE, Map.of("reason", reason));
  }

  private static ApiException windowClosed() {
    return new ApiException(ErrorCode.WORK_SCHEDULE_SUBSTITUTION_WINDOW_CLOSED, Map.of());
  }

  private static ApiException alreadyDecided() {
    return new ApiException(ErrorCode.WORK_SCHEDULE_SUBSTITUTION_ALREADY_DECIDED, Map.of());
  }

  private static ApiException notEligible() {
    return new ApiException(ErrorCode.WORK_SCHEDULE_SUBSTITUTE_NOT_ELIGIBLE, Map.of());
  }

  /** The stable conflict of a constraint the locks should have made unreachable; no DB text. */
  private static ApiException mapped(DataIntegrityViolationException violated) {
    String constraint = ScheduleConstraintViolations.constraint(violated).orElse("");
    return switch (constraint) {
      case ScheduleConstraintViolations.SUBSTITUTION_PENDING_SLOT ->
          new ApiException(ErrorCode.WORK_SCHEDULE_SUBSTITUTION_ALREADY_PENDING, Map.of());
      case ScheduleConstraintViolations.SUBSTITUTION_DECISION_REQUEST,
          ScheduleConstraintViolations.SUBSTITUTION_ONE_APPROVAL ->
          alreadyDecided();
      case ScheduleConstraintViolations.EMPLOYEE_OVERLAP ->
          new ApiException(ErrorCode.WORK_SCHEDULE_SUBSTITUTE_CONFLICT, Map.of());
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

  /** Records a read's outcome and logs it without any value. */
  private <T> T listed(String operation, String cursor, Supplier<T> work, ToIntFunction<T> count) {
    try {
      T page = counted(operation, work);
      metrics.record(operation, Outcome.LISTED);
      LOG.atInfo()
          .addKeyValue("operation", operation)
          .addKeyValue("page", cursor == null ? "first" : "next")
          .addKeyValue("resultCount", count.applyAsInt(page))
          .addKeyValue("outcome", "listed")
          .log("work_schedule_substitution_listed");
      return page;
    } catch (ApiException rejected) {
      Outcome outcome =
          switch (rejected.code()) {
            case EMPLOYEE_LINK_REQUIRED -> Outcome.DENIED;
            case WORK_SCHEDULE_SUBSTITUTION_NOT_FOUND -> Outcome.NOT_FOUND;
            default -> Outcome.VALIDATION_FAILED;
          };
      metrics.record(operation, outcome);
      LOG.atInfo()
          .addKeyValue("operation", operation)
          .addKeyValue("outcome", outcome.name().toLowerCase(Locale.ROOT))
          .addKeyValue("code", rejected.code().name())
          .log("work_schedule_substitution_list_rejected");
      throw rejected;
    } catch (RuntimeException failure) {
      metrics.record(operation, Outcome.FAILURE);
      throw failure;
    }
  }

  /** Counts link denials and undisclosable targets on the bounded self-service counter only. */
  private <T> T counted(String operation, Supplier<T> work) {
    try {
      return work.get();
    } catch (ApiException denied) {
      if (denied.code() == ErrorCode.EMPLOYEE_LINK_REQUIRED) {
        ScopeAuthorizationInterceptor.selfServiceDenied(meters, operation, "link_required");
      } else if (denied.code() == ErrorCode.WORK_SCHEDULE_SUBSTITUTION_NOT_FOUND
          || denied.code() == ErrorCode.WORK_SCHEDULE_SHIFT_NOT_FOUND) {
        ScopeAuthorizationInterceptor.selfServiceDenied(meters, operation, "not_found");
      }
      throw denied;
    }
  }
}
