package com.divalhr.core.people.schedule.application;

import com.divalhr.core.people.application.BusinessCalendar;
import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.schedule.api.LockTimesheetPeriodRequest;
import com.divalhr.core.people.schedule.api.TimesheetPeriodResponses.ActionView;
import com.divalhr.core.people.schedule.api.TimesheetPeriodResponses.Counts;
import com.divalhr.core.people.schedule.api.TimesheetPeriodResponses.History;
import com.divalhr.core.people.schedule.api.TimesheetPeriodResponses.LockPreview;
import com.divalhr.core.people.schedule.api.TimesheetPeriodResponses.LockStateView;
import com.divalhr.core.people.schedule.api.TimesheetPeriodResponses.Result;
import com.divalhr.core.people.schedule.api.TimesheetPeriodResponses.ScopeView;
import com.divalhr.core.people.schedule.api.TimesheetPeriodResponses.States;
import com.divalhr.core.people.schedule.api.TimesheetPeriodResponses.UnlockPreview;
import com.divalhr.core.people.schedule.api.TimesheetPeriodScopeRequest;
import com.divalhr.core.people.schedule.api.TimesheetPeriodUnlockPreviewRequest;
import com.divalhr.core.people.schedule.api.UnlockTimesheetPeriodRequest;
import com.divalhr.core.people.schedule.application.TimesheetPeriodCommand.Lock;
import com.divalhr.core.people.schedule.application.TimesheetPeriodCommand.Reasoned;
import com.divalhr.core.people.schedule.application.TimesheetPeriodCommand.Target;
import com.divalhr.core.people.schedule.application.TimesheetPeriodCommand.Unlock;
import com.divalhr.core.people.schedule.domain.TimesheetExport;
import com.divalhr.core.people.schedule.domain.TimesheetPeriod;
import com.divalhr.core.people.schedule.domain.TimesheetPeriod.Action;
import com.divalhr.core.people.schedule.domain.TimesheetPeriod.Kind;
import com.divalhr.core.people.schedule.domain.TimesheetPeriod.LockState;
import com.divalhr.core.people.schedule.domain.TimesheetPeriod.Readiness;
import com.divalhr.core.people.schedule.domain.TimesheetPeriod.Scope;
import com.divalhr.core.people.schedule.internal.JdbcTimesheetExportRepository;
import com.divalhr.core.people.schedule.internal.JdbcTimesheetPeriodRepository;
import com.divalhr.core.people.schedule.internal.JdbcTimesheetRepository;
import com.divalhr.core.people.schedule.internal.JdbcTimesheetRepository.Generation;
import com.divalhr.core.people.schedule.internal.JdbcWorkScheduleRepository;
import com.divalhr.core.people.schedule.internal.ScheduleConstraintViolations;
import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.UnitKind;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.UnitView;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * Period locks (MVP-071, Issue #139, L71-1 to L71-7, L71-10; ADR 0022). Authorization (role {@code
 * tenant-admin} with privileged MFA) has run before this; tenant, actor, scope and the current lock
 * action never come from the request.
 *
 * <p>A lock preview evaluates the generated week's timesheets: ready when at least one exists,
 * every one is APPROVED by its current action at its current revision, cycle and the current
 * generation version, and the week is not locked already; otherwise it names the stable blockers
 * and the counts by state. Its digest binds the tenant, the approval evidence digest, the
 * generation and lock versions and the expiry (the end of the next 15-minute window), so the lock
 * needs nothing but the digest and the two versions.
 *
 * <p>A lock is one bounded transaction: idempotency, the tenant's scheduling lock (every timesheet
 * command and correction takes it first, so nothing interleaves), the generation and the projection
 * locked, every timesheet locked and evaluated again, the versions and the digest compared, then
 * the immutable LOCKED action and the projection (checked by V36's transition and projection
 * triggers), the audit record ({@value #LOCK}) and the outbox event ({@value #LOCKED_EVENT}). An
 * already locked week whose preview named its current lock is UNCHANGED; anything else stale is
 * refused before any write.
 *
 * <p>An unlock previews the period, its current lock and the closed reason with its explanation
 * (Restricted: stored with the UNLOCKED action, digested everywhere else, never returned), then
 * appends the UNLOCKED action under the same locks ({@value #UNLOCK}, {@value #UNLOCKED_EVENT}).
 * Unlocking alone changes no timesheet and no export. Names, employee numbers, subjects,
 * explanations and idempotency keys never enter audit metadata, events, logs, metrics or errors.
 */
@Service
public class TimesheetPeriodService {

  /** Lock preview (interceptor operation and disclosure audit action). */
  public static final String LOCK_PREVIEW = "timesheet-period.lock-preview";

  /** Lock (idempotency operation, interceptor operation and audit action). */
  public static final String LOCK = "timesheet-period.lock";

  /** Unlock preview (interceptor operation and disclosure audit action). */
  public static final String UNLOCK_PREVIEW = "timesheet-period.unlock-preview";

  /** Unlock (idempotency operation, interceptor operation and audit action). */
  public static final String UNLOCK = "timesheet-period.unlock";

  /** History (interceptor operation and disclosure audit action). */
  public static final String HISTORY = "timesheet-period.history";

  /** Outbox event of a lock. */
  public static final String LOCKED_EVENT = "people.timesheet-period.locked.v1";

  /** Outbox event of an unlock. */
  public static final String UNLOCKED_EVENT = "people.timesheet-period.unlocked.v1";

  /** The preview window: a preview expires at the end of the next window. */
  static final Duration WINDOW = TimesheetExportService.WINDOW;

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.timesheet-period");
  private static final String SOURCE = "core-api/people";
  private static final String RESOURCE = "timesheet-period";
  private static final Duration TRANSACTION_TIMEOUT = Duration.ofSeconds(30);
  private static final IdempotentOperation.Spec LOCK_SPEC =
      new IdempotentOperation.Spec(LOCK, "timesheet_period", "lock", "locked", 200);
  private static final IdempotentOperation.Spec UNLOCK_SPEC =
      new IdempotentOperation.Spec(UNLOCK, "timesheet_period", "unlock", "unlocked", 200);
  private static final List<String> UNLOCK_WARNINGS =
      List.of("EXPORTS_UNCHANGED", "APPROVALS_UNCHANGED");

  private final IdempotentOperation operations;
  private final JdbcWorkScheduleRepository schedules;
  private final JdbcTimesheetRepository timesheets;
  private final JdbcTimesheetPeriodRepository periods;
  private final JdbcTimesheetExportRepository exports;
  private final OrganizationPlacementDirectory directory;
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
   * @param schedules schedule storage (the scheduling lock)
   * @param timesheets timesheet storage (the generation)
   * @param periods period lock storage
   * @param exports export storage (whether the evidence was exported)
   * @param directory the tenant module's unit port (legal entity, site)
   * @param calendar the platform clock
   * @param audit audit recorder
   * @param outbox outbox writer
   * @param metrics operation metrics
   * @param transactionManager transaction manager
   * @param json JSON mapper
   */
  public TimesheetPeriodService(
      IdempotentOperation operations,
      JdbcWorkScheduleRepository schedules,
      JdbcTimesheetRepository timesheets,
      JdbcTimesheetPeriodRepository periods,
      JdbcTimesheetExportRepository exports,
      OrganizationPlacementDirectory directory,
      BusinessCalendar calendar,
      AuditRecorder audit,
      OutboxWriter outbox,
      OperationMetrics metrics,
      PlatformTransactionManager transactionManager,
      JsonMapper json) {
    this.operations = operations;
    this.schedules = schedules;
    this.timesheets = timesheets;
    this.periods = periods;
    this.exports = exports;
    this.directory = directory;
    this.calendar = calendar;
    this.audit = audit;
    this.outbox = outbox;
    this.metrics = metrics;
    this.transactionManager = transactionManager;
    this.json = json;
  }

  // ------------------------------------------------------------------------------------------
  // Lock
  // ------------------------------------------------------------------------------------------

  /**
   * Whether the week is ready to lock, and what a lock would bind now.
   *
   * @param caller verified tenant administrator
   * @param body legal entity, site and week
   * @return the preview, after its disclosure audit committed
   */
  public LockPreview previewLock(PeopleCaller caller, TimesheetPeriodScopeRequest body) {
    return observed(
        LOCK_PREVIEW,
        () -> {
          Target target = TimesheetPeriodCommand.target(body);
          return read(
              () -> {
                TenantId tenant = caller.tenant();
                Units units = units(tenant, target);
                Generation generation = generation(tenant, target);
                LockState state = periods.lockState(tenant, generation.id(), false);
                Readiness readiness = evaluate(tenant, target, generation, state, false);
                Instant expiresAt = expiry(now());
                String digest = lockPreviewDigest(tenant, readiness, state, expiresAt);
                disclosed(
                    caller,
                    LOCK_PREVIEW,
                    "timesheet-period-lock-preview",
                    readiness.totals().timesheets(),
                    "DIVALHR-TIMESHEET-PERIOD-LOCK-PREVIEW\nversion=1\ngeneration="
                        + generation.id()
                        + "\nevidence="
                        + readiness.evidenceDigest()
                        + "\n");
                return new LockPreview(
                    ScopeView.of(readiness.scope(), units.legalEntity(), units.site()),
                    generation.id(),
                    generation.version(),
                    LockStateView.of(state),
                    Counts.of(readiness.totals()),
                    States.of(readiness.states()),
                    readiness.ready(),
                    readiness.blockers().stream().map(Enum::name).toList(),
                    exported(tenant, readiness.scope()),
                    digest,
                    expiresAt);
              });
        });
  }

  /**
   * Locks a reviewed week, returns the current lock of an already locked week whose preview named
   * it, or replays an identical command.
   *
   * @param caller verified tenant administrator
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body scope, versions, preview digest and confirmation
   * @return the result
   */
  public IdempotentOperation.Result<Result> lock(
      PeopleCaller caller, String idempotencyKey, LockTimesheetPeriodRequest body) {
    return command(
        LOCK,
        () -> {
          Lock lock =
              operations.validated(
                  LOCK_SPEC, () -> TimesheetPeriodCommand.lock(idempotencyKey, body));
          return operations.execute(
              LOCK_SPEC,
              caller.subject(),
              idempotencyKey,
              lock.canonical(caller.tenant()),
              Result.class,
              TRANSACTION_TIMEOUT,
              () -> locked(caller, lock));
        });
  }

  private IdempotentOperation.Completed<Result> locked(PeopleCaller caller, Lock lock) {
    TenantId tenant = caller.tenant();
    schedules.lock(tenant);
    Target target = lock.target();
    Units units = units(tenant, target);
    Generation generation = generation(tenant, target);
    generation = timesheets.generation(tenant, generation.id(), true).orElseThrow();
    LockState state = periods.lockState(tenant, generation.id(), true);
    Readiness readiness = evaluate(tenant, target, generation, state, true);
    Instant now = now();
    if (generation.version() != lock.generationVersion()
        || state.version() != lock.lockVersion()
        || !fresh(tenant, readiness, state, lock.previewDigest(), now)) {
      throw stale();
    }
    if (state.locked()) {
      // The preview named the current lock: nothing changes.
      Action current = periods.action(tenant, state.currentActionId()).orElseThrow();
      return new IdempotentOperation.Completed<>(
          new Result(
              "UNCHANGED",
              ScopeView.of(current.scope(), units.legalEntity(), units.site()),
              generation.id(),
              LockStateView.of(state),
              ActionView.of(current)),
          current.id(),
          Outcome.UNCHANGED);
    }
    if (!readiness.ready()) {
      throw new ApiException(ErrorCode.TIMESHEET_PERIOD_NOT_READY, Map.of());
    }
    Action action =
        new Action(
            UUID.randomUUID(),
            generation.id(),
            state.version() + 1,
            Kind.LOCKED,
            readiness.scope(),
            readiness.totals(),
            readiness.evidenceDigest(),
            null,
            null,
            null,
            now);
    LockState after = append(caller, action, state, "");
    LOG.atInfo()
        .addKeyValue("operation", LOCK)
        .addKeyValue("outcome", "locked")
        .addKeyValue("transition", "OPEN_TO_LOCKED")
        .addKeyValue("timesheets", TimesheetPeriod.countBand(action.totals().timesheets()))
        .log("timesheet_period_locked");
    return new IdempotentOperation.Completed<>(
        new Result(
            "LOCKED",
            ScopeView.of(action.scope(), units.legalEntity(), units.site()),
            generation.id(),
            LockStateView.of(after),
            ActionView.of(action)),
        action.id(),
        Outcome.CREATED);
  }

  // ------------------------------------------------------------------------------------------
  // Unlock
  // ------------------------------------------------------------------------------------------

  /**
   * What an unlock would do: the period, its current lock and the reviewed reason.
   *
   * @param caller verified tenant administrator
   * @param body legal entity, site, week, reason code and explanation
   * @return the preview, after its disclosure audit committed
   */
  public UnlockPreview previewUnlock(
      PeopleCaller caller, TimesheetPeriodUnlockPreviewRequest body) {
    return observed(
        UNLOCK_PREVIEW,
        () -> {
          Reasoned reasoned = TimesheetPeriodCommand.reasoned(body);
          return read(
              () -> {
                TenantId tenant = caller.tenant();
                Target target = reasoned.target();
                Units units = units(tenant, target);
                Generation generation = generation(tenant, target);
                LockState state = periods.lockState(tenant, generation.id(), false);
                Action current = currentLock(tenant, state);
                Instant expiresAt = expiry(now());
                String digest = unlockPreviewDigest(tenant, state, reasoned, expiresAt);
                disclosed(
                    caller,
                    UNLOCK_PREVIEW,
                    "timesheet-period-unlock-preview",
                    1,
                    "DIVALHR-TIMESHEET-PERIOD-UNLOCK-PREVIEW\nversion=1\ngeneration="
                        + generation.id()
                        + "\nlockAction="
                        + current.id()
                        + "\nreason="
                        + reasoned.reason()
                        + "\nexplanationDigest="
                        + reasoned.explanationDigest()
                        + "\n");
                return new UnlockPreview(
                    ScopeView.of(current.scope(), units.legalEntity(), units.site()),
                    generation.id(),
                    LockStateView.of(state),
                    ActionView.of(current),
                    reasoned.reason().name(),
                    UNLOCK_WARNINGS,
                    digest,
                    expiresAt);
              });
        });
  }

  /**
   * Unlocks a locked week after its reviewed preview, or replays an identical command.
   *
   * @param caller verified tenant administrator
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body scope, reason, explanation, current lock, preview digest and confirmation
   * @return the result
   */
  public IdempotentOperation.Result<Result> unlock(
      PeopleCaller caller, String idempotencyKey, UnlockTimesheetPeriodRequest body) {
    return command(
        UNLOCK,
        () -> {
          Unlock unlock =
              operations.validated(
                  UNLOCK_SPEC, () -> TimesheetPeriodCommand.unlock(idempotencyKey, body));
          return operations.execute(
              UNLOCK_SPEC,
              caller.subject(),
              idempotencyKey,
              unlock.canonical(caller.tenant()),
              Result.class,
              TRANSACTION_TIMEOUT,
              () -> unlocked(caller, unlock));
        });
  }

  private IdempotentOperation.Completed<Result> unlocked(PeopleCaller caller, Unlock unlock) {
    TenantId tenant = caller.tenant();
    schedules.lock(tenant);
    Reasoned reasoned = unlock.reasoned();
    Target target = reasoned.target();
    Units units = units(tenant, target);
    Generation generation = generation(tenant, target);
    generation = timesheets.generation(tenant, generation.id(), true).orElseThrow();
    LockState state = periods.lockState(tenant, generation.id(), true);
    Action current = currentLock(tenant, state);
    Instant now = now();
    if (state.version() != unlock.lockVersion()
        || !current.id().equals(unlock.lockActionId())
        || !fresh(tenant, state, reasoned, unlock.previewDigest(), now)) {
      throw stale();
    }
    Action action =
        new Action(
            UUID.randomUUID(),
            generation.id(),
            state.version() + 1,
            Kind.UNLOCKED,
            current.scope(),
            current.totals(),
            current.evidenceDigest(),
            current.id(),
            reasoned.reason(),
            reasoned.explanation(),
            now);
    LockState after = append(caller, action, state, reasoned.explanationDigest());
    LOG.atInfo()
        .addKeyValue("operation", UNLOCK)
        .addKeyValue("outcome", "unlocked")
        .addKeyValue("transition", "LOCKED_TO_OPEN")
        .addKeyValue("reasonCode", reasoned.reason().name())
        .addKeyValue("timesheets", TimesheetPeriod.countBand(action.totals().timesheets()))
        .log("timesheet_period_unlocked");
    return new IdempotentOperation.Completed<>(
        new Result(
            "UNLOCKED",
            ScopeView.of(action.scope(), units.legalEntity(), units.site()),
            generation.id(),
            LockStateView.of(after),
            ActionView.of(action)),
        action.id(),
        Outcome.UPDATED);
  }

  // ------------------------------------------------------------------------------------------
  // History
  // ------------------------------------------------------------------------------------------

  /**
   * A generated week's lock state and every lock and unlock, newest first.
   *
   * @param caller verified tenant administrator
   * @param generationId path value
   * @return the history, after its disclosure audit committed
   */
  public History history(PeopleCaller caller, String generationId) {
    return observed(
        HISTORY,
        () -> {
          UUID id = TimesheetCommand.id(generationId);
          return read(
              () -> {
                TenantId tenant = caller.tenant();
                Generation generation =
                    timesheets
                        .generation(tenant, id, false)
                        .orElseThrow(TimesheetCommand::notFound);
                UnitView site = kind(unit(tenant, generation.period().siteId()), UnitKind.SITE);
                UnitView legalEntity =
                    site == null || site.legalEntityId() == null
                        ? null
                        : kind(unit(tenant, site.legalEntityId()), UnitKind.LEGAL_ENTITY);
                UUID legalEntityId =
                    legalEntity == null ? generation.period().siteId() : legalEntity.id();
                LockState state = periods.lockState(tenant, id, false);
                List<Action> actions = periods.history(tenant, id);
                Scope scope =
                    actions.isEmpty()
                        ? new Scope(legalEntityId, generation.period(), id, generation.version())
                        : new Scope(
                            actions.get(0).scope().legalEntityId(),
                            generation.period(),
                            id,
                            generation.version());
                StringBuilder text =
                    new StringBuilder("DIVALHR-TIMESHEET-PERIOD-HISTORY\nversion=1\ngeneration=")
                        .append(id)
                        .append('\n');
                actions.forEach(a -> text.append("action=").append(a.id()).append('\n'));
                disclosed(
                    caller, HISTORY, "timesheet-period-history", actions.size(), text.toString());
                return new History(
                    ScopeView.of(scope, legalEntity, site),
                    id,
                    generation.version(),
                    LockStateView.of(state),
                    actions.stream().map(ActionView::of).toList());
              });
        });
  }

  // ------------------------------------------------------------------------------------------
  // Evaluation
  // ------------------------------------------------------------------------------------------

  /** The verified legal entity and site of the request. */
  private record Units(UnitView legalEntity, UnitView site) {}

  /** The legal entity and its site, or the one safe 404. */
  private Units units(TenantId tenant, Target target) {
    Map<UUID, UnitView> resolved =
        directory.resolveIds(tenant, Set.of(target.legalEntityId(), target.siteId()));
    UnitView legalEntity = kind(resolved.get(target.legalEntityId()), UnitKind.LEGAL_ENTITY);
    UnitView site = kind(resolved.get(target.siteId()), UnitKind.SITE);
    if (legalEntity == null
        || site == null
        || site.timezone() == null
        || !target.legalEntityId().equals(site.legalEntityId())) {
      throw TimesheetCommand.notFound();
    }
    return new Units(legalEntity, site);
  }

  /** The generation of the site's week, or the one safe 404 when the week is not generated. */
  private Generation generation(TenantId tenant, Target target) {
    return timesheets
        .generationFor(tenant, target.siteId(), target.weekStart())
        .orElseThrow(TimesheetCommand::notFound);
  }

  /** The week's readiness now; with {@code lock} every timesheet is locked first. */
  private Readiness evaluate(
      TenantId tenant, Target target, Generation generation, LockState state, boolean lock) {
    Scope scope =
        new Scope(
            target.legalEntityId(), generation.period(), generation.id(), generation.version());
    return Readiness.of(scope, periods.members(tenant, generation.id(), lock), state.locked());
  }

  /** Whether the week's current approved evidence was exported (MVP-070E), as a boolean only. */
  private boolean exported(TenantId tenant, Scope scope) {
    TimesheetExport.Scope exportScope =
        new TimesheetExport.Scope(
            scope.legalEntityId(), scope.period(), scope.generationId(), scope.generationVersion());
    List<TimesheetExport.Line> lines =
        TimesheetExport.ordered(exports.eligible(tenant, scope.generationId()));
    return !lines.isEmpty()
        && exports
            .byEvidence(
                tenant,
                scope.period().siteId(),
                scope.period().weekStart(),
                TimesheetExport.evidenceDigest(exportScope, lines))
            .isPresent();
  }

  /** The current LOCKED action, or the stable refusal of an open week. */
  private Action currentLock(TenantId tenant, LockState state) {
    if (!state.locked()) {
      throw new ApiException(ErrorCode.TIMESHEET_PERIOD_NOT_LOCKED, Map.of());
    }
    return periods
        .action(tenant, state.currentActionId())
        .filter(a -> a.kind() == Kind.LOCKED)
        .orElseThrow(() -> new IllegalStateException("a locked period's current lock"));
  }

  /** The action, the projection and the deferred checks; then the audit record and the event. */
  private LockState append(PeopleCaller caller, Action action, LockState before, String digest) {
    TenantId tenant = caller.tenant();
    try {
      periods.append(tenant, action, caller.subject(), before.version() == 0);
      periods.checkConsistent(tenant);
    } catch (DataIntegrityViolationException violated) {
      throw mapped(violated);
    }
    LockState after =
        new LockState(
            action.kind() == Kind.LOCKED
                ? TimesheetPeriod.State.LOCKED
                : TimesheetPeriod.State.OPEN,
            action.sequence(),
            action.id(),
            action.occurredAt());
    recorded(caller, action, digest);
    event(caller, action);
    metrics.record(action.kind() == Kind.LOCKED ? LOCK : UNLOCK, Outcome.UPDATED);
    return after;
  }

  /** The end of the window after the current one (15 to 30 minutes from {@code at}). */
  static Instant expiry(Instant at) {
    return TimesheetExportService.expiry(at);
  }

  /** The lock preview digest: the evidence, the generation and lock versions, the expiry. */
  private static String lockPreviewDigest(
      TenantId tenant, Readiness readiness, LockState state, Instant expiresAt) {
    return Fingerprints.sha256(
        "DIVALHR-TIMESHEET-PERIOD-LOCK-PREVIEW\nversion=1\ntenant="
            + tenant
            + "\nevidence="
            + readiness.evidenceDigest()
            + "\ngenerationVersion="
            + readiness.scope().generationVersion()
            + "\nlockVersion="
            + state.version()
            + "\nlockAction="
            + (state.currentActionId() == null ? "-" : state.currentActionId())
            + "\nexpiresAt="
            + expiresAt
            + "\n");
  }

  /**
   * The unlock preview digest: the current lock, the reason, the explanation digest, the expiry.
   */
  private static String unlockPreviewDigest(
      TenantId tenant, LockState state, Reasoned reasoned, Instant expiresAt) {
    return Fingerprints.sha256(
        "DIVALHR-TIMESHEET-PERIOD-UNLOCK-PREVIEW\nversion=1\ntenant="
            + tenant
            + "\nlockVersion="
            + state.version()
            + "\nlockAction="
            + state.currentActionId()
            + "\nreason="
            + reasoned.reason()
            + "\nexplanationDigest="
            + reasoned.explanationDigest()
            + "\nexpiresAt="
            + expiresAt
            + "\n");
  }

  private static boolean fresh(
      TenantId tenant, Readiness readiness, LockState state, String digest, Instant now) {
    Instant next = expiry(now).minus(WINDOW);
    for (Instant expiresAt : List.of(next, next.plus(WINDOW))) {
      if (expiresAt.isAfter(now)
          && lockPreviewDigest(tenant, readiness, state, expiresAt).equals(digest)) {
        return true;
      }
    }
    return false;
  }

  private static boolean fresh(
      TenantId tenant, LockState state, Reasoned reasoned, String digest, Instant now) {
    Instant next = expiry(now).minus(WINDOW);
    for (Instant expiresAt : List.of(next, next.plus(WINDOW))) {
      if (expiresAt.isAfter(now)
          && unlockPreviewDigest(tenant, state, reasoned, expiresAt).equals(digest)) {
        return true;
      }
    }
    return false;
  }

  // ------------------------------------------------------------------------------------------
  // Audit and outbox
  // ------------------------------------------------------------------------------------------

  /**
   * Bounded metadata only (L71-10); identifiers, the evidence digest, the exact total, the
   * explanation digest and the actor only inside the state digest.
   */
  private void recorded(PeopleCaller caller, Action action, String explanationDigest) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", WorkScheduleService.SCHEMA_VERSION);
    metadata.put("action", action.kind().name());
    metadata.put("lockVersion", action.sequence());
    metadata.put("generationVersion", action.scope().generationVersion());
    metadata.put("week", action.scope().period().weekStart().toString());
    metadata.put("timesheetCount", action.totals().timesheets());
    metadata.put("rowCount", action.totals().rows());
    metadata.put("workedMinuteBand", TimesheetPeriod.minuteBand(action.totals().workedMinutes()));
    if (action.unlockReason() != null) {
      metadata.put("reasonCode", action.unlockReason().name());
    }
    Map<String, Object> state = new TreeMap<>();
    state.put("tenantId", caller.tenant().toString());
    state.put("generationId", action.generationId().toString());
    state.put("actionId", action.id().toString());
    state.put("legalEntityId", action.scope().legalEntityId().toString());
    state.put("siteId", action.scope().period().siteId().toString());
    state.put("periodStartAt", action.scope().period().startAt().toString());
    state.put("periodEndAt", action.scope().period().endAt().toString());
    state.put("evidenceDigest", action.evidenceDigest());
    state.put("employeeCount", action.totals().employees());
    state.put("workedMinutes", action.totals().workedMinutes());
    state.put(
        "lockedActionId",
        action.lockedActionId() == null ? "" : action.lockedActionId().toString());
    state.put("explanationDigest", explanationDigest);
    state.put("occurredAt", action.occurredAt().toString());
    state.put("by", caller.subject());
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            action.occurredAt(),
            caller.subject(),
            action.kind() == Kind.LOCKED ? LOCK : UNLOCK,
            RESOURCE,
            action.generationId(),
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(json.writeValueAsString(state))));
  }

  /**
   * Stable scope and action IDs, versions, bounded counts, the minute band and the instant only.
   */
  private void event(PeopleCaller caller, Action action) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("generationId", action.generationId().toString());
    data.put("actionId", action.id().toString());
    data.put("legalEntityId", action.scope().legalEntityId().toString());
    data.put("siteId", action.scope().period().siteId().toString());
    data.put("weekStart", action.scope().period().weekStart().toString());
    data.put("periodStartAt", action.scope().period().startAt().toString());
    data.put("periodEndAt", action.scope().period().endAt().toString());
    data.put("generationVersion", action.scope().generationVersion());
    data.put("lockVersion", action.sequence());
    data.put("state", action.kind() == Kind.LOCKED ? "LOCKED" : "OPEN");
    data.put("timesheetCount", action.totals().timesheets());
    data.put("employeeCount", action.totals().employees());
    data.put("rowCount", action.totals().rows());
    data.put("workedMinuteBand", TimesheetPeriod.minuteBand(action.totals().workedMinutes()));
    if (action.kind() == Kind.UNLOCKED) {
      data.put("lockedActionId", action.lockedActionId().toString());
      data.put("reasonCode", action.unlockReason().name());
    }
    data.put("occurredAt", action.occurredAt().toString());
    outbox.append(
        new EventEnvelope(
            UUID.randomUUID().toString(),
            action.kind() == Kind.LOCKED ? LOCKED_EVENT : UNLOCKED_EVENT,
            WorkScheduleService.SCHEMA_VERSION,
            caller.tenant().toString(),
            SOURCE,
            action.generationId().toString(),
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
            Fingerprints.sha256(text + "by=" + caller.subject() + "\n")));
  }

  // ------------------------------------------------------------------------------------------
  // Shared
  // ------------------------------------------------------------------------------------------

  private static UnitView kind(UnitView view, UnitKind kind) {
    return view != null && view.kind() == kind ? view : null;
  }

  private UnitView unit(TenantId tenant, UUID id) {
    return directory.resolveIds(tenant, Set.of(id)).get(id);
  }

  private Instant now() {
    return calendar.now().truncatedTo(ChronoUnit.MICROS);
  }

  private static ApiException stale() {
    return new ApiException(ErrorCode.TIMESHEET_PERIOD_PREVIEW_STALE, Map.of());
  }

  /** The stable conflict of a constraint the locks should have made unreachable; no DB text. */
  private static ApiException mapped(DataIntegrityViolationException violated) {
    String constraint = ScheduleConstraintViolations.constraint(violated).orElse("");
    return switch (constraint) {
      case ScheduleConstraintViolations.TIMESHEET_PERIOD_ACTION_APPROVED ->
          new ApiException(ErrorCode.TIMESHEET_PERIOD_NOT_READY, Map.of());
      case ScheduleConstraintViolations.TIMESHEET_PERIOD_ACTION_SEQUENCE,
          ScheduleConstraintViolations.TIMESHEET_PERIOD_ACTION_SCOPE,
          ScheduleConstraintViolations.TIMESHEET_PERIOD_ACTION_UNLOCK,
          ScheduleConstraintViolations.TIMESHEET_PERIOD_LOCK_GUARD,
          ScheduleConstraintViolations.TIMESHEET_PERIOD_LOCK_CURRENT ->
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
          .log("timesheet_period_command_rejected");
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
          .log("timesheet_period_read");
      return result;
    } catch (ApiException rejected) {
      Outcome outcome =
          switch (rejected.code()) {
            case TIMESHEET_TARGET_NOT_FOUND -> Outcome.NOT_FOUND;
            case VALIDATION_FAILED -> Outcome.VALIDATION_FAILED;
            default -> Outcome.STATE_CONFLICT;
          };
      metrics.record(operation, outcome);
      LOG.atInfo()
          .addKeyValue("operation", operation)
          .addKeyValue("outcome", outcome.name().toLowerCase(Locale.ROOT))
          .addKeyValue("code", rejected.code().name())
          .log("timesheet_period_read_rejected");
      throw rejected;
    } catch (RuntimeException failure) {
      metrics.record(operation, Outcome.FAILURE);
      LOG.atWarn()
          .addKeyValue("operation", operation)
          .addKeyValue("outcome", "failure")
          .log("timesheet_period_read_failed");
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
