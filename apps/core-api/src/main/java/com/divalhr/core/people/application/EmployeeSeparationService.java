package com.divalhr.core.people.application;

import com.divalhr.core.people.api.CancelSeparationRequest;
import com.divalhr.core.people.api.CreateSeparationRequest;
import com.divalhr.core.people.api.SeparationRequest;
import com.divalhr.core.people.api.SeparationResponses.AccessPreview;
import com.divalhr.core.people.api.SeparationResponses.AccessState;
import com.divalhr.core.people.api.SeparationResponses.AccessStatus;
import com.divalhr.core.people.api.SeparationResponses.Blocker;
import com.divalhr.core.people.api.SeparationResponses.BlockerResolution;
import com.divalhr.core.people.api.SeparationResponses.ChecklistItem;
import com.divalhr.core.people.api.SeparationResponses.EmployeeRef;
import com.divalhr.core.people.api.SeparationResponses.Report;
import com.divalhr.core.people.api.SeparationResponses.ReportInterval;
import com.divalhr.core.people.api.SeparationResponses.Separation;
import com.divalhr.core.people.api.SeparationResponses.SeparationCancellationPreview;
import com.divalhr.core.people.api.SeparationResponses.SeparationList;
import com.divalhr.core.people.api.SeparationResponses.SeparationPreview;
import com.divalhr.core.people.api.SeparationResponses.SeparationResult;
import com.divalhr.core.people.api.SeparationResponses.Task;
import com.divalhr.core.people.api.UpdateSeparationTaskRequest;
import com.divalhr.core.people.domain.history.Assignment;
import com.divalhr.core.people.domain.history.AssignmentKind;
import com.divalhr.core.people.domain.history.ChangeReason;
import com.divalhr.core.people.domain.history.ChangeTiming;
import com.divalhr.core.people.domain.history.ChangeType;
import com.divalhr.core.people.domain.history.NewAssignment;
import com.divalhr.core.people.domain.history.TimelinePlan;
import com.divalhr.core.people.domain.separation.AccessTiming;
import com.divalhr.core.people.domain.separation.Acknowledgement;
import com.divalhr.core.people.domain.separation.ReportAction;
import com.divalhr.core.people.domain.separation.SeparationPlanner;
import com.divalhr.core.people.domain.separation.SeparationPlanner.ReportIntervals;
import com.divalhr.core.people.domain.separation.SeparationRuleException;
import com.divalhr.core.people.domain.separation.SeparationState;
import com.divalhr.core.people.domain.separation.TaskCode;
import com.divalhr.core.people.domain.separation.TaskStatus;
import com.divalhr.core.people.internal.ConstraintViolations;
import com.divalhr.core.people.internal.JdbcEmploymentHistoryRepository;
import com.divalhr.core.people.internal.JdbcEmploymentHistoryRepository.ChangeRecord;
import com.divalhr.core.people.internal.JdbcEmploymentHistoryRepository.EmployeeRecord;
import com.divalhr.core.people.internal.JdbcEmploymentHistoryRepository.EmploymentRecord;
import com.divalhr.core.people.internal.JdbcSeparationRepository;
import com.divalhr.core.people.internal.JdbcSeparationRepository.BoundChange;
import com.divalhr.core.people.internal.JdbcSeparationRepository.SeparationRecord;
import com.divalhr.core.people.internal.JdbcSeparationRepository.TaskRecord;
import com.divalhr.core.people.internal.TransactionLimits;
import com.divalhr.core.platform.access.EmployeeAccessLinks;
import com.divalhr.core.platform.access.EmployeeAccessLinks.CancelOutcome;
import com.divalhr.core.platform.access.EmployeeAccessLinks.Link;
import com.divalhr.core.platform.access.EmployeeAccessLinks.RetryOutcome;
import com.divalhr.core.platform.access.EmployeeAccessLinks.Revocation;
import com.divalhr.core.platform.access.EmployeeAccessLinks.RevocationRequest;
import com.divalhr.core.platform.access.EmployeeAccessLinks.RevocationState;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * MVP-022: previews, records and cancels separations; lists them; updates their follow-up tasks and
 * re-queues a sign-in removal that needs intervention.
 *
 * <p>Transaction boundary (A22-4): this service is the only coordinator of a separation and of its
 * cancellation. One bounded transaction, after the idempotency key is reserved, takes the locks in
 * the global order of ADR 0008: (1) the tenant's manager-graph lock, (2) the employment rows in
 * ascending ID order (the separated employee's and every affected report's), (3)-(4) through the
 * identity port, which joins this transaction, the tenant's access-link lock, the link, the
 * membership and the revocation rows, (5) the separation and task rows; then the deferred database
 * checks, audit and outbox. Nothing is deleted and nothing partial is ever committed.
 */
@Service
public class EmployeeSeparationService {

  /** Preview operation. */
  public static final String PREVIEW = "employee-separation.preview";

  /** Commit operation. */
  public static final String CREATE = "employee-separation.create";

  /** List operation. */
  public static final String READ = "employee-separation.read";

  /** Cancellation preview operation. */
  public static final String CANCEL_PREVIEW = "employee-separation.cancel-preview";

  /** Cancellation operation (and its audit action). */
  public static final String CANCEL = "employee-separation.cancel";

  /** Task status operation. */
  public static final String TASK_UPDATE = "separation-task.update";

  /** Sign-in removal retry operation. */
  public static final String RETRY = "access-revocation.retry";

  /** Audit action of a recorded separation. */
  static final String RECORD = "employee-separation.record";

  /** Future limit of a last day (D22-2). */
  static final int FUTURE_DAYS = 180;

  /** Safety limit of affected direct-report intervals (D22-10, A22-2). */
  static final int MAX_INTERVALS = 200;

  private static final IdempotentOperation.Spec CREATE_SPEC =
      new IdempotentOperation.Spec(CREATE, "employee_separation", "create", "created", 201);
  private static final IdempotentOperation.Spec CANCEL_SPEC =
      new IdempotentOperation.Spec(CANCEL, "employee_separation", "cancel", "cancelled", 200);
  private static final IdempotentOperation.Spec TASK_SPEC =
      new IdempotentOperation.Spec(TASK_UPDATE, "separation_task", "update", "updated", 200);
  private static final IdempotentOperation.Spec RETRY_SPEC =
      new IdempotentOperation.Spec(RETRY, "access_revocation", "retry", "requeued", 200);

