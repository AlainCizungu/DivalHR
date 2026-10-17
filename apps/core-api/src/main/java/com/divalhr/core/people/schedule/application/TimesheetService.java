package com.divalhr.core.people.schedule.application;

import com.divalhr.core.people.application.BusinessCalendar;
import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.schedule.api.GenerateTimesheetsRequest;
import com.divalhr.core.people.schedule.api.RefreshTimesheetsRequest;
import com.divalhr.core.people.schedule.api.TimesheetPeriodRequest;
import com.divalhr.core.people.schedule.api.TimesheetPeriodResponses.LockStateView;
import com.divalhr.core.people.schedule.api.TimesheetResponses;
import com.divalhr.core.people.schedule.api.TimesheetResponses.ActionView;
import com.divalhr.core.people.schedule.api.TimesheetResponses.Detail;
import com.divalhr.core.people.schedule.api.TimesheetResponses.DraftItem;
import com.divalhr.core.people.schedule.api.TimesheetResponses.EmployeeView;
import com.divalhr.core.people.schedule.api.TimesheetResponses.GenerationPreview;
import com.divalhr.core.people.schedule.api.TimesheetResponses.GenerationResult;
import com.divalhr.core.people.schedule.api.TimesheetResponses.GenerationSummary;
import com.divalhr.core.people.schedule.api.TimesheetResponses.GenerationView;
import com.divalhr.core.people.schedule.api.TimesheetResponses.MyActionView;
import com.divalhr.core.people.schedule.api.TimesheetResponses.MyDetail;
import com.divalhr.core.people.schedule.api.TimesheetResponses.MyItem;
import com.divalhr.core.people.schedule.api.TimesheetResponses.MyPage;
import com.divalhr.core.people.schedule.api.TimesheetResponses.MyRow;
import com.divalhr.core.people.schedule.api.TimesheetResponses.RefreshPreview;
import com.divalhr.core.people.schedule.api.TimesheetResponses.RefreshResult;
import com.divalhr.core.people.schedule.api.TimesheetResponses.RevisionView;
import com.divalhr.core.people.schedule.api.TimesheetResponses.RowView;
import com.divalhr.core.people.schedule.api.TimesheetResponses.SubmissionView;
import com.divalhr.core.people.schedule.api.TimesheetResponses.TotalsView;
import com.divalhr.core.people.schedule.application.TimesheetCommand.Refresh;
import com.divalhr.core.people.schedule.application.TimesheetCommand.Target;
import com.divalhr.core.people.schedule.domain.Substitution.Person;
import com.divalhr.core.people.schedule.domain.Timesheet;
import com.divalhr.core.people.schedule.domain.Timesheet.Period;
import com.divalhr.core.people.schedule.domain.Timesheet.Row;
import com.divalhr.core.people.schedule.domain.Timesheet.Totals;
import com.divalhr.core.people.schedule.domain.TimesheetApproval;
import com.divalhr.core.people.schedule.domain.TimesheetApproval.Blocker;
import com.divalhr.core.people.schedule.domain.TimesheetApproval.Status;
import com.divalhr.core.people.schedule.domain.TimesheetPeriod.LockState;
import com.divalhr.core.people.schedule.internal.JdbcTimesheetActionRepository;
import com.divalhr.core.people.schedule.internal.JdbcTimesheetPeriodRepository;
import com.divalhr.core.people.schedule.internal.JdbcTimesheetRepository;
import com.divalhr.core.people.schedule.internal.JdbcTimesheetRepository.Candidate;
import com.divalhr.core.people.schedule.internal.JdbcTimesheetRepository.Generation;
import com.divalhr.core.people.schedule.internal.JdbcTimesheetRepository.Header;
import com.divalhr.core.people.schedule.internal.JdbcTimesheetRepository.Member;
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
import java.time.LocalDate;
import java.time.ZoneId;
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
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Weekly draft timesheets (MVP-070A, Issue #128, T70A-1 to T70A-11; ADR 0018). Authorization (role
 * {@code tenant-admin} with privileged MFA, or the {@code employee} role for the caller's own
 * drafts) has run before this; tenant and actor never come from the request.
 *
 * <p>A generation turns one site's site-local week into one DRAFT per employee who is the effective
 * assignee of at least one included slot (an operational schedule's slot starting inside the week,
 * with exactly one qualifying employment), each with one row per slot from the effective attendance
 * projection. It is one bounded transaction: idempotency (step 0), the tenant's scheduling lock
 * (1), the candidates' employments {@code FOR SHARE} (2), the candidates read again (3); then the
 * bounds (at most 500 drafts and 5,000 source rows, refused before any write), the generation,
 * drafts, revisions and rows, the deferred checks, the audit record ({@value #GENERATE}) and the
 * outbox event ({@value #GENERATED_EVENT}). A week that is already generated is returned as it is,
 * never generated twice.
 *
 * <p>A refresh is previewed (a digest of every draft's next revision and the generation version)
 * and committed with that digest, the version and the identical site and week: under the same lock
 * order plus the generation and its drafts {@code FOR UPDATE}, everything is evaluated again and
 * any difference is {@code 409 TIMESHEET_REFRESH_PREVIEW_STALE}. Changed drafts receive a new
 * append-only revision (old revisions stay as evidence); newly included employees receive a draft.
 *
 * <p>MVP-070D (T70D-4): a refresh is refused while any timesheet of the week is SUBMITTED. An
 * APPROVED timesheet is never given a revision: it stays locked and unchanged (its approval stands,
 * and only a material attendance correction reopens it). A REOPENED timesheet always receives its
 * next revision and becomes a DRAFT again, ready for the employee's next submission cycle.
 *
 * <p>Names, numbers, labels, attendance instants, the subject and idempotency keys never enter
 * audit metadata, events, logs, metrics or errors.
 */
@Service
public class TimesheetService {

  /** Generation preview (interceptor operation and disclosure audit action). */
  public static final String GENERATION_PREVIEW = "timesheet.generation-preview";

  /** Generation command (idempotency operation, interceptor operation and audit action). */
  public static final String GENERATE = "timesheet.generate";

  /** A generation with its drafts (interceptor operation and disclosure audit action). */
  public static final String GENERATION_READ = "timesheet.generation-read";

  /** Refresh preview (interceptor operation and disclosure audit action). */
  public static final String REFRESH_PREVIEW = "timesheet.refresh-preview";

  /** Refresh command (idempotency operation, interceptor operation and audit action). */
  public static final String REFRESH = "timesheet.refresh";

  /** One draft for an administrator (interceptor operation and disclosure audit action). */
  public static final String READ = "timesheet.read";

  /** The employee's own drafts (interceptor operation and disclosure audit action). */
  public static final String SELF_LIST = "timesheet.self-list";

  /** One of the employee's own drafts (interceptor operation and disclosure audit action). */
  public static final String SELF_READ = "timesheet.self-read";

  /** Outbox event of a generation. */
  public static final String GENERATED_EVENT = "people.timesheet.generated.v1";

  /** Outbox event of a refresh. */
  public static final String REFRESHED_EVENT = "people.timesheet.refreshed.v1";

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.timesheet");
  private static final String SOURCE = "core-api/people";
  private static final String RESOURCE = "timesheet-generation";
  private static final Duration TRANSACTION_TIMEOUT = Duration.ofSeconds(30);
  private static final IdempotentOperation.Spec GENERATE_SPEC =
      new IdempotentOperation.Spec(GENERATE, "timesheet_generation", "generate", "generated", 200);
  private static final IdempotentOperation.Spec REFRESH_SPEC =
      new IdempotentOperation.Spec(REFRESH, "timesheet_generation", "refresh", "refreshed", 200);
  private static final Pattern LIMIT = Pattern.compile("^[0-9]{1,3}$");
  private static final int DEFAULT_LIMIT = 20;
  private static final int MAX_LIMIT = 100;

  private final IdempotentOperation operations;
  private final JdbcWorkScheduleRepository schedules;
  private final JdbcWorkScheduleSubstitutionRepository shifts;
  private final JdbcTimesheetRepository timesheets;
  private final JdbcTimesheetActionRepository actions;
  private final TimesheetRouting routing;
  private final JdbcTimesheetPeriodRepository periods;
  private final TimesheetPeriodGuard periodGuard;
  private final OrganizationPlacementDirectory directory;
  private final EmployeeAccessLinks links;
  private final CursorCodec cursors;
  private final BusinessCalendar calendar;
  private final AuditRecorder audit;
  private final OutboxWriter outbox;
  private final OperationMetrics metrics;
  private final PlatformTransactionManager transactionManager;
  private final JsonMapper json;
  private final int maxDrafts;
  private final int maxRows;

  /**
   * Creates the service.
   *
   * @param operations shared idempotent command flow
   * @param schedules schedule storage (the scheduling lock)
   * @param shifts employment locks and identities
   * @param timesheets timesheet storage
   * @param actions submission and decision storage (MVP-070B/C)
   * @param routing the approval route (MVP-070B/C)
   * @param periods period lock storage (the lock state of a week, MVP-071)
   * @param periodGuard the locked-period guard (MVP-071)
   * @param directory the tenant module's unit port (site, time zone, cost centers)
   * @param links the identity port (the employee's own link)
   * @param cursors signed cursors
   * @param calendar the platform clock (the authoritative instant)
   * @param audit audit recorder
   * @param outbox outbox writer
   * @param metrics operation metrics
   * @param transactionManager transaction manager
   * @param json JSON mapper
   * @param maxDrafts the per-request draft bound (T70A-3: 500; lowered only by tests)
   * @param maxRows the per-request source-row bound (T70A-3: 5,000; lowered only by tests)
   */
  public TimesheetService(
      IdempotentOperation operations,
      JdbcWorkScheduleRepository schedules,
      JdbcWorkScheduleSubstitutionRepository shifts,
      JdbcTimesheetRepository timesheets,
      JdbcTimesheetActionRepository actions,
      TimesheetRouting routing,
      JdbcTimesheetPeriodRepository periods,
      TimesheetPeriodGuard periodGuard,
      OrganizationPlacementDirectory directory,
      EmployeeAccessLinks links,
      CursorCodec cursors,
      BusinessCalendar calendar,
      AuditRecorder audit,
      OutboxWriter outbox,
      OperationMetrics metrics,
      PlatformTransactionManager transactionManager,
      JsonMapper json,
      @Value("${divalhr.timesheets.max-drafts:" + Timesheet.MAX_DRAFTS + "}") int maxDrafts,
      @Value("${divalhr.timesheets.max-rows:" + Timesheet.MAX_ROWS + "}") int maxRows) {
    this.operations = operations;
    this.schedules = schedules;
    this.shifts = shifts;
    this.timesheets = timesheets;
    this.actions = actions;
    this.routing = routing;
    this.periods = periods;
    this.periodGuard = periodGuard;
    this.directory = directory;
    this.links = links;
    this.cursors = cursors;
    this.calendar = calendar;
    this.audit = audit;
    this.outbox = outbox;
    this.metrics = metrics;
    this.transactionManager = transactionManager;
    this.json = json;
    this.maxDrafts = Math.min(maxDrafts, Timesheet.MAX_DRAFTS);
    this.maxRows = Math.min(maxRows, Timesheet.MAX_ROWS);
  }

  // ------------------------------------------------------------------------------------------
  // Generation
  // ------------------------------------------------------------------------------------------

  /**
   * What a generation of the site's week would hold now, and the week's generation if any.
   *
   * @param caller verified tenant administrator
   * @param body site and week
   * @return the preview, after its disclosure audit committed
   */
  public GenerationPreview previewGeneration(PeopleCaller caller, TimesheetPeriodRequest body) {
    return observed(
        GENERATION_PREVIEW,
        () -> {
          Target target = TimesheetCommand.target(body);
          return read(
              () -> {
                TenantId tenant = caller.tenant();
                Site site = site(tenant, target);
                Plan plan = candidates(tenant, site.period());
                GenerationSummary existing =
                    timesheets
                        .generationFor(tenant, target.siteId(), target.weekStart())
                        .map(g -> summary(tenant, g, site.view()))
                        .orElse(null);
                disclosed(
                    caller,
                    GENERATION_PREVIEW,
                    "timesheet-generation-preview",
                    plan.drafts(),
                    "DIVALHR-TIMESHEET-GENERATION-PREVIEW\nversion=1\nsite="
                        + target.siteId()
                        + "\nweek="
                        + target.weekStart()
                        + "\n");
                return new GenerationPreview(
                    TimesheetResponses.Period.of(site.period(), site.view()),
                    existing,
                    plan.drafts(),
                    TotalsView.of(plan.totals()),
                    maxDrafts,
                    maxRows);
              });
        });
  }

  /**
   * Generates the site's week, or returns its existing generation; replays an identical command.
   *
   * @param caller verified tenant administrator
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body site, week and confirmation
   * @return the result
   */
  public IdempotentOperation.Result<GenerationResult> generate(
      PeopleCaller caller, String idempotencyKey, GenerateTimesheetsRequest body) {
    return command(
        GENERATE,
        () -> {
          Target target =
              operations.validated(
                  GENERATE_SPEC, () -> TimesheetCommand.generate(idempotencyKey, body));
          return operations.execute(
              GENERATE_SPEC,
              caller.subject(),
              idempotencyKey,
              target.canonical(caller.tenant()),
              GenerationResult.class,
              TRANSACTION_TIMEOUT,
              () -> generated(caller, target));
        });
  }

  private IdempotentOperation.Completed<GenerationResult> generated(
      PeopleCaller caller, Target target) {
    TenantId tenant = caller.tenant();
    schedules.lock(tenant);
    Site site = site(tenant, target);
    Period period = site.period();
    var existing = timesheets.generationFor(tenant, target.siteId(), target.weekStart());
    if (existing.isPresent()) {
      Generation generation = existing.get();
      return new IdempotentOperation.Completed<>(
          new GenerationResult("EXISTING", summary(tenant, generation, site.view())),
          generation.id(),
          Outcome.UNCHANGED);
    }
    Plan plan = lockedCandidates(tenant, period);
    if (plan.drafts() == 0) {
      throw new ApiException(ErrorCode.TIMESHEET_GENERATION_NOT_ALLOWED, Map.of());
    }
    Instant now = now();
    UUID generationId = UUID.randomUUID();
    List<Written> written = new ArrayList<>();
    try {
      timesheets.insertGeneration(tenant, generationId, period, now, caller.subject());
      for (Map.Entry<UUID, List<Row>> draft : plan.byEmployee().entrySet()) {
        written.add(create(tenant, generationId, 1, period, draft.getKey(), draft.getValue(), now));
      }
      timesheets.checkConsistent(tenant);
    } catch (DataIntegrityViolationException violated) {
      throw mapped(violated);
    }
    Generation generation = timesheets.generation(tenant, generationId, false).orElseThrow();
    recorded(caller, GENERATE, generation, written, plan.totals(), now, -1);
    event(caller, GENERATED_EVENT, generation, written, plan.totals(), now, -1);
    LOG.atInfo()
        .addKeyValue("operation", GENERATE)
        .addKeyValue("outcome", "generated")
        .addKeyValue("drafts", band(written.size()))
        .log("timesheet_generated");
    return new IdempotentOperation.Completed<>(
        new GenerationResult("CREATED", summary(tenant, generation, site.view())),
        generationId,
        Outcome.CREATED);
  }

  /**
   * A generation and its drafts.
   *
   * @param caller verified tenant administrator
   * @param generationId path value
   * @return the generation, after its disclosure audit committed
   */
  public GenerationView generation(PeopleCaller caller, String generationId) {
    return observed(
        GENERATION_READ,
        () -> {
          UUID id = TimesheetCommand.id(generationId);
          return read(
              () -> {
                TenantId tenant = caller.tenant();
                Generation generation =
                    timesheets
                        .generation(tenant, id, false)
                        .orElseThrow(TimesheetCommand::notFound);
                UnitView site = unit(tenant, generation.period().siteId());
                List<Member> members = timesheets.members(tenant, id, false);
                List<DraftItem> items = new ArrayList<>(members.size());
                StringBuilder text =
                    new StringBuilder("DIVALHR-TIMESHEET-GENERATION-READ\nversion=1\ngeneration=")
                        .append(id)
                        .append('\n');
                for (Member member : members) {
                  Header h = member.header();
                  items.add(
                      new DraftItem(
                          h.id(),
                          EmployeeView.of(member.employee()),
                          h.status(),
                          h.revision(),
                          TotalsView.of(h.totals()),
                          h.updatedAt()));
                  text.append("timesheet=").append(h.id()).append('\n');
                }
                disclosed(
                    caller, GENERATION_READ, "timesheet-generation", items.size(), text.toString());
                return new GenerationView(
                    summary(generation, site, members, periods.lockState(tenant, id, false)),
                    items);
              });
        });
  }

  // ------------------------------------------------------------------------------------------
  // Refresh
  // ------------------------------------------------------------------------------------------

  /**
   * What a refresh of a generated week would change now.
   *
   * @param caller verified tenant administrator
   * @param generationId path value
   * @param body the generation's site and week
   * @return the preview, after its disclosure audit committed
   */
  public RefreshPreview previewRefresh(
      PeopleCaller caller, String generationId, TimesheetPeriodRequest body) {
    return observed(
        REFRESH_PREVIEW,
        () -> {
          UUID id = TimesheetCommand.id(generationId);
          Target target = TimesheetCommand.target(body);
          return read(
              () -> {
                TenantId tenant = caller.tenant();
                Generation generation = matching(tenant, id, target, false);
                UnitView site = unit(tenant, generation.period().siteId());
                Change change = change(tenant, caller, generation, false);
                disclosed(
                    caller,
                    REFRESH_PREVIEW,
                    "timesheet-refresh-preview",
                    change.next().size(),
                    "DIVALHR-TIMESHEET-REFRESH-PREVIEW\nversion=1\ndigest="
                        + change.digest()
                        + "\n");
                return change.preview(generation, site);
              });
        });
  }

  /**
   * Commits a previewed refresh, or replays an identical earlier one.
   *
   * @param caller verified tenant administrator
   * @param generationId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body site, week, version, preview digest and confirmation
   * @return the result
   */
  public IdempotentOperation.Result<RefreshResult> refresh(
      PeopleCaller caller,
      String generationId,
      String idempotencyKey,
      RefreshTimesheetsRequest body) {
    return command(
        REFRESH,
        () -> {
          UUID id = TimesheetCommand.id(generationId);
          Refresh refresh =
              operations.validated(
                  REFRESH_SPEC, () -> TimesheetCommand.refresh(idempotencyKey, body));
          return operations.execute(
              REFRESH_SPEC,
              caller.subject(),
              idempotencyKey,
              refresh.canonical(caller.tenant(), id),
              RefreshResult.class,
              TRANSACTION_TIMEOUT,
              () -> refreshed(caller, id, refresh));
        });
  }

  private IdempotentOperation.Completed<RefreshResult> refreshed(
      PeopleCaller caller, UUID id, Refresh refresh) {
    TenantId tenant = caller.tenant();
    schedules.lock(tenant);
    Generation generation = matching(tenant, id, refresh.target(), true);
    periodGuard.requireOpen(tenant, generation.id()); // MVP-071: a locked week is not refreshed
    if (generation.version() != refresh.version()) {
      throw stale();
    }
    Change change = change(tenant, caller, generation, true);
    if (!change.digest().equals(refresh.previewDigest())) {
      throw stale();
    }
    Instant now = now();
    int version = generation.version() + 1;
    List<Written> written = new ArrayList<>();
    try {
      if (timesheets.refreshGeneration(tenant, id, generation.version(), now, caller.subject())
          != 1) {
        throw stale();
      }
      for (Next next : change.next()) {
        if (!next.changed()) {
          continue;
        }
        if (next.header() == null) {
          written.add(
              create(
                  tenant, id, version, generation.period(), next.employeeId(), next.rows(), now));
        } else {
          int revision = next.header().revision() + 1;
          timesheets.insertRevision(
              tenant,
              next.header().id(),
              revision,
              version,
              Timesheet.digest(next.rows()),
              next.rows(),
              now);
          if (timesheets.advance(
                  tenant, next.header().id(), next.header().revision(), Totals.of(next.rows()), now)
              != 1) {
            throw stale();
          }
          written.add(new Written(next.header().id(), revision, Timesheet.digest(next.rows())));
        }
      }
      timesheets.checkConsistent(tenant);
    } catch (DataIntegrityViolationException violated) {
      throw mapped(violated);
    }
    Generation after = timesheets.generation(tenant, id, false).orElseThrow();
    Totals totals = change.totals();
    recorded(caller, REFRESH, after, written, totals, now, change.reopened());
    event(caller, REFRESHED_EVENT, after, written, totals, now, change.reopened());
    LOG.atInfo()
        .addKeyValue("operation", REFRESH)
        .addKeyValue("outcome", "refreshed")
        .addKeyValue("revisions", band(written.size()))
        .log("timesheet_refreshed");
    UnitView site = unit(tenant, after.period().siteId());
    return new IdempotentOperation.Completed<>(
        new RefreshResult(
            summary(tenant, after, site),
            change.newDrafts(),
            change.changedTimesheets(),
            change.changedRows(),
            change.reopened()),
        id,
        Outcome.UPDATED);
  }

  // ------------------------------------------------------------------------------------------
  // Drafts
  // ------------------------------------------------------------------------------------------

  /**
   * One draft for an administrator: the current revision's rows and every revision.
   *
   * @param caller verified tenant administrator
   * @param timesheetId path value
   * @return the draft, after its disclosure audit committed
   */
  public Detail timesheet(PeopleCaller caller, String timesheetId) {
    return observed(
        READ,
        () -> {
          UUID id = TimesheetCommand.id(timesheetId);
          return read(
              () -> {
                TenantId tenant = caller.tenant();
                Header header =
                    timesheets.timesheet(tenant, id).orElseThrow(TimesheetCommand::notFound);
                Person employee =
                    shifts
                        .person(tenant, header.employeeId())
                        .orElseThrow(TimesheetCommand::notFound);
                List<Row> rows = timesheets.rows(tenant, id, header.revision());
                Set<UUID> units = new HashSet<>();
                units.add(header.period().siteId());
                rows.forEach(
                    r -> {
                      if (r.source().costCenterId() != null) {
                        units.add(r.source().costCenterId());
                      }
                    });
                Map<UUID, UnitView> resolved = directory.resolveIds(tenant, units);
                List<RowView> views = new ArrayList<>(rows.size());
                for (Row row : rows) {
                  views.add(
                      RowView.of(
                          row,
                          row.source().costCenterId() == null
                              ? null
                              : costCenter(resolved.get(row.source().costCenterId()))));
                }
                List<RevisionView> revisions =
                    timesheets.revisions(tenant, id).stream()
                        .map(
                            r ->
                                new RevisionView(
                                    r.revision(),
                                    r.generationVersion(),
                                    r.createdAt(),
                                    TotalsView.of(r.totals())))
                        .toList();
                disclosed(
                    caller,
                    READ,
                    "timesheet",
                    views.size(),
                    "DIVALHR-TIMESHEET-READ\nversion=2\ntimesheet="
                        + id
                        + "\nrevision="
                        + header.revision()
                        + "\nstatus="
                        + header.status()
                        + "\ncycle="
                        + header.cycle()
                        + "\n");
                return new Detail(
                    id,
                    header.generationId(),
                    EmployeeView.of(employee),
                    TimesheetResponses.Period.of(
                        header.period(), site(resolved.get(header.period().siteId()))),
                    header.status(),
                    header.revision(),
                    header.cycle(),
                    TotalsView.of(header.totals()),
                    views,
                    revisions,
                    actions.actions(tenant, id).stream().map(ActionView::of).toList());
              });
        });
  }

  /**
   * One page of the caller's own drafts, newest week first.
   *
   * @param caller verified employee
   * @param cursor opaque cursor or {@code null}
   * @param limit page size (1 to 100, default 20)
   * @return the page, after its disclosure audit committed
   */
  public MyPage mine(PeopleCaller caller, String cursor, String limit) {
    return observed(
        SELF_LIST,
        () -> {
          FieldErrors errors = new FieldErrors();
          int size = limit(limit, errors);
          errors.throwIfAny();
          return read(
              () -> {
                TenantId tenant = caller.tenant();
                SelfLink link = link(caller);
                CursorScope scope =
                    new CursorScope(
                        SELF_LIST,
                        tenant,
                        Map.of(
                            "link",
                            link.linkId().toString(),
                            "employee",
                            link.employeeId().toString(),
                            "limit",
                            Integer.toString(size)));
                LocalDate afterWeek = null;
                UUID afterId = null;
                if (cursor != null) {
                  KeysetPosition position = cursors.decode(cursor, scope);
                  afterWeek = week(position.code());
                  afterId = position.id();
                }
                List<Header> rows =
                    timesheets.own(tenant, link.employeeId(), afterWeek, afterId, size + 1);
                List<Header> shown = rows.subList(0, Math.min(rows.size(), size));
                String next =
                    rows.size() > size
                        ? cursors.encode(
                            scope,
                            new KeysetPosition(
                                "W" + shown.get(shown.size() - 1).period().weekStart(),
                                shown.get(shown.size() - 1).id()))
                        : null;
                Set<UUID> sites = new HashSet<>();
                shown.forEach(h -> sites.add(h.period().siteId()));
                Map<UUID, UnitView> resolved =
                    sites.isEmpty() ? Map.of() : directory.resolveIds(tenant, sites);
                List<MyItem> items = new ArrayList<>(shown.size());
                StringBuilder text =
                    new StringBuilder("DIVALHR-TIMESHEET-SELF-LIST\nversion=1\nemployee=")
                        .append(link.employeeId())
                        .append('\n');
                for (Header h : shown) {
                  items.add(
                      new MyItem(
                          h.id(),
                          TimesheetResponses.Period.of(
                              h.period(), site(resolved.get(h.period().siteId()))),
                          h.status(),
                          h.revision(),
                          h.cycle(),
                          TotalsView.of(h.totals()),
                          h.updatedAt()));
                  text.append("timesheet=").append(h.id()).append('\n');
                }
                disclosed(caller, SELF_LIST, "my-timesheets", items.size(), text.toString());
                return new MyPage(items, next);
              });
        });
  }

  /**
   * One of the caller's own drafts with its current rows; another employee's, a foreign and an
   * unknown draft are the same {@code 404}.
   *
   * @param caller verified employee
   * @param timesheetId path value
   * @return the draft, after its disclosure audit committed
   */
  public MyDetail myTimesheet(PeopleCaller caller, String timesheetId) {
    return observed(
        SELF_READ,
        () -> {
          UUID id = TimesheetCommand.id(timesheetId);
          return read(
              () -> {
                TenantId tenant = caller.tenant();
                SelfLink link = link(caller);
                Header header =
                    timesheets
                        .timesheet(tenant, id)
                        .filter(h -> h.employeeId().equals(link.employeeId()))
                        .orElseThrow(TimesheetCommand::notFound);
                List<MyRow> rows =
                    timesheets.rows(tenant, id, header.revision()).stream().map(MyRow::of).toList();
                Generation generation =
                    timesheets.generation(tenant, header.generationId(), false).orElseThrow();
                SubmissionView submission = submission(tenant, header);
                List<MyActionView> history =
                    actions.actions(tenant, id).stream().map(MyActionView::of).toList();
                disclosed(
                    caller,
                    SELF_READ,
                    "my-timesheet",
                    rows.size(),
                    "DIVALHR-TIMESHEET-SELF-READ\nversion=2\ntimesheet="
                        + id
                        + "\nrevision="
                        + header.revision()
                        + "\nstatus="
                        + header.status()
                        + "\ncycle="
                        + header.cycle()
                        + "\n");
                return new MyDetail(
                    id,
                    TimesheetResponses.Period.of(
                        header.period(), unit(tenant, header.period().siteId())),
                    header.status(),
                    header.revision(),
                    generation.version(),
                    header.cycle(),
                    TotalsView.of(header.totals()),
                    header.updatedAt(),
                    rows,
                    submission,
                    history,
                    periods.lockState(tenant, header.generationId(), false).locked());
              });
        });
  }

  /**
   * Whether the employee can submit the timesheet now (MVP-070B/C, T70BC-2, T70BC-3): only a DRAFT,
   * without a blocking row, with a READY row and an approval route; the route is resolved last and
   * named nowhere. A REOPENED timesheet waits for the organization's refresh (MVP-070D).
   */
  private SubmissionView submission(TenantId tenant, Header header) {
    if (header.reopened()) {
      return new SubmissionView(false, List.of(Blocker.REFRESH_REQUIRED.name()));
    }
    if (!header.draft()) {
      return new SubmissionView(false, List.of());
    }
    List<String> blockers = new ArrayList<>();
    TimesheetApproval.rowBlockers(header.totals()).forEach(b -> blockers.add(b.name()));
    if (blockers.isEmpty()
        && routing
            .employment(tenant, header)
            .flatMap(employment -> routing.manager(tenant, header, employment))
            .isEmpty()) {
      blockers.add(Blocker.APPROVAL_ROUTE_UNAVAILABLE.name());
    }
    return new SubmissionView(blockers.isEmpty(), blockers);
  }

  /**
   * The timesheet's next rows from the current sources, as a refresh would compute them (MVP-070B/C
   * decisions compare them with the submitted evidence; nothing is written). With {@code lock} the
   * caller holds the scheduling lock, and the week's candidates' employments are locked {@code FOR
   * SHARE} first.
   *
   * @param tenant verified tenant
   * @param header the timesheet
   * @param lock whether to apply the lock order
   * @return the next rows
   */
  List<Row> nextRows(TenantId tenant, Header header, boolean lock) {
    Plan plan =
        lock ? lockedCandidates(tenant, header.period()) : candidates(tenant, header.period());
    List<Row> previous = timesheets.rows(tenant, header.id(), header.revision());
    return Timesheet.refreshed(
        previous, plan.byEmployee().getOrDefault(header.employeeId(), List.of()));
  }

  // ------------------------------------------------------------------------------------------
  // Candidates and plans
  // ------------------------------------------------------------------------------------------

  /** The resolved site and its week. */
  private record Site(UnitView view, Period period) {}

  /**
   * The included slots of a week, grouped by employee (ordered by employee ID, rows by start).
   *
   * @param byEmployee each employee's rows
   * @param employments every qualifying employment
   * @param totals the rows' totals
   */
  private record Plan(Map<UUID, List<Row>> byEmployee, Set<UUID> employments, Totals totals) {
    int drafts() {
      return byEmployee.size();
    }
  }

  /** A draft written by a command: its ID, revision and source digest (audit digest only). */
  private record Written(UUID timesheetId, int revision, String digest) {}

  /**
   * One timesheet's next state in a refresh.
   *
   * @param employeeId employee
   * @param header the existing timesheet, or {@code null} for a new draft
   * @param rows its next rows (a locked approved timesheet's current rows)
   * @param changed whether it receives a revision
   * @param changes the changed slots
   */
  private record Next(
      UUID employeeId, Header header, List<Row> rows, boolean changed, int changes) {

    boolean reopened() {
      return header != null && header.reopened();
    }

    boolean locked() {
      return header != null && Status.APPROVED.name().equals(header.status());
    }
  }

  /** A refresh's evaluation: every draft's next state and the preview digest. */
  private record Change(List<Next> next, Totals totals, String digest) {

    int newDrafts() {
      return (int) next.stream().filter(n -> n.header() == null).count();
    }

    int changedTimesheets() {
      return (int) next.stream().filter(n -> n.header() != null && n.changed()).count();
    }

    int changedRows() {
      return next.stream().mapToInt(Next::changes).sum();
    }

    int reopened() {
      return (int) next.stream().filter(Next::reopened).count();
    }

    int locked() {
      return (int) next.stream().filter(Next::locked).count();
    }

    RefreshPreview preview(Generation generation, UnitView site) {
      int unchanged = (int) next.stream().filter(n -> !n.changed()).count();
      return new RefreshPreview(
          generation.id(),
          generation.version(),
          TimesheetResponses.Period.of(generation.period(), site),
          digest,
          newDrafts(),
          changedTimesheets(),
          unchanged,
          changedRows(),
          TotalsView.of(totals),
          reopened(),
          locked());
    }
  }

  /** The verified site of the request and its week (a past or the current site-local week). */
  private Site site(TenantId tenant, Target target) {
    UnitView view = unit(tenant, target.siteId());
    if (view == null || view.kind() != UnitKind.SITE || view.timezone() == null) {
      throw TimesheetCommand.notFound();
    }
    LocalDate today = calendar.now().atZone(ZoneId.of(view.timezone())).toLocalDate();
    if (target.weekStart().isAfter(Period.mondayOf(today))) {
      FieldErrors errors = new FieldErrors();
      errors.add("weekStart", Constraint.RANGE);
      errors.throwIfAny();
    }
    return new Site(view, Period.of(target.siteId(), view.timezone(), target.weekStart()));
  }

  /** The week's included slots now (no lock): bounded, never partial. */
  private Plan candidates(TenantId tenant, Period period) {
    List<Candidate> read = timesheets.candidates(tenant, period, maxRows + 1);
    if (read.size() > maxRows) {
      throw limitExceeded();
    }
    Map<UUID, List<Row>> byEmployee = new TreeMap<>();
    Set<UUID> employments = new TreeSet<>();
    List<Row> all = new ArrayList<>();
    for (Candidate candidate : read) {
      if (candidate.qualifying() != 1) {
        continue; // no or several qualifying employments: excluded, never chosen arbitrarily
      }
      Row row = Row.of(candidate.source());
      byEmployee.computeIfAbsent(candidate.source().employeeId(), e -> new ArrayList<>()).add(row);
      employments.add(candidate.source().employmentId());
      all.add(row);
    }
    if (byEmployee.size() > maxDrafts) {
      throw limitExceeded();
    }
    Map<UUID, List<Row>> ordered = new LinkedHashMap<>();
    byEmployee.forEach((employee, rows) -> ordered.put(employee, Timesheet.ordered(rows)));
    return new Plan(ordered, employments, Totals.of(all));
  }

  /**
   * The week's included slots under the lock order: the scheduling lock is held; the candidates'
   * employments are locked {@code FOR SHARE} and the candidates are read again.
   */
  private Plan lockedCandidates(TenantId tenant, Period period) {
    Plan first = candidates(tenant, period);
    if (!first.employments().isEmpty()) {
      shifts.shareEmployments(
          tenant, first.employments(), null, period.weekStart().minusDays(1), period.weekEnd());
    }
    return candidates(tenant, period);
  }

  /** The generation of the request's site and week, or the one safe 404. */
  private Generation matching(TenantId tenant, UUID id, Target target, boolean lock) {
    Generation generation =
        timesheets.generation(tenant, id, lock).orElseThrow(TimesheetCommand::notFound);
    if (!generation.period().siteId().equals(target.siteId())
        || !generation.period().weekStart().equals(target.weekStart())) {
      throw TimesheetCommand.notFound();
    }
    return generation;
  }

  /**
   * Every timesheet's next state: the existing ones (by employee) and newly included employees. A
   * SUBMITTED timesheet refuses the refresh (MVP-070B/C, T70BC-8); an APPROVED one stays locked and
   * unchanged and a REOPENED one always receives its next revision (MVP-070D, T70D-4).
   */
  private Change change(TenantId tenant, PeopleCaller caller, Generation generation, boolean lock) {
    List<Member> members = timesheets.members(tenant, generation.id(), lock);
    if (members.stream().anyMatch(m -> Status.SUBMITTED.name().equals(m.header().status()))) {
      throw new ApiException(ErrorCode.TIMESHEET_GENERATION_NOT_ALLOWED, Map.of());
    }
    Plan plan =
        lock
            ? lockedCandidates(tenant, generation.period())
            : candidates(tenant, generation.period());
    Map<UUID, Member> existing = new TreeMap<>();
    members.forEach(m -> existing.put(m.header().employeeId(), m));
    Set<UUID> employees = new TreeSet<>(existing.keySet());
    employees.addAll(plan.byEmployee().keySet());
    if (employees.size() > maxDrafts) {
      throw limitExceeded();
    }
    List<Next> next = new ArrayList<>(employees.size());
    Totals totals = Totals.NONE;
    StringBuilder text =
        new StringBuilder("DIVALHR-TIMESHEET-REFRESH\nversion=2\ntenant=")
            .append(tenant)
            .append("\nsubject=")
            .append(caller.subject())
            .append("\ngeneration=")
            .append(generation.id())
            .append("\ngenerationVersion=")
            .append(generation.version())
            .append('\n');
    int written = 0;
    for (UUID employee : employees) {
      List<Row> current = plan.byEmployee().getOrDefault(employee, List.of());
      Member member = existing.get(employee);
      Next item;
      if (member == null) {
        item = new Next(employee, null, current, true, current.size());
      } else {
        Header header = member.header();
        List<Row> previous = timesheets.rows(tenant, header.id(), header.revision());
        if (Status.APPROVED.name().equals(header.status())) {
          // Locked: an approval is never rewritten; only a correction reopens it (T70D-4).
          item = new Next(employee, header, previous, false, 0);
        } else {
          List<Row> rows = Timesheet.refreshed(previous, current);
          int changes = Timesheet.changes(previous, rows);
          item = new Next(employee, header, rows, changes > 0 || header.reopened(), changes);
        }
      }
      next.add(item);
      totals = totals.plus(Totals.of(item.rows()));
      if (item.changed()) {
        written += item.rows().size();
      }
      text.append("employee=")
          .append(employee)
          .append("|timesheet=")
          .append(item.header() == null ? "new" : item.header().id())
          .append("|revision=")
          .append(item.header() == null ? 0 : item.header().revision())
          .append("|status=")
          .append(item.header() == null ? "new" : item.header().status())
          .append("|next=")
          .append(Timesheet.digest(item.rows()))
          .append('\n');
    }
    if (written > maxRows) {
      throw limitExceeded();
    }
    return new Change(List.copyOf(next), totals, Fingerprints.sha256(text.toString()));
  }

  /** A new draft at its first revision. */
  private Written create(
      TenantId tenant,
      UUID generationId,
      int generationVersion,
      Period period,
      UUID employeeId,
      List<Row> rows,
      Instant now) {
    UUID id = UUID.randomUUID();
    String digest = Timesheet.digest(rows);
    timesheets.insertTimesheet(tenant, id, generationId, employeeId, period, Totals.of(rows), now);
    timesheets.insertRevision(tenant, id, 1, generationVersion, digest, rows, now);
    return new Written(id, 1, digest);
  }

  // ------------------------------------------------------------------------------------------
  // Summaries, audit and outbox
  // ------------------------------------------------------------------------------------------

  private GenerationSummary summary(TenantId tenant, Generation generation, UnitView site) {
    return summary(
        generation,
        site,
        timesheets.members(tenant, generation.id(), false),
        periods.lockState(tenant, generation.id(), false));
  }

  private static GenerationSummary summary(
      Generation generation, UnitView site, List<Member> members, LockState lock) {
    Totals totals = Totals.NONE;
    for (Member member : members) {
      totals = totals.plus(member.header().totals());
    }
    return new GenerationSummary(
        generation.id(),
        TimesheetResponses.Period.of(generation.period(), site),
        "DRAFT",
        generation.version(),
        generation.generatedAt(),
        generation.refreshedAt(),
        members.size(),
        TotalsView.of(totals),
        LockStateView.of(lock));
  }

  /** The audit record: bounded metadata; identifiers and boundaries only in the digest. */
  private void recorded(
      PeopleCaller caller,
      String action,
      Generation generation,
      List<Written> written,
      Totals totals,
      Instant now,
      int reopened) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", WorkScheduleService.SCHEMA_VERSION);
    metadata.put("week", generation.period().weekStart().toString());
    metadata.put("draftCount", written.size());
    metadata.put("rowCount", totals.rows());
    metadata.put("readyCount", totals.ready());
    metadata.put("blockedCount", totals.blocked());
    metadata.put("revisionCount", written.size());
    if (reopened >= 0) {
      // MVP-070D: the reopened timesheets this refresh made drafts again (a refresh only).
      metadata.put("reopenedCount", reopened);
    }
    Map<String, Object> state = new TreeMap<>();
    state.put("tenantId", caller.tenant().toString());
    state.put("generationId", generation.id().toString());
    state.put("siteId", generation.period().siteId().toString());
    state.put("periodStartAt", generation.period().startAt().toString());
    state.put("periodEndAt", generation.period().endAt().toString());
    state.put("version", generation.version());
    List<String> drafts = new ArrayList<>();
    written.forEach(w -> drafts.add(w.timesheetId() + ":" + w.revision() + ":" + w.digest()));
    state.put("revisions", drafts);
    state.put("workedMinutes", totals.workedMinutes());
    state.put("by", caller.subject());
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            now,
            caller.subject(),
            action,
            RESOURCE,
            generation.id(),
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(json.writeValueAsString(state))));
  }

  /** Stable IDs, the week's boundaries, counts and the version only. */
  private void event(
      PeopleCaller caller,
      String type,
      Generation generation,
      List<Written> written,
      Totals totals,
      Instant now,
      int reopened) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("generationId", generation.id().toString());
    data.put("siteId", generation.period().siteId().toString());
    data.put("weekStart", generation.period().weekStart().toString());
    data.put("periodStartAt", generation.period().startAt().toString());
    data.put("periodEndAt", generation.period().endAt().toString());
    data.put("version", generation.version());
    data.put("revisionCount", written.size());
    data.put("rowCount", totals.rows());
    data.put("readyCount", totals.ready());
    data.put("blockedCount", totals.blocked());
    if (reopened >= 0) {
      data.put("reopenedCount", reopened);
    }
    data.put("occurredAt", now.toString());
    outbox.append(
        new EventEnvelope(
            UUID.randomUUID().toString(),
            type,
            WorkScheduleService.SCHEMA_VERSION,
            caller.tenant().toString(),
            SOURCE,
            generation.id().toString(),
            now.toString(),
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

  private UnitView unit(TenantId tenant, UUID id) {
    return directory.resolveIds(tenant, Set.of(id)).get(id);
  }

  private static UnitView site(UnitView view) {
    return view != null && view.kind() == UnitKind.SITE ? view : null;
  }

  private static UnitView costCenter(UnitView view) {
    return view != null && view.kind() == UnitKind.COST_CENTER ? view : null;
  }

  private SelfLink link(PeopleCaller caller) {
    return links
        .linkedEmployee(caller.tenant(), caller.subject())
        .orElseThrow(() -> new ApiException(ErrorCode.EMPLOYEE_LINK_REQUIRED, Map.of()));
  }

  private static LocalDate week(String code) {
    if (code == null || !code.startsWith("W")) {
      throw WorkScheduleService.invalidCursor();
    }
    try {
      return LocalDate.parse(code.substring(1));
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

  /** A bounded count band for logs: never an exact count of people. */
  static String band(int count) {
    if (count == 0) {
      return "0";
    }
    if (count <= 10) {
      return "1-10";
    }
    return count <= 100 ? "11-100" : "101-500";
  }

  private Instant now() {
    return calendar.now().truncatedTo(ChronoUnit.MICROS);
  }

  private static ApiException stale() {
    return new ApiException(ErrorCode.TIMESHEET_REFRESH_PREVIEW_STALE, Map.of());
  }

  private static ApiException limitExceeded() {
    return new ApiException(ErrorCode.TIMESHEET_GENERATION_LIMIT_EXCEEDED, Map.of());
  }

  /** The stable conflict of a constraint the locks should have made unreachable; no DB text. */
  private static ApiException mapped(DataIntegrityViolationException violated) {
    String constraint = ScheduleConstraintViolations.constraint(violated).orElse("");
    return switch (constraint) {
      case ScheduleConstraintViolations.TIMESHEET_GENERATION_WEEK,
          ScheduleConstraintViolations.TIMESHEET_GENERATION_LOCKED ->
          new ApiException(ErrorCode.TIMESHEET_GENERATION_NOT_ALLOWED, Map.of());
      case ScheduleConstraintViolations.TIMESHEET_REVISION_SEQUENCE,
          ScheduleConstraintViolations.TIMESHEET_GENERATION_GUARD,
          ScheduleConstraintViolations.TIMESHEET_GUARD ->
          stale();
      default -> throw violated;
    };
  }

  /** A command's metric and log: the operation and a bounded outcome only. */
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

  /** Records a read or preview outcome; rejections log only their code. */
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
            case TIMESHEET_GENERATION_LIMIT_EXCEEDED, VALIDATION_FAILED, CURSOR_INVALID ->
                Outcome.VALIDATION_FAILED;
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
