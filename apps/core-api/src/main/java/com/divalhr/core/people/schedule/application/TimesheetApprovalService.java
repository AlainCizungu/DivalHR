package com.divalhr.core.people.schedule.application;

import com.divalhr.core.people.application.BusinessCalendar;
import com.divalhr.core.people.application.ManagerGraphLock;
import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.schedule.api.ApproveTimesheetRequest;
import com.divalhr.core.people.schedule.api.ReturnTimesheetRequest;
import com.divalhr.core.people.schedule.api.SubmitTimesheetRequest;
import com.divalhr.core.people.schedule.api.TimesheetResponses;
import com.divalhr.core.people.schedule.api.TimesheetResponses.ActionReceipt;
import com.divalhr.core.people.schedule.api.TimesheetResponses.ActionView;
import com.divalhr.core.people.schedule.api.TimesheetResponses.ApprovalDetail;
import com.divalhr.core.people.schedule.api.TimesheetResponses.ApprovalEmployee;
import com.divalhr.core.people.schedule.api.TimesheetResponses.ApprovalItem;
import com.divalhr.core.people.schedule.api.TimesheetResponses.ApprovalPage;
import com.divalhr.core.people.schedule.api.TimesheetResponses.MyRow;
import com.divalhr.core.people.schedule.api.TimesheetResponses.TotalsView;
import com.divalhr.core.people.schedule.application.TimesheetApprovalCommand.Decide;
import com.divalhr.core.people.schedule.application.TimesheetApprovalCommand.Filters;
import com.divalhr.core.people.schedule.application.TimesheetApprovalCommand.Submit;
import com.divalhr.core.people.schedule.domain.Substitution.Person;
import com.divalhr.core.people.schedule.domain.Timesheet;
import com.divalhr.core.people.schedule.domain.Timesheet.Row;
import com.divalhr.core.people.schedule.domain.TimesheetApproval;
import com.divalhr.core.people.schedule.domain.TimesheetApproval.Action;
import com.divalhr.core.people.schedule.domain.TimesheetApproval.Kind;
import com.divalhr.core.people.schedule.domain.TimesheetApproval.Status;
import com.divalhr.core.people.schedule.internal.JdbcTimesheetActionRepository;
import com.divalhr.core.people.schedule.internal.JdbcTimesheetActionRepository.QueueItem;
import com.divalhr.core.people.schedule.internal.JdbcTimesheetRepository;
import com.divalhr.core.people.schedule.internal.JdbcTimesheetRepository.Generation;
import com.divalhr.core.people.schedule.internal.JdbcTimesheetRepository.Header;
import com.divalhr.core.people.schedule.internal.JdbcWorkScheduleRepository;
import com.divalhr.core.people.schedule.internal.JdbcWorkScheduleSubstitutionRepository;
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
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.UnitKind;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.UnitView;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
 * Weekly time submission and approval (MVP-070B/C, Issue #130, T70BC-1 to T70BC-12; ADR 0019).
 * Authorization (the {@code employee} role with an active employee-access link) has run before
 * this; tenant, employee, actor and route never come from the request, and a tenant administrator
 * role grants nothing here.
 *
 * <p>The employee submits one of their own clean DRAFTs; the manager the timesheet routes to now
 * ({@link TimesheetRouting}) approves it or returns it with a reason. Every command is one bounded
 * transaction in the lock order of ADR 0018 preceded by the manager graph (as substitution
 * decisions): idempotency, the tenant's manager-graph lock, its scheduling lock, the caller's link
 * {@code FOR SHARE}, the timesheet {@code FOR UPDATE}, and for a decision the week's candidates'
 * employments {@code FOR SHARE} before its sources are evaluated again. Then the append-only action
 * (V33 refuses any other order of actions), the timesheet's matching state, the deferred checks,
 * the audit record and the outbox event, committed together.
 *
 * <p>Names, numbers, labels, attendance instants, exact worked time, digests, the subject, the
 * return reason and idempotency keys never enter audit metadata, events, logs, metrics or errors.
 */
@Service
public class TimesheetApprovalService {

  /** Submission (idempotency operation, interceptor operation and audit action). */
  public static final String SUBMIT = "timesheet.submit";

  /** Approval (idempotency operation, interceptor operation and audit action). */
  public static final String APPROVE = "timesheet.approve";

  /** Return (idempotency operation, interceptor operation and audit action). */
  public static final String RETURN = "timesheet.return";

  /** The routed manager's queue (interceptor operation and disclosure audit action). */
  public static final String APPROVAL_LIST = "timesheet.approval-list";

  /** One submitted timesheet for its routed manager (interceptor and disclosure audit action). */
  public static final String APPROVAL_READ = "timesheet.approval-read";

  /** Outbox event of a submission. */
  public static final String SUBMITTED_EVENT = "people.timesheet.submitted.v1";

  /** Outbox event of an approval. */
  public static final String APPROVED_EVENT = "people.timesheet.approved.v1";

  /** Outbox event of a return. */
  public static final String RETURNED_EVENT = "people.timesheet.returned.v1";

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.timesheet");
  private static final String SOURCE = "core-api/people";
  private static final String RESOURCE = "timesheet";
  private static final Duration TRANSACTION_TIMEOUT = Duration.ofSeconds(30);
  private static final IdempotentOperation.Spec SUBMIT_SPEC =
      new IdempotentOperation.Spec(SUBMIT, "timesheet", "submit", "submitted", 200);
  private static final IdempotentOperation.Spec APPROVE_SPEC =
      new IdempotentOperation.Spec(APPROVE, "timesheet", "approve", "approved", 200);
  private static final IdempotentOperation.Spec RETURN_SPEC =
      new IdempotentOperation.Spec(RETURN, "timesheet", "return", "returned", 200);
  private static final Pattern LIMIT = Pattern.compile("^[0-9]{1,3}$");
  private static final int DEFAULT_LIMIT = 20;
  private static final int MAX_LIMIT = 100;

  private final IdempotentOperation operations;
  private final ManagerGraphLock graph;
  private final JdbcWorkScheduleRepository schedules;
  private final JdbcWorkScheduleSubstitutionRepository shifts;
  private final JdbcTimesheetRepository timesheets;
  private final JdbcTimesheetActionRepository actions;
  private final TimesheetService drafts;
  private final TimesheetRouting routing;
  private final OrganizationPlacementDirectory directory;
  private final EmployeeAccessLinks links;
  private final CursorCodec cursors;
  private final BusinessCalendar calendar;
  private final AuditRecorder audit;
  private final OutboxWriter outbox;
  private final OperationMetrics metrics;
  private final PlatformTransactionManager transactionManager;
  private final JsonMapper json;

  /**
   * Creates the service.
   *
   * @param operations shared idempotent command flow
   * @param graph the tenant's manager-graph lock (routes are frozen while it is held)
   * @param schedules schedule storage (the scheduling lock)
   * @param shifts employment locks and identities
   * @param timesheets timesheet storage
   * @param actions submission and decision storage
   * @param drafts the draft timesheets (a decision's sources evaluated again)
   * @param routing the approval route
   * @param directory the tenant module's unit port (site names)
   * @param links the identity port (the caller's own link)
   * @param cursors signed cursors
   * @param calendar the platform clock (the authoritative instant)
   * @param audit audit recorder
   * @param outbox outbox writer
   * @param metrics operation metrics
   * @param transactionManager transaction manager
   * @param json JSON mapper
   */
  public TimesheetApprovalService(
      IdempotentOperation operations,
      ManagerGraphLock graph,
      JdbcWorkScheduleRepository schedules,
      JdbcWorkScheduleSubstitutionRepository shifts,
      JdbcTimesheetRepository timesheets,
      JdbcTimesheetActionRepository actions,
      TimesheetService drafts,
      TimesheetRouting routing,
      OrganizationPlacementDirectory directory,
      EmployeeAccessLinks links,
      CursorCodec cursors,
      BusinessCalendar calendar,
      AuditRecorder audit,
      OutboxWriter outbox,
      OperationMetrics metrics,
      PlatformTransactionManager transactionManager,
      JsonMapper json) {
    this.operations = operations;
    this.graph = graph;
    this.schedules = schedules;
    this.shifts = shifts;
    this.timesheets = timesheets;
    this.actions = actions;
    this.drafts = drafts;
    this.routing = routing;
    this.directory = directory;
    this.links = links;
    this.cursors = cursors;
    this.calendar = calendar;
    this.audit = audit;
    this.outbox = outbox;
    this.metrics = metrics;
    this.transactionManager = transactionManager;
    this.json = json;
  }

  // ------------------------------------------------------------------------------------------
  // Submission
  // ------------------------------------------------------------------------------------------

  /**
   * Submits one of the caller's own clean drafts to the manager it routes to; replays an identical
   * earlier submission while the timesheet is still the caller's.
   *
   * @param caller verified employee
   * @param timesheetId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the reviewed revision and generation version, and the confirmation
   * @return the receipt
   */
  public IdempotentOperation.Result<ActionReceipt> submit(
      PeopleCaller caller, String timesheetId, String idempotencyKey, SubmitTimesheetRequest body) {
    return command(
        SUBMIT,
        () -> {
          Submit command =
              operations.validated(
                  SUBMIT_SPEC,
                  () -> TimesheetApprovalCommand.submit(idempotencyKey, timesheetId, body));
          return operations.execute(
              SUBMIT_SPEC,
              caller.subject(),
              idempotencyKey,
              command.canonical(caller.tenant()),
              ActionReceipt.class,
              TRANSACTION_TIMEOUT,
              stored -> own(caller, stored),
              () -> submitted(caller, command));
        });
  }

  /** A submission replays only while the timesheet is the caller's linked employee's. */
  private void own(PeopleCaller caller, UUID timesheetId) {
    SelfLink link = link(caller);
    timesheets
        .timesheet(caller.tenant(), timesheetId)
        .filter(h -> h.employeeId().equals(link.employeeId()))
        .orElseThrow(TimesheetCommand::notFound);
  }

  private IdempotentOperation.Completed<ActionReceipt> submitted(
      PeopleCaller caller, Submit command) {
    TenantId tenant = caller.tenant();
    graph.lock(tenant);
    schedules.lock(tenant);
    SelfLink link = link(caller);
    Header header =
        timesheets
            .lockTimesheet(tenant, command.timesheetId())
            .filter(h -> h.employeeId().equals(link.employeeId()))
            .orElseThrow(TimesheetCommand::notFound);
    if (!header.draft()) {
      throw new ApiException(ErrorCode.TIMESHEET_SUBMISSION_NOT_ALLOWED, Map.of());
    }
    Generation generation = generation(tenant, header);
    if (header.revision() != command.revision()
        || generation.version() != command.generationVersion()) {
      throw stale();
    }
    if (!TimesheetApproval.rowBlockers(header.totals()).isEmpty()) {
      throw new ApiException(ErrorCode.TIMESHEET_SUBMISSION_NOT_ALLOWED, Map.of());
    }
    UUID employment =
        routing.employment(tenant, header).orElseThrow(TimesheetApprovalService::noRoute);
    shifts.shareEmployments(
        tenant,
        List.of(employment),
        header.employeeId(),
        header.period().weekStart(),
        header.period().lastDay());
    UUID manager =
        routing.manager(tenant, header, employment).orElseThrow(TimesheetApprovalService::noRoute);
    Instant now = now();
    Action action =
        new Action(
            UUID.randomUUID(),
            header.id(),
            sequence(tenant, header),
            Kind.SUBMITTED,
            header.cycle() + 1,
            null,
            header.revision(),
            generation.version(),
            timesheets.revisionDigest(tenant, header.id(), header.revision()),
            header.totals(),
            header.employeeId(),
            employment,
            manager,
            null,
            now);
    appended(caller, header, action, Status.DRAFT, "");
    return new IdempotentOperation.Completed<>(
        ActionReceipt.of(action), header.id(), Outcome.UPDATED);
  }

  // ------------------------------------------------------------------------------------------
  // The routed manager's queue and reads
  // ------------------------------------------------------------------------------------------

  /**
   * One page of the currently submitted timesheets the caller approves now, newest submission
   * first.
   *
   * @param caller verified employee acting as a manager
   * @param siteId optional site filter
   * @param weekStart optional site-local Monday filter
   * @param cursor opaque cursor or {@code null}
   * @param limit page size (1 to 100, default 20)
   * @return the page, after its disclosure audit committed
   */
  public ApprovalPage queue(
      PeopleCaller caller, String siteId, String weekStart, String cursor, String limit) {
    return observed(
        APPROVAL_LIST,
        () -> {
          FieldErrors errors = new FieldErrors();
          Filters filters = TimesheetApprovalCommand.filters(siteId, weekStart, errors);
          int size = limit(limit, errors);
          errors.throwIfAny();
          return read(
              () -> {
                TenantId tenant = caller.tenant();
                SelfLink link = link(caller);
                Map<String, String> bound = new TreeMap<>();
                bound.put("link", link.linkId().toString());
                bound.put("employee", link.employeeId().toString());
                bound.put("site", filters.siteId() == null ? "" : filters.siteId().toString());
                bound.put(
                    "week", filters.weekStart() == null ? "" : filters.weekStart().toString());
                bound.put("limit", Integer.toString(size));
                CursorScope scope = new CursorScope(APPROVAL_LIST, tenant, bound);
                Instant afterAt = null;
                UUID afterId = null;
                if (cursor != null) {
                  KeysetPosition position = cursors.decode(cursor, scope);
                  afterAt = instant(position.code());
                  afterId = position.id();
                }
                List<QueueItem> rows =
                    actions.queue(
                        tenant,
                        link.employeeId(),
                        filters.siteId(),
                        filters.weekStart(),
                        afterAt,
                        afterId,
                        size + 1);
                List<QueueItem> shown = rows.subList(0, Math.min(rows.size(), size));
                String next =
                    rows.size() > size
                        ? cursors.encode(
                            scope,
                            new KeysetPosition(
                                "A" + shown.get(shown.size() - 1).submittedAt(),
                                shown.get(shown.size() - 1).submissionActionId()))
                        : null;
                Set<UUID> sites = new HashSet<>();
                shown.forEach(q -> sites.add(q.header().period().siteId()));
                Map<UUID, UnitView> resolved =
                    sites.isEmpty() ? Map.of() : directory.resolveIds(tenant, sites);
                List<ApprovalItem> items = new ArrayList<>(shown.size());
                StringBuilder text =
                    new StringBuilder("DIVALHR-TIMESHEET-APPROVAL-LIST\nversion=1\nmanager=")
                        .append(link.employeeId())
                        .append('\n');
                for (QueueItem q : shown) {
                  Header h = q.header();
                  items.add(
                      new ApprovalItem(
                          h.id(),
                          ApprovalEmployee.of(q.employee()),
                          TimesheetResponses.Period.of(
                              h.period(), site(resolved.get(h.period().siteId()))),
                          h.revision(),
                          h.cycle(),
                          h.totals().rows(),
                          h.totals().workedMinutes(),
                          q.submittedAt()));
                  text.append("timesheet=").append(h.id()).append('\n');
                }
                disclosed(caller, APPROVAL_LIST, "time-approvals", items.size(), text.toString());
                return new ApprovalPage(items, next);
              });
        });
  }

  /**
   * One currently submitted timesheet the caller approves now; anything else is the one safe 404.
   *
   * @param caller verified employee acting as a manager
   * @param timesheetId path value
   * @return the timesheet, after its disclosure audit committed
   */
  public ApprovalDetail approval(PeopleCaller caller, String timesheetId) {
    return observed(
        APPROVAL_READ,
        () -> {
          UUID id = TimesheetCommand.id(timesheetId);
          return read(
              () -> {
                TenantId tenant = caller.tenant();
                SelfLink link = link(caller);
                Header header =
                    timesheets.timesheet(tenant, id).orElseThrow(TimesheetCommand::notFound);
                Action submission = routedSubmission(tenant, header, link);
                Person employee =
                    shifts
                        .person(tenant, header.employeeId())
                        .orElseThrow(TimesheetCommand::notFound);
                List<MyRow> rows =
                    timesheets.rows(tenant, id, header.revision()).stream().map(MyRow::of).toList();
                List<ActionView> history =
                    actions.actions(tenant, id).stream().map(ActionView::of).toList();
                disclosed(
                    caller,
                    APPROVAL_READ,
                    "time-approval",
                    rows.size(),
                    "DIVALHR-TIMESHEET-APPROVAL-READ\nversion=1\ntimesheet="
                        + id
                        + "\nsubmission="
                        + submission.id()
                        + "\n");
                return new ApprovalDetail(
                    id,
                    ApprovalEmployee.of(employee),
                    TimesheetResponses.Period.of(
                        header.period(), site(unit(tenant, header.period().siteId()))),
                    Status.SUBMITTED.name(),
                    header.revision(),
                    submission.generationVersion(),
                    header.cycle(),
                    submission.id(),
                    submission.occurredAt(),
                    TotalsView.of(header.totals()),
                    rows,
                    history);
              });
        });
  }

  // ------------------------------------------------------------------------------------------
  // Decisions
  // ------------------------------------------------------------------------------------------

  /**
   * Approves exactly the reviewed submission; replays an identical earlier approval while the
   * caller still holds the route.
   *
   * @param caller verified employee acting as a manager
   * @param timesheetId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the reviewed evidence and the confirmation
   * @return the receipt
   */
  public IdempotentOperation.Result<ActionReceipt> approve(
      PeopleCaller caller,
      String timesheetId,
      String idempotencyKey,
      ApproveTimesheetRequest body) {
    return command(
        APPROVE,
        () -> {
          Decide command =
              operations.validated(
                  APPROVE_SPEC,
                  () -> TimesheetApprovalCommand.approve(idempotencyKey, timesheetId, body));
          return decide(caller, APPROVE_SPEC, idempotencyKey, command);
        });
  }

  /**
   * Returns exactly the reviewed submission with a reason; replays an identical earlier return
   * while the caller still holds the route.
   *
   * @param caller verified employee acting as a manager
   * @param timesheetId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the reviewed evidence, the reason and the confirmation
   * @return the receipt
   */
  public IdempotentOperation.Result<ActionReceipt> giveBack(
      PeopleCaller caller, String timesheetId, String idempotencyKey, ReturnTimesheetRequest body) {
    return command(
        RETURN,
        () -> {
          Decide command =
              operations.validated(
                  RETURN_SPEC,
                  () -> TimesheetApprovalCommand.giveBack(idempotencyKey, timesheetId, body));
          return decide(caller, RETURN_SPEC, idempotencyKey, command);
        });
  }

  private IdempotentOperation.Result<ActionReceipt> decide(
      PeopleCaller caller, IdempotentOperation.Spec spec, String key, Decide command) {
    return operations.execute(
        spec,
        caller.subject(),
        key,
        command.canonical(caller.tenant()),
        ActionReceipt.class,
        TRANSACTION_TIMEOUT,
        stored -> stillRouted(caller, stored),
        () -> decided(caller, command));
  }

  /**
   * A decision replays only while the timesheet routes to the caller's linked employee now;
   * otherwise the same {@code 404} as an unknown timesheet, with no stored data.
   */
  private void stillRouted(PeopleCaller caller, UUID timesheetId) {
    TenantId tenant = caller.tenant();
    graph.lock(tenant);
    SelfLink link = link(caller);
    Header header =
        timesheets.timesheet(tenant, timesheetId).orElseThrow(TimesheetCommand::notFound);
    UUID employment =
        actions.actions(tenant, header.id()).stream()
            .filter(a -> a.cycle() == header.cycle() && a.kind() == Kind.SUBMITTED)
            .map(Action::employmentId)
            .findFirst()
            .orElseThrow(TimesheetCommand::notFound);
    if (!routing.manager(tenant, header, employment).map(link.employeeId()::equals).orElse(false)) {
      throw TimesheetCommand.notFound();
    }
  }

  private IdempotentOperation.Completed<ActionReceipt> decided(
      PeopleCaller caller, Decide command) {
    TenantId tenant = caller.tenant();
    graph.lock(tenant);
    schedules.lock(tenant);
    SelfLink link = link(caller);
    Header header =
        timesheets
            .lockTimesheet(tenant, command.timesheetId())
            .orElseThrow(TimesheetCommand::notFound);
    if (header.currentActionId() == null) {
      // Never submitted: nothing a manager may know about.
      throw TimesheetCommand.notFound();
    }
    Action current = actions.action(tenant, header.currentActionId()).orElseThrow();
    Action submission = submissionOf(tenant, current);
    // The route is resolved now, from the current employment history (T70BC-3, T70BC-6).
    shifts.shareEmployments(
        tenant,
        List.of(submission.employmentId()),
        header.employeeId(),
        header.period().weekStart(),
        header.period().lastDay());
    UUID manager =
        routing
            .manager(tenant, header, submission.employmentId())
            .filter(link.employeeId()::equals)
            .orElseThrow(TimesheetCommand::notFound);
    if (!Status.SUBMITTED.name().equals(header.status())) {
      throw new ApiException(ErrorCode.TIMESHEET_DECISION_NOT_ALLOWED, Map.of());
    }
    Generation generation = generation(tenant, header);
    if (header.revision() != command.revision()
        || generation.version() != command.generationVersion()
        || header.cycle() != command.cycle()
        || !submission.id().equals(command.submissionActionId())
        || submission.generationVersion() != generation.version()) {
      throw stale();
    }
    // An approval evaluates the sources again: it never approves time that no longer matches its
    // sources (the submitted evidence or a recomputed value). A return binds the same evidence but
    // stays possible when the sources changed: it is how such a timesheet goes back to a DRAFT for
    // the administrators' refresh (ADR 0019).
    if (command.approve()) {
      List<Row> next = drafts.nextRows(tenant, header, true);
      List<Row> previous = timesheets.rows(tenant, header.id(), header.revision());
      if (Timesheet.changes(previous, next) > 0
          || !Timesheet.digest(next).equals(submission.sourceDigest())
          || !Timesheet.Totals.of(next).equals(submission.totals())) {
        throw stale();
      }
    }
    Instant now = now();
    Action action =
        new Action(
            UUID.randomUUID(),
            header.id(),
            current.sequence() + 1,
            command.approve() ? Kind.APPROVED : Kind.RETURNED,
            submission.cycle(),
            submission.id(),
            submission.revision(),
            submission.generationVersion(),
            submission.sourceDigest(),
            submission.totals(),
            header.employeeId(),
            submission.employmentId(),
            manager,
            command.reason(),
            now);
    appended(caller, header, action, Status.SUBMITTED, command.reasonDigest());
    return new IdempotentOperation.Completed<>(
        ActionReceipt.of(action), header.id(), Outcome.UPDATED);
  }

  // ------------------------------------------------------------------------------------------
  // Evidence, audit and outbox
  // ------------------------------------------------------------------------------------------

  /** The action, the matching state and the deferred checks; then the audit record and event. */
  private void appended(
      PeopleCaller caller, Header header, Action action, Status from, String reasonDigest) {
    TenantId tenant = caller.tenant();
    try {
      actions.insert(tenant, action, header.period().siteId(), caller.subject());
      if (actions.moveTo(tenant, action, from) != 1) {
        throw stale();
      }
      timesheets.checkConsistent(tenant);
    } catch (DataIntegrityViolationException violated) {
      throw mapped(violated);
    }
    String operation =
        switch (action.kind()) {
          case SUBMITTED -> SUBMIT;
          case APPROVED -> APPROVE;
          case RETURNED -> RETURN;
          case REOPENED -> throw new IllegalStateException("a reopening is a correction's");
        };
    recorded(caller, operation, header, action, reasonDigest);
    event(caller, header, action);
    LOG.atInfo()
        .addKeyValue("operation", operation)
        .addKeyValue("outcome", action.kind().name().toLowerCase(Locale.ROOT))
        .log("timesheet_" + action.kind().name().toLowerCase(Locale.ROOT));
  }

  /**
   * Bounded metadata only (T70BC-11); stable IDs, exact totals, the source digest, the return
   * reason's digest, the manager and the subject only inside the state digest.
   */
  private void recorded(
      PeopleCaller caller, String operation, Header header, Action action, String reasonDigest) {
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
    state.put(
        "submissionActionId",
        action.submissionActionId() == null ? "" : action.submissionActionId().toString());
    state.put("employeeId", action.employeeId().toString());
    state.put("employmentId", action.employmentId().toString());
    state.put("managerEmployeeId", action.managerEmployeeId().toString());
    state.put("sourceDigest", action.sourceDigest());
    state.put("readyRows", action.totals().ready());
    state.put("workedMinutes", action.totals().workedMinutes());
    state.put("reasonDigest", reasonDigest);
    state.put("occurredAt", action.occurredAt().toString());
    state.put("by", caller.subject());
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            action.occurredAt(),
            caller.subject(),
            operation,
            RESOURCE,
            header.id(),
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(json.writeValueAsString(state))));
  }

  /** Stable IDs, the week's boundaries, the evidence numbers and the decision only. */
  private void event(PeopleCaller caller, Header header, Action action) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("timesheetId", header.id().toString());
    data.put("actionId", action.id().toString());
    if (action.submissionActionId() != null) {
      data.put("submissionActionId", action.submissionActionId().toString());
    }
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
    data.put("decision", action.kind().name());
    data.put("status", action.kind().state().name());
    data.put("occurredAt", action.occurredAt().toString());
    String type =
        switch (action.kind()) {
          case SUBMITTED -> SUBMITTED_EVENT;
          case APPROVED -> APPROVED_EVENT;
          case RETURNED -> RETURNED_EVENT;
          case REOPENED -> throw new IllegalStateException("a reopening is a correction's");
        };
    outbox.append(
        new EventEnvelope(
            UUID.randomUUID().toString(),
            type,
            WorkScheduleService.SCHEMA_VERSION,
            caller.tenant().toString(),
            SOURCE,
            header.id().toString(),
            action.occurredAt().toString(),
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

  // ------------------------------------------------------------------------------------------
  // Shared
  // ------------------------------------------------------------------------------------------

  /**
   * The current submission of a SUBMITTED timesheet that routes to the caller's linked employee
   * now; anything else is the one safe 404.
   */
  private Action routedSubmission(TenantId tenant, Header header, SelfLink link) {
    if (!Status.SUBMITTED.name().equals(header.status()) || header.currentActionId() == null) {
      throw TimesheetCommand.notFound();
    }
    Action submission =
        actions
            .action(tenant, header.currentActionId())
            .filter(a -> a.kind() == Kind.SUBMITTED)
            .orElseThrow(TimesheetCommand::notFound);
    boolean routed =
        routing
            .manager(tenant, header, submission.employmentId())
            .map(link.employeeId()::equals)
            .orElse(false);
    if (!routed) {
      throw TimesheetCommand.notFound();
    }
    return submission;
  }

  /**
   * The submission of the cycle an action belongs to: itself, a decision's submission, or the
   * submission of the approval a reopening superseded (MVP-070D).
   */
  private Action submissionOf(TenantId tenant, Action current) {
    return switch (current.kind()) {
      case SUBMITTED -> current;
      case APPROVED, RETURNED -> actions.action(tenant, current.submissionActionId()).orElseThrow();
      case REOPENED ->
          submissionOf(tenant, actions.action(tenant, current.supersededActionId()).orElseThrow());
    };
  }

  private int sequence(TenantId tenant, Header header) {
    if (header.currentActionId() == null) {
      return 1;
    }
    return actions.action(tenant, header.currentActionId()).orElseThrow().sequence() + 1;
  }

  private Generation generation(TenantId tenant, Header header) {
    return timesheets.generation(tenant, header.generationId(), false).orElseThrow();
  }

  private UnitView unit(TenantId tenant, UUID id) {
    return directory.resolveIds(tenant, Set.of(id)).get(id);
  }

  private static UnitView site(UnitView view) {
    return view != null && view.kind() == UnitKind.SITE ? view : null;
  }

  private SelfLink link(PeopleCaller caller) {
    return links
        .linkedEmployee(caller.tenant(), caller.subject())
        .orElseThrow(() -> new ApiException(ErrorCode.EMPLOYEE_LINK_REQUIRED, Map.of()));
  }

  private static Instant instant(String code) {
    if (code == null || !code.startsWith("A")) {
      throw WorkScheduleService.invalidCursor();
    }
    try {
      return Instant.parse(code.substring(1));
    } catch (DateTimeParseException invalid) {
      throw WorkScheduleService.invalidCursor();
    }
  }

  private static int limit(String raw, FieldErrors errors) {
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

  private Instant now() {
    return calendar.now().truncatedTo(ChronoUnit.MICROS);
  }

  private static ApiException stale() {
    return new ApiException(ErrorCode.TIMESHEET_DECISION_STALE, Map.of());
  }

  private static ApiException noRoute() {
    return new ApiException(ErrorCode.TIMESHEET_APPROVAL_ROUTE_UNAVAILABLE, Map.of());
  }

  /** The stable conflict of a constraint the locks should have made unreachable; no DB text. */
  private static ApiException mapped(DataIntegrityViolationException violated) {
    String constraint = ScheduleConstraintViolations.constraint(violated).orElse("");
    return switch (constraint) {
      case ScheduleConstraintViolations.TIMESHEET_ACTION_SEQUENCE,
          ScheduleConstraintViolations.TIMESHEET_ACTION_TRANSITION,
          ScheduleConstraintViolations.TIMESHEET_ACTION_DECISION,
          ScheduleConstraintViolations.TIMESHEET_ACTION_SUBMISSION,
          ScheduleConstraintViolations.TIMESHEET_GUARD ->
          stale();
      default -> throw violated;
    };
  }

  /** A command's log: the operation and a bounded outcome only. */
  private <T> T command(String operation, Supplier<T> work) {
    try {
      return work.get();
    } catch (ApiException refused) {
      LOG.atInfo()
          .addKeyValue("operation", operation)
          .addKeyValue("outcome", refused.code().name().toLowerCase(Locale.ROOT))
          .log("timesheet_command_rejected");
      throw refused;
    }
  }

  /** Records a read outcome; rejections log only their code. */
  private <T> T observed(String operation, Supplier<T> work) {
    try {
      T result = work.get();
      metrics.record(operation, Outcome.LISTED);
      LOG.atInfo()
          .addKeyValue("operation", operation)
          .addKeyValue("outcome", "listed")
          .log("timesheet_read");
      return result;
    } catch (ApiException rejected) {
      Outcome outcome =
          switch (rejected.code()) {
            case TIMESHEET_TARGET_NOT_FOUND -> Outcome.NOT_FOUND;
            case VALIDATION_FAILED, CURSOR_INVALID -> Outcome.VALIDATION_FAILED;
            case EMPLOYEE_LINK_REQUIRED -> Outcome.DENIED;
            default -> Outcome.STATE_CONFLICT;
          };
      metrics.record(operation, outcome);
      LOG.atInfo()
          .addKeyValue("operation", operation)
          .addKeyValue("outcome", outcome.name().toLowerCase(Locale.ROOT))
          .addKeyValue("code", rejected.code().name())
          .log("timesheet_read_rejected");
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