  private final JdbcEmploymentHistoryRepository history;
  private final JdbcSeparationRepository separations;
  private final EmploymentHistoryRules rules;
  private final EmploymentHistoryViews views;
  private final EmploymentHistoryEvents historyEvents;
  private final SeparationEvents events;
  private final BusinessCalendar calendar;
  private final EmployeeDirectoryService directory;
  private final EmployeeAccessLinks links;
  private final IdempotentOperation operations;
  private final TransactionLimits limits;
  private final EmploymentHistoryProperties properties;

  /**
   * Creates the service.
   *
   * @param history history repository
   * @param separations separation repository
   * @param rules manager rules
   * @param views response builder
   * @param historyEvents audit and outbox of employment changes
   * @param events audit and outbox of separations
   * @param calendar business date
   * @param directory fail-closed disclosure
   * @param links the identity port (joins this module's transactions)
   * @param operations idempotent operation flow
   * @param limits transaction limits
   * @param properties settings
   */
  public EmployeeSeparationService(
      JdbcEmploymentHistoryRepository history,
      JdbcSeparationRepository separations,
      EmploymentHistoryRules rules,
      EmploymentHistoryViews views,
      EmploymentHistoryEvents historyEvents,
      SeparationEvents events,
      BusinessCalendar calendar,
      EmployeeDirectoryService directory,
      EmployeeAccessLinks links,
      IdempotentOperation operations,
      TransactionLimits limits,
      EmploymentHistoryProperties properties) {
    this.history = history;
    this.separations = separations;
    this.rules = rules;
    this.views = views;
    this.historyEvents = historyEvents;
    this.events = events;
    this.calendar = calendar;
    this.directory = directory;
    this.links = links;
    this.operations = operations;
    this.limits = limits;
    this.properties = properties;
  }

  // ------------------------------------------------------------------------------------------
  // Planning
  // ------------------------------------------------------------------------------------------

  /** One separation-bound direct-report change. */
  private record BoundPlan(
      UUID changeId,
      UUID employeeId,
      UUID employmentId,
      LocalDate effectiveFrom,
      TimelinePlan plan) {}

  /** A computed separation. */
  private record Planned(
      EmploymentRecord employment,
      LocalDate today,
      ChangeTiming timing,
      SeparationPlanner.Own own,
      List<ReportIntervals> reports,
      int intervalCount,
      List<BoundPlan> bound,
      Optional<Link> link,
      AccessStatus access,
      Instant accessEndsAt,
      Set<Acknowledgement> required,
      String digest) {}

  private Planned plan(
      TenantId tenant,
      UUID employeeId,
      SeparationCommand command,
      EmploymentRecord employment,
      Optional<Link> link) {
    LocalDate today = calendar.today(tenant);
    LocalDate lastDay = command.lastDay();
    if (employment.end() != null) {
      throw new ApiException(ErrorCode.SEPARATION_EXISTS, Map.of());
    }
    if (lastDay.isBefore(employment.start())
        || lastDay.isBefore(today.minusDays(properties.retroactiveDays()))
        || lastDay.isAfter(today.plusDays(FUTURE_DAYS))) {
      throw new ApiException(ErrorCode.SEPARATION_DATE_OUT_OF_RANGE, Map.of());
    }
    // A22-1: immediate removal only when the last day is today or earlier; a retroactive
    // separation removes access at once, explicitly.
    if ((command.accessTiming() == AccessTiming.IMMEDIATELY && lastDay.isAfter(today))
        || (command.accessTiming() == AccessTiming.END_OF_LAST_DAY && lastDay.isBefore(today))) {
      throw new ApiException(ErrorCode.SEPARATION_ACCESS_TIMING_INVALID, Map.of());
    }
    ChangeTiming timing = ChangeTiming.of(lastDay, today);
    List<Assignment> rows = history.assignments(tenant, employment.id());
    SeparationPlanner.Own own = SeparationPlanner.own(rows, lastDay, today, UUID::randomUUID);

    // Direct reports (A22-2): every interval of every report naming the employee after D.
    List<ReportIntervals> reports =
        SeparationPlanner.intervals(separations.reportRows(tenant, employeeId, lastDay), lastDay);
    int intervalCount = SeparationPlanner.intervalCount(reports);
    if (SeparationPlanner.tooManyIntervals(reports, MAX_INTERVALS)) {
      throw new ApiException(
          ErrorCode.SEPARATION_TOO_MANY_INTERVALS, Map.of("count", intervalCount));
    }
    List<BoundPlan> bound = new ArrayList<>();
    if (intervalCount > 0 && command.reportAction() != null) {
      UUID replacement =
          command.reportAction() == ReportAction.REASSIGN ? command.replacementManagerId() : null;
      if (employeeId.equals(replacement)) {
        throw new ApiException(ErrorCode.MANAGER_INVALID, Map.of("reason", "NOT_EMPLOYED"));
      }
      for (ReportIntervals report : reports) {
        for (Assignment row : report.rows()) {
          UUID changeId = UUID.randomUUID();
          TimelinePlan rowPlan =
              SeparationPlanner.reportRow(changeId, row, lastDay, replacement, UUID::randomUUID);
          for (NewAssignment created : rowPlan.created()) {
            if (created.origin().equals(changeId)) {
              rules.checkManager(
                  tenant, report.employeeId(), replacement, created.from(), created.to());
            }
          }
          bound.add(
              new BoundPlan(
                  changeId,
                  report.employeeId(),
                  report.employmentId(),
                  SeparationPlanner.reportChangeDate(row, lastDay),
                  rowPlan));
        }
      }
    }

    AccessStatus access;
    if (link.isEmpty()) {
      access = AccessStatus.NOT_LINKED;
    } else if (link.get().self()) {
      access = AccessStatus.SELF;
    } else if (link.get().tenantAdmin()) {
      access = AccessStatus.PROTECTED_ADMIN;
    } else {
      access = AccessStatus.LINKED;
    }
    Instant accessEndsAt = null;
    if (link.isPresent()) {
      accessEndsAt =
          command.accessTiming() == AccessTiming.IMMEDIATELY
              ? calendar.now().truncatedTo(ChronoUnit.MICROS)
              : calendar.startOf(tenant, lastDay.plusDays(1));
    }
    Set<Acknowledgement> required = EnumSet.noneOf(Acknowledgement.class);
    if (timing == ChangeTiming.RETROACTIVE) {
      required.add(Acknowledgement.RETROACTIVE);
    }
    if (link.isEmpty()) {
      required.add(Acknowledgement.NO_LINKED_ACCESS);
    } else if (command.accessTiming() == AccessTiming.IMMEDIATELY) {
      required.add(Acknowledgement.IMMEDIATE_ACCESS_REMOVAL);
    }

    SeparationDigests.Text text =
        new SeparationDigests.Text(
                "DIVALHR-SEPARATION-PREVIEW", employment.id(), employment.version())
            .line("command", command.canonical())
            .line("timing", timing.name())
            .line(
                "accessEndsAt",
                link.isEmpty()
                    ? "-"
                    : command.accessTiming() == AccessTiming.IMMEDIATELY
                        ? "commit"
                        : accessEndsAt.toString())
            .line(
                "access",
                access.name()
                    + ";link="
                    + link.map(l -> l.linkId().toString()).orElse("-")
                    + ";linkVersion="
                    + link.map(l -> Long.toString(l.version())).orElse("-"))
            .plan("own", own.plan());
    own.blockers().forEach(row -> text.line("blocker", row.id()));
    for (ReportIntervals report : reports) {
      text.line(
          "report",
          report.employeeId()
              + ";"
              + report.employmentId()
              + ";intervals="
              + report.intervals().size());
      report.intervals().forEach(interval -> text.rows("interval", interval));
    }
    text.line("intervals", intervalCount);
    for (TaskCode code : TaskCode.values()) {
      text.line("checklist", code.name() + ";" + lastDay);
    }
    return new Planned(
        employment,
        today,
        timing,
        own,
        reports,
        intervalCount,
        bound,
        link,
        access,
        accessEndsAt,
        required,
        text.sha256());
  }

  // ------------------------------------------------------------------------------------------
  // Preview and commit
  // ------------------------------------------------------------------------------------------

  /**
   * Previews a separation. Writes nothing but the disclosure audit record.
   *
   * @param caller verified caller
   * @param employeeId raw path value
   * @param request body
   * @return the preview
   */
  public SeparationPreview preview(
      PeopleCaller caller, String employeeId, SeparationRequest request) {
    UUID id = directory.validated(PREVIEW, () -> EmployeeDirectoryService.employeeId(employeeId));
    SeparationCommand command =
        directory.validated(
            PREVIEW,
            () -> {
              FieldErrors errors = new FieldErrors();
              SeparationCommand parsed = SeparationCommand.parse(request, errors);
              errors.throwIfAny();
              return parsed;
            });
    TenantId tenant = caller.tenant();
    return directory.disclose(
        caller,
        PREVIEW,
        "separation-preview",
        "first",
        () -> {
          EmploymentRecord employment = locate(tenant, id, command.lastDay(), false);
          Planned planned =
              plan(tenant, id, command, employment, links.activeLink(tenant, id, caller.subject()));
          SeparationPreview preview = previewBody(tenant, command, planned);
          List<UUID> disclosed = new ArrayList<>();
          planned.own().plan().superseded().forEach(r -> disclosed.add(r.id()));
          planned.reports().forEach(r -> disclosed.add(r.employeeId()));
          return new EmployeeDirectoryService.Disclosure<>(
              preview, "employee", id, disclosed.size(), disclosed);
        });
  }

  private SeparationPreview previewBody(
      TenantId tenant, SeparationCommand command, Planned planned) {
    List<Blocker> blockers = new ArrayList<>();
    for (Assignment row : planned.own().blockers()) {
      Optional<UUID> cancellable = cancellableChange(tenant, planned, row);
      blockers.add(
          new Blocker(
              row.kind(),
              row.from(),
              cancellable.isPresent()
                  ? BlockerResolution.CANCEL_CHANGE
                  : BlockerResolution.NOT_CANCELLABLE,
              cancellable.orElse(null)));
    }
    Set<UUID> reportIds = new TreeSet<>();
    planned.reports().forEach(r -> reportIds.add(r.employeeId()));
    Map<UUID, EmployeeRecord> people = history.employees(tenant, reportIds);
    List<Report> reports = new ArrayList<>();
    for (ReportIntervals report : planned.reports()) {
      EmployeeRecord person = people.get(report.employeeId());
      List<ReportInterval> intervals = new ArrayList<>();
      for (List<Assignment> interval : report.intervals()) {
        LocalDate from = interval.get(0).from();
        LocalDate start = from.isAfter(command.lastDay()) ? from : command.lastDay().plusDays(1);
        intervals.add(new ReportInterval(start, interval.get(interval.size() - 1).to()));
      }
      reports.add(
          new Report(
              new EmployeeRef(
                  report.employeeId(),
                  person == null ? "" : person.employeeNumber(),
                  person == null ? "" : person.givenNames(),
                  person == null ? "" : person.familyName()),
              intervals));
    }
    List<ChecklistItem> checklist = new ArrayList<>();
    for (TaskCode code : TaskCode.values()) {
      checklist.add(new ChecklistItem(code, command.lastDay()));
    }
    return new SeparationPreview(
        planned.employment().id(),
        planned.employment().version(),
        planned.today(),
        command.lastDay(),
        planned.timing(),
        command.accessTiming(),
        views.periods(tenant, planned.own().plan()),
        blockers,
        reports,
        planned.intervalCount() > 0 && command.reportAction() == null,
        new AccessPreview(planned.access(), planned.accessEndsAt()),
        checklist,
        List.copyOf(planned.required()),
        planned.digest());
  }

  /** The change to cancel next for a blocker (A22-3), when one can be cancelled. */
  private Optional<UUID> cancellableChange(TenantId tenant, Planned planned, Assignment row) {
    for (UUID candidate : List.of(row.createdBy(), row.origin())) {
      Optional<ChangeRecord> change =
          history.change(tenant, planned.employment().employeeId(), candidate);
      if (change.isPresent()
          && change.get().type() == ChangeType.CHANGE
          && !change.get().cancelled()
          && change.get().reason() != ChangeReason.MANAGER_SEPARATED
          && change.get().effectiveFrom().isAfter(planned.today())) {
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
  }

  /** A validated commit. */
  private record Commit(SeparationCommand command, SeparationCommand.Confirmation confirmation) {}

  /**
   * Records a previewed separation, or replays an identical earlier one.
   *
   * @param caller verified caller
   * @param employeeId raw path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param request body
   * @return the separation and whether it was replayed
   */
  public IdempotentOperation.Result<SeparationResult> create(
      PeopleCaller caller,
      String employeeId,
      String idempotencyKey,
      CreateSeparationRequest request) {
    UUID id = EmployeeDirectoryService.employeeId(employeeId);
    Commit commit =
        operations.validated(
            CREATE_SPEC,
            () -> {
              FieldErrors errors = new FieldErrors();
              EmployeeImportService.requireKey(errors, idempotencyKey);
              SeparationCommand command = SeparationCommand.parse(request, errors);
              SeparationCommand.Confirmation confirmation =
                  SeparationCommand.confirmation(request, errors);
              errors.throwIfAny();
              return new Commit(command, confirmation);
            });
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("tenantId", caller.tenant().toString());
    canonical.put("employeeId", id.toString());
    canonical.put("command", commit.command().canonical());
    canonical.putAll(commit.confirmation().canonical());
    return bounded(
        () ->
            operations.execute(
                CREATE_SPEC,
                caller.subject(),
                idempotencyKey,
                canonical,
                SeparationResult.class,
                properties.transactionTimeout(),
                () -> createInTransaction(caller, id, commit)));
  }

  private IdempotentOperation.Completed<SeparationResult> createInTransaction(
      PeopleCaller caller, UUID employeeId, Commit commit) {
    TenantId tenant = caller.tenant();
    SeparationCommand command = commit.command();
    limits.apply(properties.statementTimeout());
    // (1) The tenant's manager-graph lock: direct-report rows cannot change while it is held.
    history.lockManagerGraph(tenant);
    EmploymentRecord unlocked = locate(tenant, employeeId, command.lastDay(), false);
    // (2) Employment rows in ascending ID order: the employee's and every affected report's.
    Set<UUID> employments = new TreeSet<>();
    employments.add(unlocked.id());
    separations
        .reportRows(tenant, employeeId, command.lastDay())
        .forEach(r -> employments.add(r.employmentId()));
    Map<UUID, EmploymentRecord> locked = lockEmployments(tenant, employments);
    EmploymentRecord employment = locked.get(unlocked.id());
    if (employment.version() != commit.confirmation().expectedVersion()) {
      throw new ApiException(ErrorCode.EMPLOYMENT_VERSION_CONFLICT, Map.of());
    }
    // (3)-(4) The access-link lock, the link and its membership, through the identity port.
    Optional<Link> link = links.lockForSeparation(tenant, employeeId, caller.subject());
    Planned planned = plan(tenant, employeeId, command, employment, link);
    if (!planned.digest().equals(commit.confirmation().previewDigest())) {
      throw new ApiException(ErrorCode.SEPARATION_PREVIEW_CHANGED, Map.of());
    }
    if (!planned.own().blockers().isEmpty()) {
      throw new ApiException(
          ErrorCode.SEPARATION_FUTURE_CHANGES, Map.of("count", planned.own().blockers().size()));
    }
    if (planned.access() == AccessStatus.SELF || planned.access() == AccessStatus.PROTECTED_ADMIN) {
      throw new ApiException(
          ErrorCode.SEPARATION_PROTECTED,
          Map.of("reason", planned.access() == AccessStatus.SELF ? "SELF" : "ADMIN_ACCESS"));
    }
    if (planned.intervalCount() > 0 && command.reportAction() == null) {
      throw new ApiException(ErrorCode.SEPARATION_REPORT_PLAN_REQUIRED, Map.of());
    }
    for (Acknowledgement acknowledgement : planned.required()) {
      if (!commit.confirmation().acknowledgements().contains(acknowledgement)) {
        throw new ApiException(
            ErrorCode.SEPARATION_ACKNOWLEDGEMENT_REQUIRED,
            Map.of("acknowledgement", acknowledgement.name()));
      }
    }

    Instant now = calendar.now().truncatedTo(ChronoUnit.MICROS);
    UUID separationId = UUID.randomUUID();
    UUID changeId = UUID.randomUUID();
    long version = employment.version() + 1;
    LocalDate lastDay = command.lastDay();
    TimelinePlan own = planned.own().plan();
    boolean reassign = command.reportAction() != null && planned.intervalCount() > 0;
    int reportCount =
        (int) planned.reports().stream().map(ReportIntervals::employeeId).distinct().count();
    SeparationState state;
    Map<UUID, Long> changeVersions = new HashMap<>();
    try {
      history.insertChange(
          tenant,
          new ChangeRecord(
              changeId,
              employeeId,
              employment.id(),
              ChangeType.SEPARATION,
              lastDay.plusDays(1),
              own.kinds(),
              null,
              ChangeTiming.of(lastDay.plusDays(1), planned.today()),
              null,
              false,
              now,
              version),
          caller.subject(),
          separationId);
      state =
          separations.insert(
              tenant,
              new SeparationRecord(
                  separationId,
                  employeeId,
                  employment.id(),
                  changeId,
                  lastDay,
                  command.reason(),
                  command.accessTiming(),
                  reassign ? command.reportAction() : null,
                  reassign ? command.replacementManagerId() : null,
                  reassign ? reportCount : 0,
                  reassign ? planned.intervalCount() : 0,
                  SeparationState.SCHEDULED,
                  calendar.startOf(tenant, lastDay.plusDays(1)),
                  now,
                  null,
                  0,
                  tenant),
              caller.subject());
      supersede(tenant, own, changeId, now);
      history.insertAssignments(tenant, employment, changeId, own.created());
      history.setEnd(tenant, employment.id(), lastDay, version);

      // A22-2: one separation-bound change per affected direct-report row.
      Map<UUID, Long> reportVersions = new HashMap<>();
      for (BoundPlan change : planned.bound()) {
        EmploymentRecord reportEmployment = locked.get(change.employmentId());
        long reportVersion =
            reportVersions.getOrDefault(change.employmentId(), reportEmployment.version()) + 1;
        reportVersions.put(change.employmentId(), reportVersion);
        changeVersions.put(change.changeId(), reportVersion);
        history.insertChange(
            tenant,
            new ChangeRecord(
                change.changeId(),
                change.employeeId(),
                change.employmentId(),
                ChangeType.CHANGE,
                change.effectiveFrom(),
                Set.of(AssignmentKind.MANAGER),
                ChangeReason.MANAGER_SEPARATED,
                ChangeTiming.of(change.effectiveFrom(), planned.today()),
                null,
                false,
                now,
                reportVersion),
            caller.subject(),
            separationId);
        supersede(tenant, change.plan(), change.changeId(), now);
        history.insertAssignments(
            tenant, reportEmployment, change.changeId(), change.plan().created());
      }
      reportVersions.forEach((employmentId, v) -> history.setVersion(tenant, employmentId, v));

      separations.insertTasks(tenant, separationId, employeeId, lastDay, now, caller.subject());
      if (link.isPresent()) {
        links.scheduleRevocation(
            tenant,
            new RevocationRequest(
                separationId,
                employeeId,
                link.get().linkId(),
                link.get().membershipId(),
                planned.accessEndsAt(),
                caller.subject(),
                caller.correlationId()));
      }
      history.checkDeferred(tenant);
    } catch (DataAccessException violated) {
      throw mapped(violated);
    }

    for (BoundPlan change : planned.bound()) {
      historyEvents.recorded(
          tenant,
          caller.subject(),
          change.effectiveFrom().isAfter(planned.today())
              ? EmploymentChangeService.SCHEDULE
              : EmploymentChangeService.APPLY,
          EmploymentHistoryEvents.CHANGED,
          new EmploymentHistoryEvents.Write(
              change.employeeId(),
              change.employmentId(),
              change.changeId(),
              change.changeId(),
              change.plan().superseded().size(),
              change.plan().created().size(),
              changeVersions.get(change.changeId()),
              SeparationDigests.identifiers(
                  "bound-change",
                  change.changeId(),
                  separationId.toString(),
                  Long.toString(changeVersions.get(change.changeId())))),
          now,
          caller.correlationId());
    }
    events.written(
        tenant,
        caller.subject(),
        RECORD,
        SeparationEvents.RECORDED,
        new SeparationEvents.Write(
            separationId,
            employeeId,
            employment.id(),
            state.name(),
            reassign ? reportCount : 0,
            reassign ? planned.intervalCount() : 0,
            own.superseded().size(),
            own.created().size(),
            version,
            planned.digest()),
        now,
        caller.correlationId());
    SeparationRecord recorded =
        separations
            .find(tenant, employeeId, separationId)
            .orElseThrow(() -> new IllegalStateException("separation vanished"));
    return new IdempotentOperation.Completed<>(
        new SeparationResult(view(tenant, recorded), version), separationId, Outcome.CREATED);
  }

  // ------------------------------------------------------------------------------------------
  // Cancellation
  // ------------------------------------------------------------------------------------------

  /** A computed cancellation. */
  private record PlannedCancellation(
      EmploymentRecord employment,
      ChangeRecord separationChange,
      TimelinePlan own,
      List<BoundChange> reportChanges,
      Map<UUID, TimelinePlan> reportPlans,
      int reportCount,
      String digest) {}

  private PlannedCancellation planCancellation(
      TenantId tenant, SeparationRecord separation, EmploymentRecord employment) {
    if (separation.state() != SeparationState.SCHEDULED
        || !separation.effectiveAt().isAfter(calendar.now())) {
      throw notCancellable("NOT_SCHEDULED");
    }
    Revocation revocation =
        links.revocations(tenant, List.of(separation.id())).get(separation.id());
    if (revocation != null
        && revocation.state() != RevocationState.SCHEDULED
        && revocation.state() != RevocationState.CANCELLED) {
      throw notCancellable("ACCESS_ALREADY_REVOKED");
    }
    ChangeRecord separationChange =
        history
            .change(tenant, separation.employeeId(), separation.changeId())
            .orElseThrow(() -> new IllegalStateException("separation without its change"));
    Map<UUID, List<Assignment>> rowsByEmployment = new HashMap<>();
    TimelinePlan own;
    Map<UUID, TimelinePlan> reportPlans = new LinkedHashMap<>();
    List<BoundChange> reportChanges = new ArrayList<>();
    try {
      own =
          SeparationPlanner.reversal(
              separation.changeId(),
              rowsByEmployment.computeIfAbsent(
                  employment.id(), e -> history.assignments(tenant, e)),
              UUID::randomUUID);
      for (BoundChange change : separations.boundChanges(tenant, separation.id())) {
        if (change.separation()) {
          continue;
        }
        reportChanges.add(change);
        reportPlans.put(
            change.id(),
            SeparationPlanner.reversal(
                change.id(),
                rowsByEmployment.computeIfAbsent(
                    change.employmentId(), e -> history.assignments(tenant, e)),
                UUID::randomUUID));
      }
    } catch (SeparationRuleException changed) {
      throw notCancellable("HISTORY_CHANGED_SINCE");
    }
    SeparationDigests.Text text =
        new SeparationDigests.Text(
                "DIVALHR-SEPARATION-CANCELLATION-PREVIEW", employment.id(), employment.version())
            .line("separation", separation.id())
            .plan("own", own);
    for (BoundChange change : reportChanges) {
      text.line("cancels", change.id()).plan("report", reportPlans.get(change.id()));
    }
    int reportCount = (int) reportChanges.stream().map(BoundChange::employeeId).distinct().count();
    return new PlannedCancellation(
        employment, separationChange, own, reportChanges, reportPlans, reportCount, text.sha256());
  }

  /**
   * Previews the cancellation of a scheduled separation. Writes nothing but the disclosure audit.
   *
   * @param caller verified caller
   * @param employeeId raw path value
   * @param separationId raw path value
   * @return the preview
   */
  public SeparationCancellationPreview previewCancellation(
      PeopleCaller caller, String employeeId, String separationId) {
    UUID id =
        directory.validated(CANCEL_PREVIEW, () -> EmployeeDirectoryService.employeeId(employeeId));
    UUID sid = directory.validated(CANCEL_PREVIEW, () -> separationId(separationId));
    TenantId tenant = caller.tenant();
    return directory.disclose(
        caller,
        CANCEL_PREVIEW,
        "separation-cancel-preview",
        "first",
        () -> {
          history.employee(tenant, id).orElseThrow(EmployeeDirectoryService::notFound);
          SeparationRecord separation = separation(tenant, id, sid);
          EmploymentRecord employment =
              history
                  .employmentById(tenant, separation.employmentId())
                  .orElseThrow(() -> new IllegalStateException("separation without employment"));
          PlannedCancellation planned = planCancellation(tenant, separation, employment);
          SeparationCancellationPreview preview =
              new SeparationCancellationPreview(
                  employment.version(),
                  views.periods(tenant, planned.own()),
                  planned.reportCount(),
                  planned.reportChanges().size(),
                  planned.digest());
          return new EmployeeDirectoryService.Disclosure<>(
              preview,
              "employee",
              id,
              planned.own().superseded().size(),
              planned.own().superseded().stream().map(Assignment::id).toList());
        });
  }

  /**
   * Cancels a scheduled separation as previewed, or replays an identical earlier cancellation.
   *
   * @param caller verified caller
   * @param employeeId raw path value
   * @param separationId raw path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param request body
   * @return the separation and whether it was replayed
   */
  public IdempotentOperation.Result<SeparationResult> cancel(
      PeopleCaller caller,
      String employeeId,
      String separationId,
      String idempotencyKey,
      CancelSeparationRequest request) {
    UUID id = EmployeeDirectoryService.employeeId(employeeId);
    UUID sid = separationId(separationId);
    SeparationCommand.CancellationConfirmation confirmation =
        operations.validated(
            CANCEL_SPEC,
            () -> {
              FieldErrors errors = new FieldErrors();
              EmployeeImportService.requireKey(errors, idempotencyKey);
              SeparationCommand.CancellationConfirmation parsed =
                  SeparationCommand.cancellation(request, errors);
              errors.throwIfAny();
              return parsed;
            });
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("tenantId", caller.tenant().toString());
    canonical.put("employeeId", id.toString());
    canonical.put("separationId", sid.toString());
    canonical.put("expectedVersion", confirmation.expectedVersion());
    canonical.put("cancellationDigest", confirmation.cancellationDigest());
    return bounded(
        () ->
            operations.execute(
                CANCEL_SPEC,
                caller.subject(),
                idempotencyKey,
                canonical,
                SeparationResult.class,
                properties.transactionTimeout(),
                () -> cancelInTransaction(caller, id, sid, confirmation)));
  }

  private IdempotentOperation.Completed<SeparationResult> cancelInTransaction(
      PeopleCaller caller,
      UUID employeeId,
      UUID separationId,
      SeparationCommand.CancellationConfirmation confirmation) {
    TenantId tenant = caller.tenant();
    limits.apply(properties.statementTimeout());
    history.employee(tenant, employeeId).orElseThrow(EmployeeDirectoryService::notFound);
    SeparationRecord unlocked = separation(tenant, employeeId, separationId);
    // (1) manager graph, (2) employments in ascending ID order.
    history.lockManagerGraph(tenant);
    Set<UUID> employments = new TreeSet<>();
    employments.add(unlocked.employmentId());
    separations.boundChanges(tenant, separationId).forEach(c -> employments.add(c.employmentId()));
    Map<UUID, EmploymentRecord> locked = lockEmployments(tenant, employments);
    EmploymentRecord employment = locked.get(unlocked.employmentId());
    if (employment.version() != confirmation.expectedVersion()) {
      throw new ApiException(ErrorCode.EMPLOYMENT_VERSION_CONFLICT, Map.of());
    }
    // (3)-(4) access-link lock and link, then the revocation (cancelled below).
    links.lockForSeparation(tenant, employeeId, caller.subject());
    // (5) the separation row, re-read under its lock.
    SeparationRecord separation =
        separations
            .lock(tenant, employeeId, separationId)
            .orElseThrow(() -> new ApiException(ErrorCode.SEPARATION_NOT_FOUND, Map.of()));
    PlannedCancellation planned = planCancellation(tenant, separation, employment);
    if (!planned.digest().equals(confirmation.cancellationDigest())) {
      throw new ApiException(ErrorCode.SEPARATION_PREVIEW_CHANGED, Map.of());
    }
    Instant now = calendar.now().truncatedTo(ChronoUnit.MICROS);
    long version = employment.version() + 1;
    try {
      if (links.cancelRevocation(tenant, separationId, caller.subject(), caller.correlationId())
          == CancelOutcome.ALREADY_EFFECTIVE) {
        throw notCancellable("ACCESS_ALREADY_REVOKED");
      }
      Map<UUID, Long> reportVersions = new HashMap<>();
      for (BoundChange change : planned.reportChanges()) {
        EmploymentRecord reportEmployment = locked.get(change.employmentId());
        long reportVersion =
            reportVersions.getOrDefault(change.employmentId(), reportEmployment.version()) + 1;
        reportVersions.put(change.employmentId(), reportVersion);
        reverse(
            tenant,
            caller.subject(),
            change.id(),
            change.employeeId(),
            reportEmployment,
            change.effectiveFrom(),
            Set.of(AssignmentKind.MANAGER),
            planned.reportPlans().get(change.id()),
            separationId,
            reportVersion,
            now);
      }
      reportVersions.forEach((employmentId, v) -> history.setVersion(tenant, employmentId, v));
      reverse(
          tenant,
          caller.subject(),
          planned.separationChange().id(),
          employeeId,
          employment,
          planned.separationChange().effectiveFrom(),
          planned.separationChange().kinds(),
          planned.own(),
          separationId,
          version,
          now);
      if (separations.cancel(tenant, separationId, separation.version(), caller.subject(), now)
          != 1) {
        throw notCancellable("NOT_SCHEDULED");
      }
      separations.closeTasks(tenant, separationId, now, caller.subject());
      history.setEnd(tenant, employment.id(), null, version);
      history.checkDeferred(tenant);
    } catch (DataAccessException violated) {
      throw mapped(violated);
    }
    events.written(
        tenant,
        caller.subject(),
        CANCEL,
        SeparationEvents.CANCELLED,
        new SeparationEvents.Write(
            separationId,
            employeeId,
            employment.id(),
            SeparationState.CANCELLED.name(),
            planned.reportCount(),
            planned.reportChanges().size(),
            planned.own().superseded().size(),
            planned.own().created().size(),
            version,
            planned.digest()),
        now,
        caller.correlationId());
    SeparationRecord cancelled =
        separations
            .find(tenant, employeeId, separationId)
            .orElseThrow(() -> new IllegalStateException("separation vanished"));
    return new IdempotentOperation.Completed<>(
        new SeparationResult(view(tenant, cancelled), version), separationId, Outcome.UPDATED);
  }

  /** Records the exact reversal of one change. */
  private void reverse(
      TenantId tenant,
      String actor,
      UUID cancelledId,
      UUID employeeId,
      EmploymentRecord employment,
      LocalDate effectiveFrom,
      Set<AssignmentKind> kinds,
      TimelinePlan plan,
      UUID separationId,
      long versionAfter,
      Instant now) {
    UUID cancellationId = UUID.randomUUID();
    history.insertChange(
        tenant,
        new ChangeRecord(
            cancellationId,
            employeeId,
            employment.id(),
            ChangeType.CANCELLATION,
            effectiveFrom,
            kinds,
            null,
            ChangeTiming.SCHEDULED,
            cancelledId,
            false,
            now,
            versionAfter),
        actor,
        separationId);
    history.markCancelled(tenant, cancelledId);
    supersede(tenant, plan, cancellationId, now);
    history.insertAssignments(tenant, employment, cancellationId, plan.created());
  }

  // ------------------------------------------------------------------------------------------
  // Reads, tasks and retry
  // ------------------------------------------------------------------------------------------

  /**
   * An employee's separations, newest first.
   *
   * @param caller verified caller
   * @param employeeId raw path value
   * @return the list, after its disclosure audit committed
   */
  public SeparationList list(PeopleCaller caller, String employeeId) {
    UUID id = directory.validated(READ, () -> EmployeeDirectoryService.employeeId(employeeId));
    TenantId tenant = caller.tenant();
    return directory.disclose(
        caller,
        READ,
        "separations",
        "first",
        () -> {
          history.employee(tenant, id).orElseThrow(EmployeeDirectoryService::notFound);
          List<SeparationRecord> found = separations.ofEmployee(tenant, id);
          SeparationList body = new SeparationList(views(tenant, found));
          return new EmployeeDirectoryService.Disclosure<>(
              body,
              "employee",
              id,
              found.size(),
              found.stream().map(SeparationRecord::id).toList());
        });
  }

  /**
   * Marks a follow-up task done, not applicable, or open again.
   *
   * @param caller verified caller
   * @param employeeId raw path value
   * @param separationId raw path value
   * @param taskId raw path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param request body
   * @return the task and whether it was replayed
   */
  public IdempotentOperation.Result<Task> updateTask(
      PeopleCaller caller,
      String employeeId,
      String separationId,
      String taskId,
      String idempotencyKey,
      UpdateSeparationTaskRequest request) {
    UUID id = EmployeeDirectoryService.employeeId(employeeId);
    UUID sid = separationId(separationId);
    UUID tid = taskId(taskId);
    SeparationCommand.TaskUpdate update =
        operations.validated(
            TASK_SPEC,
            () -> {
              FieldErrors errors = new FieldErrors();
              EmployeeImportService.requireKey(errors, idempotencyKey);
              SeparationCommand.TaskUpdate parsed = SeparationCommand.taskUpdate(request, errors);
              errors.throwIfAny();
              return parsed;
            });
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("tenantId", caller.tenant().toString());
    canonical.put("employeeId", id.toString());
    canonical.put("separationId", sid.toString());
    canonical.put("taskId", tid.toString());
    canonical.put("status", update.status().name());
    canonical.put("expectedVersion", update.expectedVersion());
    return bounded(
        () ->
            operations.execute(
                TASK_SPEC,
                caller.subject(),
                idempotencyKey,
                canonical,
                Task.class,
                properties.transactionTimeout(),
                () -> {
                  TenantId tenant = caller.tenant();
                  limits.apply(properties.statementTimeout());
                  history.employee(tenant, id).orElseThrow(EmployeeDirectoryService::notFound);
                  SeparationRecord separation =
                      separations
                          .lock(tenant, id, sid)
                          .orElseThrow(
                              () -> new ApiException(ErrorCode.SEPARATION_NOT_FOUND, Map.of()));
                  TaskRecord task =
                      separations
                          .lockTask(tenant, sid, tid)
                          .orElseThrow(
                              () ->
                                  new ApiException(ErrorCode.SEPARATION_TASK_NOT_FOUND, Map.of()));
                  if (separation.state() == SeparationState.CANCELLED
                      || task.status() == TaskStatus.CANCELLED) {
                    throw new ApiException(ErrorCode.SEPARATION_TASK_CLOSED, Map.of());
                  }
                  if (task.version() != update.expectedVersion()) {
                    throw new ApiException(ErrorCode.SEPARATION_TASK_VERSION_CONFLICT, Map.of());
                  }
                  if (task.status() == update.status()) {
                    return new IdempotentOperation.Completed<>(
                        task(task), task.id(), Outcome.UNCHANGED);
                  }
                  if (task.status() != TaskStatus.OPEN && update.status() != TaskStatus.OPEN) {
                    // DONE <-> NOT_APPLICABLE goes through OPEN (the V15 guard enforces it).
                    throw new ApiException(ErrorCode.SEPARATION_TASK_VERSION_CONFLICT, Map.of());
                  }
                  Instant now = calendar.now().truncatedTo(ChronoUnit.MICROS);
                  if (separations.setStatus(
                          tenant, tid, update.status(), task.version(), now, caller.subject())
                      != 1) {
                    throw new ApiException(ErrorCode.SEPARATION_TASK_VERSION_CONFLICT, Map.of());
                  }
                  events.taskUpdated(
                      tenant,
                      caller.subject(),
                      sid,
                      id,
                      tid,
                      task.status().name(),
                      update.status().name(),
                      task.version() + 1,
                      now,
                      caller.correlationId());
                  TaskRecord updated =
                      separations
                          .lockTask(tenant, sid, tid)
                          .orElseThrow(() -> new IllegalStateException("task vanished"));
                  return new IdempotentOperation.Completed<>(task(updated), tid, Outcome.UPDATED);
                }));
  }

  /**
   * Re-queues the sign-in removal of a separation that needs manual intervention.
   *
   * @param caller verified caller
   * @param employeeId raw path value
   * @param separationId raw path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @return the separation and whether it was replayed
   */
  public IdempotentOperation.Result<Separation> retry(
      PeopleCaller caller, String employeeId, String separationId, String idempotencyKey) {
    UUID id = EmployeeDirectoryService.employeeId(employeeId);
    UUID sid = separationId(separationId);
    operations.validated(
        RETRY_SPEC,
        () -> {
          FieldErrors errors = new FieldErrors();
          EmployeeImportService.requireKey(errors, idempotencyKey);
          errors.throwIfAny();
          return Boolean.TRUE;
        });
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("tenantId", caller.tenant().toString());
    canonical.put("employeeId", id.toString());
    canonical.put("separationId", sid.toString());
    return bounded(
        () ->
            operations.execute(
                RETRY_SPEC,
                caller.subject(),
                idempotencyKey,
                canonical,
                Separation.class,
                properties.transactionTimeout(),
                () -> {
                  TenantId tenant = caller.tenant();
                  limits.apply(properties.statementTimeout());
                  history.employee(tenant, id).orElseThrow(EmployeeDirectoryService::notFound);
                  SeparationRecord separation = separation(tenant, id, sid);
                  if (links.retry(tenant, sid, caller.subject(), caller.correlationId())
                      != RetryOutcome.QUEUED) {
                    throw new ApiException(ErrorCode.ACCESS_REVOCATION_NOT_RETRYABLE, Map.of());
                  }
                  return new IdempotentOperation.Completed<>(
                      view(tenant, separation), sid, Outcome.UPDATED);
                }));
  }

  // ------------------------------------------------------------------------------------------
  // Shared
  // ------------------------------------------------------------------------------------------

  private Separation view(TenantId tenant, SeparationRecord record) {
    return views(tenant, List.of(record)).get(0);
  }

  private List<Separation> views(TenantId tenant, List<SeparationRecord> records) {
    List<UUID> ids = records.stream().map(SeparationRecord::id).toList();
    Map<UUID, List<Task>> tasks = new HashMap<>();
    for (TaskRecord task : separations.tasks(tenant, ids)) {
      tasks.computeIfAbsent(task.separationId(), k -> new ArrayList<>()).add(task(task));
    }
    Map<UUID, Revocation> revocations = links.revocations(tenant, ids);
    Instant now = calendar.now();
    List<Separation> views = new ArrayList<>();
    for (SeparationRecord record : records) {
      Revocation revocation = revocations.get(record.id());
      AccessState access =
          revocation == null
              ? AccessState.NOT_LINKED
              : AccessState.valueOf(revocation.state().name());
      boolean cancellable =
          record.state() == SeparationState.SCHEDULED
              && record.effectiveAt().isAfter(now)
              && (revocation == null
                  || revocation.state() == RevocationState.SCHEDULED
                  || revocation.state() == RevocationState.CANCELLED);
      views.add(
          new Separation(
              record.id(),
              record.employmentId(),
              record.lastDay(),
              record.reason(),
              record.accessTiming(),
              record.reportAction(),
              record.reportCount(),
              record.intervalCount(),
              record.state(),
              record.effectiveAt(),
              record.recordedAt(),
              record.cancelledAt(),
              cancellable,
              access,
              revocation == null ? null : revocation.effectiveAt(),
              tasks.getOrDefault(record.id(), List.of()),
              record.version()));
    }
    return views;
  }

  private static Task task(TaskRecord task) {
    return new Task(
        task.id(), task.code(), task.status(), task.dueDate(), task.updatedAt(), task.version());
  }

  private EmploymentRecord locate(
      TenantId tenant, UUID employeeId, LocalDate lastDay, boolean lock) {
    history.employee(tenant, employeeId).orElseThrow(EmployeeDirectoryService::notFound);
    EmploymentRecord employment =
        history
            .employment(tenant, employeeId, lastDay)
            .orElseThrow(() -> new IllegalStateException("employee without employment"));
    if (!lock) {
      return employment;
    }
    return history
        .lockEmployment(tenant, employment.id())
        .orElseThrow(() -> new IllegalStateException("employment vanished"));
  }

  private Map<UUID, EmploymentRecord> lockEmployments(TenantId tenant, Collection<UUID> ids) {
    Map<UUID, EmploymentRecord> locked = new HashMap<>();
    for (UUID id : new TreeSet<>(ids)) {
      locked.put(
          id,
          history
              .lockEmployment(tenant, id)
              .orElseThrow(() -> new IllegalStateException("employment vanished")));
    }
    return locked;
  }

  private void supersede(TenantId tenant, TimelinePlan plan, UUID changeId, Instant now) {
    List<UUID> ids = plan.superseded().stream().map(Assignment::id).toList();
    if (history.supersede(tenant, ids, changeId, now) != ids.size()) {
      throw new ApiException(ErrorCode.SEPARATION_PREVIEW_CHANGED, Map.of());
    }
  }

  private SeparationRecord separation(TenantId tenant, UUID employeeId, UUID separationId) {
    return separations
        .find(tenant, employeeId, separationId)
        .orElseThrow(() -> new ApiException(ErrorCode.SEPARATION_NOT_FOUND, Map.of()));
  }

  private static ApiException notCancellable(String reason) {
    return new ApiException(ErrorCode.SEPARATION_NOT_CANCELLABLE, Map.of("reason", reason));
  }

  /**
   * Maps only the named V14 and V15 constraints; anything else stays an internal error.
   *
   * @param violated database failure
   * @return the exception to throw
   */
  static RuntimeException mapped(DataAccessException violated) {
    Optional<String> constraint = ConstraintViolations.constraint(violated);
    if (constraint.isEmpty()) {
      return violated;
    }
    return switch (constraint.get()) {
      case "employment_manager_employed" ->
          new ApiException(ErrorCode.MANAGER_INVALID, Map.of("reason", "NOT_EMPLOYED"));
      case "employment_separation_one_open",
          "access_revocation_one_open",
          "access_revocation_one_per_separation",
          "employment_end_governed" ->
          new ApiException(ErrorCode.SEPARATION_EXISTS, Map.of());
      case "access_revocation_membership_employee_role", "access_revocation_membership" ->
          new ApiException(ErrorCode.SEPARATION_PROTECTED, Map.of("reason", "ADMIN_ACCESS"));
      case "employment_separation_immutable", "access_revocation_immutable" ->
          new ApiException(ErrorCode.SEPARATION_NOT_CANCELLABLE, Map.of("reason", "NOT_SCHEDULED"));
      case "employment_assignment_within_employment",
          "employment_assignment_no_overlap",
          "employment_placement_coverage",
          "employment_change_shape" ->
          new ApiException(ErrorCode.SEPARATION_PREVIEW_CHANGED, Map.of());
      default -> EmploymentChangeService.mapped(violated);
    };
  }

  static UUID separationId(String raw) {
    try {
      return EmployeeDirectoryService.employeeId(raw);
    } catch (ApiException malformed) {
      throw new ApiException(ErrorCode.SEPARATION_NOT_FOUND, Map.of());
    }
  }

  static UUID taskId(String raw) {
    try {
      return EmployeeDirectoryService.employeeId(raw);
    } catch (ApiException malformed) {
      throw new ApiException(ErrorCode.SEPARATION_TASK_NOT_FOUND, Map.of());
    }
  }

  /**
   * Runs a write and turns a statement, lock or transaction timeout (including a NOWAIT conflict
   * with a background worker) into {@code 503 EMPLOYMENT_CHANGE_TIMEOUT}: the transaction was
   * rolled back entirely.
   */
  private static <T> T bounded(Supplier<T> work) {
    try {
      return work.get();
    } catch (ApiException expected) {
      throw expected;
    } catch (RuntimeException failure) {
      if (DatabaseTimeouts.isTimeout(failure)) {
        throw new ApiException(ErrorCode.EMPLOYMENT_CHANGE_TIMEOUT, Map.of());
      }
      throw failure;
    }
  }
}
