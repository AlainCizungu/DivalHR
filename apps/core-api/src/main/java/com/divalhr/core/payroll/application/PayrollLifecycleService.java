package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.CreatePayrollInputExportRequest;
import com.divalhr.core.payroll.api.LockPayrollInputRunRequest;
import com.divalhr.core.payroll.api.PayrollInputResponses.Counts;
import com.divalhr.core.payroll.api.PayrollInputResponses.SourceWeekView;
import com.divalhr.core.payroll.api.PayrollInputResponses.Summary;
import com.divalhr.core.payroll.api.PayrollInputResponses.WeekView;
import com.divalhr.core.payroll.api.PayrollLifecycleResponses.Download;
import com.divalhr.core.payroll.api.PayrollLifecycleResponses.ExportPage;
import com.divalhr.core.payroll.api.PayrollLifecycleResponses.ExportResult;
import com.divalhr.core.payroll.api.PayrollLifecycleResponses.ExportView;
import com.divalhr.core.payroll.api.PayrollLifecycleResponses.LockPreview;
import com.divalhr.core.payroll.api.PayrollLifecycleResponses.LockResult;
import com.divalhr.core.payroll.api.PayrollLifecycleResponses.SupersedePreview;
import com.divalhr.core.payroll.api.PayrollLifecycleResponses.SupersedeResult;
import com.divalhr.core.payroll.api.PayrollLifecycleResponses.Version;
import com.divalhr.core.payroll.api.PayrollLifecycleResponses.Versions;
import com.divalhr.core.payroll.api.SupersedePayrollInputRunRequest;
import com.divalhr.core.payroll.application.PayrollInputCommand.Target;
import com.divalhr.core.payroll.application.PayrollInputService.Resolved;
import com.divalhr.core.payroll.domain.PayrollInputs;
import com.divalhr.core.payroll.domain.PayrollInputs.Evaluation;
import com.divalhr.core.payroll.domain.PayrollInputs.Run;
import com.divalhr.core.payroll.domain.PayrollInputs.SourceWeek;
import com.divalhr.core.payroll.domain.PayrollLifecycle;
import com.divalhr.core.payroll.domain.PayrollLifecycle.Export;
import com.divalhr.core.payroll.domain.PayrollLifecycle.Line;
import com.divalhr.core.payroll.domain.PayrollLifecycle.State;
import com.divalhr.core.payroll.internal.JdbcPayrollInputRepository;
import com.divalhr.core.payroll.internal.JdbcPayrollLifecycleRepository;
import com.divalhr.core.payroll.internal.PayrollConstraintViolations;
import com.divalhr.core.platform.access.LockedApprovedTime;
import com.divalhr.core.platform.access.LockedApprovedTime.Week;
import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * The lifecycle of payroll-input runs (MVP-092, Issue #149; ADR 0025): immutable replacement
 * versions after corrected upstream evidence, the final lock and the deterministic worked-time
 * export. Authorization (role {@code tenant-admin} with privileged MFA) has run before this;
 * tenant, actor and scope never come from the request.
 *
 * <p>Monthly-salary boundary: worked minutes never calculate or change salary, overtime, deductions
 * or payment. Nothing here prices, prorates or pays; the export is approved worked-time evidence
 * for payroll preparation and carries the fixed notices.
 *
 * <p>Every command takes the tenant's scheduling lock through the people port first (the lock order
 * of ADR 0023), re-evaluates the upstream evidence, checks the reviewed digest and the expiry, and
 * commits its evidence, audit record and outbox event atomically. The database proves the chain,
 * the latest-version rule, the single lock and the export's exactness as well, so one valid
 * transition wins even when two commands race. Names, employee numbers, subjects, explanations,
 * exact pay values, idempotency keys and digests never enter audit metadata, events, logs, metrics
 * or errors.
 */
@Service
public class PayrollLifecycleService {

  /** Supersede preview (interceptor operation and disclosure audit action). */
  public static final String SUPERSEDE_PREVIEW = "payroll-input.supersede-preview";

  /** Supersession (idempotency operation, interceptor operation and audit action). */
  public static final String SUPERSEDE = "payroll-input.supersede";

  /** Version chain (interceptor operation and disclosure audit action). */
  public static final String VERSIONS = "payroll-input.versions";

  /** Lock preview (interceptor operation and disclosure audit action). */
  public static final String LOCK_PREVIEW = "payroll-input.lock-preview";

  /** Lock (idempotency operation, interceptor operation and audit action). */
  public static final String LOCK = "payroll-input.lock";

  /** Export creation (idempotency operation, interceptor operation and audit action). */
  public static final String EXPORT_CREATE = "payroll-input-export.create";

  /** Export history (interceptor operation and disclosure audit action). */
  public static final String EXPORT_LIST = "payroll-input-export.list";

  /** One export (interceptor operation and disclosure audit action). */
  public static final String EXPORT_READ = "payroll-input-export.read";

  /** Export download (interceptor operation and disclosure audit action). */
  public static final String EXPORT_DOWNLOAD = "payroll-input-export.download";

  /** Outbox event of a supersession. */
  public static final String SUPERSEDED_EVENT = "payroll.payroll-input.superseded.v1";

  /** Outbox event of a lock. */
  public static final String LOCKED_EVENT = "payroll.payroll-input.locked.v1";

  /** Outbox event of an export. */
  public static final String EXPORT_CREATED_EVENT = "payroll.payroll-input-export.created.v1";

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.payroll-input");
  private static final String SOURCE = "core-api/payroll";
  private static final String RUN = "payroll-input-run";
  private static final String EXPORT = "payroll-input-export";
  private static final Duration TRANSACTION_TIMEOUT = Duration.ofSeconds(30);
  private static final IdempotentOperation.Spec SUPERSEDE_SPEC =
      new IdempotentOperation.Spec(SUPERSEDE, "payroll_input", "supersede", "superseded", 200);
  private static final IdempotentOperation.Spec LOCK_SPEC =
      new IdempotentOperation.Spec(LOCK, "payroll_input", "lock", "locked", 200);
  private static final IdempotentOperation.Spec EXPORT_SPEC =
      new IdempotentOperation.Spec(EXPORT_CREATE, "payroll_input_export", "create", "created", 200);
  private static final Pattern LIMIT = Pattern.compile("^[0-9]{1,3}$");
  private static final Pattern UUID_SHAPE =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  private static final int DEFAULT_LIMIT = 20;
  private static final int MAX_LIMIT = 100;

  private final PayrollInputService inputs;
  private final IdempotentOperation operations;
  private final LockedApprovedTime approvedTime;
  private final JdbcPayrollInputRepository runs;
  private final JdbcPayrollLifecycleRepository lifecycle;
  private final OrganizationPlacementDirectory units;
  private final CursorCodec cursors;
  private final Clock clock;
  private final AuditRecorder audit;
  private final OutboxWriter outbox;
  private final JsonMapper json;

  /**
   * Creates the service.
   *
   * @param inputs the payroll-input service (evaluation helpers, observed reads)
   * @param operations shared idempotent command flow
   * @param approvedTime the people module's locked-approved-time port
   * @param runs run storage
   * @param lifecycle lifecycle storage
   * @param units the tenant module's unit port
   * @param cursors signed cursors
   * @param clock the platform clock
   * @param audit audit recorder
   * @param outbox outbox writer
   * @param json JSON mapper
   */
  public PayrollLifecycleService(
      PayrollInputService inputs,
      IdempotentOperation operations,
      LockedApprovedTime approvedTime,
      JdbcPayrollInputRepository runs,
      JdbcPayrollLifecycleRepository lifecycle,
      OrganizationPlacementDirectory units,
      CursorCodec cursors,
      Clock clock,
      AuditRecorder audit,
      OutboxWriter outbox,
      JsonMapper json) {
    this.inputs = inputs;
    this.operations = operations;
    this.approvedTime = approvedTime;
    this.runs = runs;
    this.lifecycle = lifecycle;
    this.units = units;
    this.cursors = cursors;
    this.clock = clock;
    this.audit = audit;
    this.outbox = outbox;
    this.json = json;
  }

  // ------------------------------------------------------------------------------------------
  // Versions
  // ------------------------------------------------------------------------------------------

  /**
   * What a superseding version of a run would hold now.
   *
   * @param caller verified tenant administrator
   * @param runId path value
   * @return the preview, after its disclosure audit committed
   */
  public SupersedePreview supersedePreview(PayrollCaller caller, String runId) {
    return inputs.observed(
        SUPERSEDE_PREVIEW,
        () -> {
          UUID id = PayrollInputCommand.id(runId);
          return inputs.read(
              () -> {
                TenantId tenant = caller.tenant();
                approvedTime.lockForPayrollInputs(tenant);
                Loaded loaded = load(tenant, id);
                List<String> blockers =
                    PayrollLifecycle.supersedeBlockers(
                        loaded.state(), loaded.run(), loaded.evaluation());
                Instant expiresAt = PayrollInputService.expiry(inputs.now());
                disclose(
                    caller,
                    SUPERSEDE_PREVIEW,
                    "payroll-input-supersede-preview",
                    id,
                    loaded.evaluation().totals().employees());
                Map<UUID, SourceWeek> sources = new HashMap<>();
                loaded.evaluation().sourceWeeks().forEach(w -> sources.put(w.generationId(), w));
                List<WeekView> weeks = new ArrayList<>();
                for (Week week : loaded.evaluation().weeks()) {
                  SourceWeek source =
                      week.generation() == null ? null : sources.get(week.generation().id());
                  weeks.add(
                      WeekView.of(week, source, loaded.resolved().sites().get(week.siteId())));
                }
                return new SupersedePreview(
                    Summary.of(loaded.run(), loaded.resolved().legalEntity(), loaded.state()),
                    weeks,
                    Counts.of(loaded.evaluation().totals()),
                    blockers,
                    blockers.isEmpty(),
                    digest("SUPERSEDE", tenant, loaded, blockers, expiresAt),
                    expiresAt);
              });
        });
  }

  /**
   * Creates the next version of a run from a reviewed preview, or replays an identical command.
   *
   * @param caller verified tenant administrator
   * @param runId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body reason, digest and confirmation
   * @return the result
   */
  public IdempotentOperation.Result<SupersedeResult> supersede(
      PayrollCaller caller,
      String runId,
      String idempotencyKey,
      SupersedePayrollInputRunRequest body) {
    return inputs.command(
        SUPERSEDE,
        () -> {
          PayrollLifecycleCommand.Supersede command =
              operations.validated(
                  SUPERSEDE_SPEC,
                  () -> PayrollLifecycleCommand.supersede(idempotencyKey, runId, body));
          return operations.execute(
              SUPERSEDE_SPEC,
              caller.subject(),
              idempotencyKey,
              command.canonical(caller.tenant()),
              SupersedeResult.class,
              TRANSACTION_TIMEOUT,
              () -> superseded(caller, command));
        });
  }

  private IdempotentOperation.Completed<SupersedeResult> superseded(
      PayrollCaller caller, PayrollLifecycleCommand.Supersede command) {
    TenantId tenant = caller.tenant();
    approvedTime.lockForPayrollInputs(tenant);
    Loaded loaded = load(tenant, command.runId());
    Run previous = loaded.run();
    if (loaded.state().locked()) {
      throw new ApiException(ErrorCode.PAYROLL_INPUT_RUN_LOCKED, Map.of());
    }
    if (!loaded.state().latest()) {
      throw new ApiException(ErrorCode.PAYROLL_INPUT_NOT_LATEST, Map.of());
    }
    Instant now = inputs.now();
    List<String> blockers =
        PayrollLifecycle.supersedeBlockers(loaded.state(), previous, loaded.evaluation());
    if (!fresh("SUPERSEDE", tenant, loaded, blockers, command.previewDigest(), now)) {
      throw PayrollInputService.stale();
    }
    if (!blockers.isEmpty()) {
      throw new ApiException(ErrorCode.PAYROLL_INPUT_NOT_READY, Map.of());
    }
    Evaluation evaluation = loaded.evaluation();
    Run next =
        new Run(
            UUID.randomUUID(),
            evaluation.scope(),
            previous.version() + 1,
            evaluation.sourceWeeks().size(),
            evaluation.totals(),
            evaluation.exclusions(),
            evaluation.evidenceDigest(),
            now);
    try {
      runs.insert(tenant, next, evaluation, caller.subject());
      lifecycle.insertSupersession(
          tenant,
          next.id(),
          previous.id(),
          command.reason(),
          command.explanation(),
          now,
          caller.subject());
      runs.checkConsistent();
    } catch (DataIntegrityViolationException violated) {
      throw mapped(violated);
    }
    State state =
        new State(
            next.id(),
            next.version(),
            previous.id(),
            command.reason(),
            command.explanation(),
            null,
            true,
            null);
    Map<String, Object> metadata = runMetadata(next);
    metadata.put("supersedesRunVersion", previous.version());
    metadata.put("reasonCode", command.reason().name());
    record(
        caller,
        SUPERSEDE,
        RUN,
        next.id(),
        now,
        metadata,
        stateOf(caller, next, "supersedes=" + previous.id(), now));
    Map<String, Object> data = runData(next, now);
    data.put("supersedesRunId", previous.id().toString());
    data.put("supersedesRunVersion", previous.version());
    data.put("reasonCode", command.reason().name());
    data.put("status", "SUPERSEDED");
    emit(caller, SUPERSEDED_EVENT, next.id(), now, data);
    LOG.atInfo()
        .addKeyValue("operation", SUPERSEDE)
        .addKeyValue("outcome", "superseded")
        .addKeyValue("reason", command.reason().name())
        .addKeyValue("rows", PayrollInputs.countBand(next.totals().rows()))
        .log("payroll_input_superseded");
    return new IdempotentOperation.Completed<>(
        new SupersedeResult(
            "SUPERSEDED", Summary.of(next, loaded.resolved().legalEntity(), state), previous.id()),
        next.id(),
        Outcome.CREATED);
  }

  /**
   * The whole version chain of the scope of a run, oldest first.
   *
   * @param caller verified tenant administrator
   * @param runId path value (any version of the scope)
   * @return the chain, after its disclosure audit committed
   */
  public Versions versions(PayrollCaller caller, String runId) {
    return inputs.observed(
        VERSIONS,
        () -> {
          UUID id = PayrollInputCommand.id(runId);
          return inputs.read(
              () -> {
                TenantId tenant = caller.tenant();
                Run run = runs.run(tenant, id).orElseThrow(PayrollInputCommand::notFound);
                List<Run> chain = runs.versions(tenant, run);
                Map<UUID, State> states =
                    lifecycle.states(tenant, chain.stream().map(Run::id).toList());
                UnitView entity = entity(tenant, run.scope().legalEntityId());
                disclose(caller, VERSIONS, "payroll-input-versions", id, chain.size());
                List<Version> items = new ArrayList<>();
                for (Run version : chain) {
                  State state = states.get(version.id());
                  if (state == null) {
                    throw new IllegalStateException("a payroll-input version has no state");
                  }
                  items.add(new Version(Summary.of(version, entity, state), state.explanation()));
                }
                return new Versions(items);
              });
        });
  }

  // ------------------------------------------------------------------------------------------
  // Lock
  // ------------------------------------------------------------------------------------------

  /**
   * What locking a run would make final.
   *
   * @param caller verified tenant administrator
   * @param runId path value
   * @return the preview, after its disclosure audit committed
   */
  public LockPreview lockPreview(PayrollCaller caller, String runId) {
    return inputs.observed(
        LOCK_PREVIEW,
        () -> {
          UUID id = PayrollInputCommand.id(runId);
          return inputs.read(
              () -> {
                TenantId tenant = caller.tenant();
                approvedTime.lockForPayrollInputs(tenant);
                Loaded loaded = load(tenant, id);
                Run run = loaded.run();
                List<String> blockers =
                    PayrollLifecycle.lockBlockers(loaded.state(), run, loaded.evaluation());
                Instant expiresAt = PayrollInputService.expiry(inputs.now());
                List<SourceWeek> weeks = runs.weeks(tenant, run.id());
                Set<UUID> siteIds = new HashSet<>();
                weeks.forEach(w -> siteIds.add(w.siteId()));
                Map<UUID, UnitView> sites =
                    siteIds.isEmpty() ? Map.of() : units.resolveIds(tenant, siteIds);
                disclose(
                    caller, LOCK_PREVIEW, "payroll-input-lock-preview", id, run.totals().rows());
                UUID supersedes = loaded.state().supersedesRunId();
                return new LockPreview(
                    Summary.of(run, loaded.resolved().legalEntity(), loaded.state()),
                    weeks.stream()
                        .map(
                            w ->
                                SourceWeekView.of(
                                    w,
                                    PayrollInputService.kind(sites.get(w.siteId()), UnitKind.SITE)))
                        .toList(),
                    Counts.of(run.totals()),
                    PayrollInputs.minuteBand(run.totals().workedMinutes()),
                    supersedes,
                    supersedes == null ? null : run.version() - 1,
                    blockers,
                    blockers.isEmpty(),
                    digest("LOCK", tenant, loaded, blockers, expiresAt),
                    expiresAt);
              });
        });
  }

  /**
   * Locks a run from a reviewed preview, or replays an identical command.
   *
   * @param caller verified tenant administrator
   * @param runId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body digest and confirmation
   * @return the result
   */
  public IdempotentOperation.Result<LockResult> lock(
      PayrollCaller caller, String runId, String idempotencyKey, LockPayrollInputRunRequest body) {
    return inputs.command(
        LOCK,
        () -> {
          PayrollLifecycleCommand.Lock command =
              operations.validated(
                  LOCK_SPEC, () -> PayrollLifecycleCommand.lock(idempotencyKey, runId, body));
          return operations.execute(
              LOCK_SPEC,
              caller.subject(),
              idempotencyKey,
              command.canonical(caller.tenant()),
              LockResult.class,
              TRANSACTION_TIMEOUT,
              () -> locked(caller, command));
        });
  }

  private IdempotentOperation.Completed<LockResult> locked(
      PayrollCaller caller, PayrollLifecycleCommand.Lock command) {
    TenantId tenant = caller.tenant();
    approvedTime.lockForPayrollInputs(tenant);
    Loaded loaded = load(tenant, command.runId());
    Run run = loaded.run();
    if (loaded.state().locked()) {
      throw new ApiException(ErrorCode.PAYROLL_INPUT_RUN_LOCKED, Map.of());
    }
    if (!loaded.state().latest()) {
      throw new ApiException(ErrorCode.PAYROLL_INPUT_NOT_LATEST, Map.of());
    }
    Instant now = inputs.now();
    List<String> blockers = PayrollLifecycle.lockBlockers(loaded.state(), run, loaded.evaluation());
    if (!fresh("LOCK", tenant, loaded, blockers, command.previewDigest(), now)) {
      throw PayrollInputService.stale();
    }
    if (!blockers.isEmpty()) {
      throw new ApiException(ErrorCode.PAYROLL_INPUT_NOT_READY, Map.of());
    }
    UUID lockId = UUID.randomUUID();
    try {
      lifecycle.insertLock(tenant, lockId, run, now, caller.subject());
      lifecycle.checkConsistent();
    } catch (DataIntegrityViolationException violated) {
      throw mapped(violated);
    }
    State state =
        new State(
            run.id(),
            run.version(),
            loaded.state().supersedesRunId(),
            loaded.state().reason(),
            loaded.state().explanation(),
            new PayrollLifecycle.Lock(lockId, run.id(), now),
            true,
            null);
    Map<String, Object> metadata = runMetadata(run);
    metadata.put("lockVersion", 1);
    record(caller, LOCK, RUN, run.id(), now, metadata, stateOf(caller, run, "lock=" + lockId, now));
    Map<String, Object> data = runData(run, now);
    data.put("lockActionId", lockId.toString());
    data.put("lockVersion", 1);
    data.put("status", "LOCKED");
    emit(caller, LOCKED_EVENT, run.id(), now, data);
    LOG.atInfo()
        .addKeyValue("operation", LOCK)
        .addKeyValue("outcome", "locked")
        .addKeyValue("rows", PayrollInputs.countBand(run.totals().rows()))
        .log("payroll_input_locked");
    return new IdempotentOperation.Completed<>(
        new LockResult("LOCKED", Summary.of(run, loaded.resolved().legalEntity(), state)),
        run.id(),
        Outcome.UPDATED);
  }

  // ------------------------------------------------------------------------------------------
  // Export
  // ------------------------------------------------------------------------------------------

  /**
   * Creates the worked-time export of a locked run, returns the run's existing export, or replays
   * an identical command.
   *
   * @param caller verified tenant administrator
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the run and confirmation
   * @return the result
   */
  public IdempotentOperation.Result<ExportResult> createExport(
      PayrollCaller caller, String idempotencyKey, CreatePayrollInputExportRequest body) {
    return inputs.command(
        EXPORT_CREATE,
        () -> {
          PayrollLifecycleCommand.Export command =
              operations.validated(
                  EXPORT_SPEC, () -> PayrollLifecycleCommand.export(idempotencyKey, body));
          return operations.execute(
              EXPORT_SPEC,
              caller.subject(),
              idempotencyKey,
              command.canonical(caller.tenant()),
              ExportResult.class,
              TRANSACTION_TIMEOUT,
              () -> exported(caller, command));
        });
  }

  private IdempotentOperation.Completed<ExportResult> exported(
      PayrollCaller caller, PayrollLifecycleCommand.Export command) {
    TenantId tenant = caller.tenant();
    Run run = runs.run(tenant, command.runId()).orElseThrow(PayrollInputCommand::notFound);
    // The row lock on the run's final lock serializes the creation: a concurrent command waits and
    // then finds the export.
    UUID lockId =
        lifecycle
            .lockForExport(tenant, run.id())
            .orElseThrow(() -> new ApiException(ErrorCode.PAYROLL_INPUT_NOT_LOCKED, Map.of()));
    UnitView entity = entity(tenant, run.scope().legalEntityId());
    Optional<Export> existing = lifecycle.exportOfRun(tenant, run.id());
    if (existing.isPresent()) {
      return new IdempotentOperation.Completed<>(
          new ExportResult("EXISTING", ExportView.of(existing.get(), entity)),
          existing.get().id(),
          Outcome.UNCHANGED);
    }
    List<Line> lines = PayrollLifecycle.ordered(lifecycle.sourceLines(tenant, run.id()));
    if (lines.size() > PayrollLifecycle.MAX_EXPORT_ROWS) {
      throw new ApiException(ErrorCode.PAYROLL_INPUT_EXPORT_TOO_LARGE, Map.of());
    }
    if (lines.isEmpty()) {
      throw new IllegalStateException("a locked payroll-input run holds no rows");
    }
    Instant now = inputs.now();
    Export export =
        new Export(
            UUID.randomUUID(),
            run.id(),
            run.version(),
            run.scope(),
            lockId,
            1,
            run.totals().employees(),
            run.totals().rows(),
            run.totals().workedMinutes(),
            PayrollLifecycle.sha256(PayrollLifecycle.csv(lines)),
            now);
    try {
      lifecycle.insertExport(tenant, export, lines, caller.subject());
      lifecycle.checkConsistent();
    } catch (DataIntegrityViolationException violated) {
      throw mapped(violated);
    }
    Map<String, Object> metadata = runMetadata(run);
    metadata.put("exportSchemaVersion", PayrollLifecycle.EXPORT_SCHEMA_VERSION);
    record(
        caller,
        EXPORT_CREATE,
        EXPORT,
        export.id(),
        now,
        metadata,
        stateOf(caller, run, "export=" + export.id() + "\ncsv=" + export.csvSha256(), now));
    Map<String, Object> data = runData(run, now);
    data.put("exportId", export.id().toString());
    data.put("exportSchemaVersion", PayrollLifecycle.EXPORT_SCHEMA_VERSION);
    data.put("lockActionId", lockId.toString());
    data.put("lockVersion", 1);
    data.put("status", "CREATED");
    emit(caller, EXPORT_CREATED_EVENT, export.id(), now, data);
    LOG.atInfo()
        .addKeyValue("operation", EXPORT_CREATE)
        .addKeyValue("outcome", "created")
        .addKeyValue("rows", PayrollInputs.countBand(export.rows()))
        .log("payroll_input_export_created");
    return new IdempotentOperation.Completed<>(
        new ExportResult("CREATED", ExportView.of(export, entity)), export.id(), Outcome.CREATED);
  }

  /**
   * One page of the tenant's exports, newest first.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw filter, or {@code null}
   * @param cursor opaque cursor, or {@code null}
   * @param limit page size (1 to 100, default 20)
   * @return the page, after its disclosure audit committed
   */
  public ExportPage exports(
      PayrollCaller caller, String legalEntityId, String cursor, String limit) {
    return inputs.observed(
        EXPORT_LIST,
        () -> {
          FieldErrors errors = new FieldErrors();
          UUID filter = null;
          if (legalEntityId != null) {
            if (UUID_SHAPE.matcher(legalEntityId).matches()) {
              filter = UUID.fromString(legalEntityId.toLowerCase(Locale.ROOT));
            } else {
              errors.add("legalEntityId", Constraint.FORMAT);
            }
          }
          int size = limit(limit, errors);
          errors.throwIfAny();
          UUID legalEntity = filter;
          return inputs.read(
              () -> {
                TenantId tenant = caller.tenant();
                Map<String, String> filters = new TreeMap<>();
                filters.put("legalEntity", legalEntity == null ? "-" : legalEntity.toString());
                filters.put("limit", Integer.toString(size));
                CursorScope scope = new CursorScope(EXPORT_LIST, tenant, filters);
                Instant afterAt = null;
                UUID afterId = null;
                if (cursor != null) {
                  KeysetPosition position = cursors.decode(cursor, scope);
                  afterAt = createdAt(position.code());
                  afterId = position.id();
                }
                List<Export> rows = lifecycle.page(tenant, legalEntity, afterAt, afterId, size + 1);
                List<Export> shown = rows.subList(0, Math.min(rows.size(), size));
                String next =
                    rows.size() > size
                        ? cursors.encode(
                            scope,
                            new KeysetPosition(
                                "G" + micros(shown.get(shown.size() - 1).createdAt()),
                                shown.get(shown.size() - 1).id()))
                        : null;
                Set<UUID> ids = new HashSet<>();
                shown.forEach(e -> ids.add(e.scope().legalEntityId()));
                Map<UUID, UnitView> resolved =
                    ids.isEmpty() ? Map.of() : units.resolveIds(tenant, ids);
                disclose(
                    caller, EXPORT_LIST, "payroll-input-exports", tenant.value(), shown.size());
                return new ExportPage(
                    shown.stream()
                        .map(
                            e ->
                                ExportView.of(
                                    e,
                                    PayrollInputService.kind(
                                        resolved.get(e.scope().legalEntityId()),
                                        UnitKind.LEGAL_ENTITY)))
                        .toList(),
                    next);
              });
        });
  }

  /**
   * One export.
   *
   * @param caller verified tenant administrator
   * @param exportId path value
   * @return the export, after its disclosure audit committed
   */
  public ExportView exportView(PayrollCaller caller, String exportId) {
    return inputs.observed(
        EXPORT_READ,
        () -> {
          UUID id = PayrollInputCommand.id(exportId);
          return inputs.read(
              () -> {
                TenantId tenant = caller.tenant();
                Export export =
                    lifecycle.export(tenant, id).orElseThrow(PayrollInputCommand::notFound);
                disclose(caller, EXPORT_READ, "payroll-input-export", id, export.rows());
                return ExportView.of(export, entity(tenant, export.scope().legalEntityId()));
              });
        });
  }

  /**
   * The CSV bytes of an export, rebuilt from its immutable rows and checked against the stored
   * SHA-256 before anything is served.
   *
   * @param caller verified tenant administrator
   * @param exportId path value
   * @return the download, after its disclosure audit committed
   */
  public Download csv(PayrollCaller caller, String exportId) {
    return download(caller, exportId, "csv");
  }

  /**
   * The canonical manifest of an export.
   *
   * @param caller verified tenant administrator
   * @param exportId path value
   * @return the download, after its disclosure audit committed
   */
  public Download manifest(PayrollCaller caller, String exportId) {
    return download(caller, exportId, "manifest");
  }

  private Download download(PayrollCaller caller, String exportId, String view) {
    return inputs.observed(
        EXPORT_DOWNLOAD,
        () -> {
          UUID id = PayrollInputCommand.id(exportId);
          return inputs.read(
              () -> {
                TenantId tenant = caller.tenant();
                Export export =
                    lifecycle.export(tenant, id).orElseThrow(PayrollInputCommand::notFound);
                byte[] bytes;
                if ("csv".equals(view)) {
                  List<Line> lines = lifecycle.lines(tenant, export.id());
                  bytes = PayrollLifecycle.csv(lines);
                  if (lines.size() != export.rows()
                      || !PayrollLifecycle.sha256(bytes).equals(export.csvSha256())) {
                    // The stored evidence no longer reproduces the recorded bytes: serve nothing.
                    throw new IllegalStateException("a payroll-input export lost its integrity");
                  }
                } else {
                  bytes = PayrollLifecycle.manifest(tenant, export);
                }
                Instant now = inputs.now();
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("schemaVersion", PayrollInputs.SCHEMA_VERSION);
                metadata.put("view", "payroll-input-export-" + view);
                metadata.put("rowCount", export.rows());
                metadata.put("workedMinuteBand", PayrollInputs.minuteBand(export.workedMinutes()));
                audit.record(
                    new AuditEvent(
                        UUID.randomUUID(),
                        now,
                        caller.subject(),
                        EXPORT_DOWNLOAD,
                        EXPORT,
                        export.id(),
                        tenant.value(),
                        "SUCCESS",
                        caller.correlationId(),
                        metadata,
                        Fingerprints.sha256(
                            "DIVALHR-PAYROLL-INPUT-DOWNLOAD\nversion=1\nexport="
                                + export.id()
                                + "\nview="
                                + view
                                + "\nby="
                                + caller.subject()
                                + "\n")));
                return new Download(
                    PayrollLifecycle.filename(export, "csv".equals(view) ? "csv" : "manifest.json"),
                    bytes);
              });
        });
  }

  // ------------------------------------------------------------------------------------------
  // Shared
  // ------------------------------------------------------------------------------------------

  /** A run with its state and the upstream evidence now. */
  private record Loaded(Run run, State state, Resolved resolved, Evaluation evaluation) {}

  private Loaded load(TenantId tenant, UUID runId) {
    Run run = runs.run(tenant, runId).orElseThrow(PayrollInputCommand::notFound);
    State state = lifecycle.state(tenant, runId).orElseThrow(PayrollInputCommand::notFound);
    Resolved resolved =
        inputs.resolve(
            tenant,
            new Target(
                run.scope().legalEntityId(), run.scope().periodStart(), run.scope().periodEnd()));
    return new Loaded(run, state, resolved, inputs.evaluate(tenant, resolved));
  }

  private UnitView entity(TenantId tenant, UUID legalEntityId) {
    return PayrollInputService.kind(
        units.resolveIds(tenant, Set.of(legalEntityId)).get(legalEntityId), UnitKind.LEGAL_ENTITY);
  }

  /** The preview digest: bound to the purpose, the run, its state, the evidence and the expiry. */
  private static String digest(
      String purpose, TenantId tenant, Loaded loaded, List<String> blockers, Instant expiresAt) {
    return Fingerprints.sha256(
        "DIVALHR-PAYROLL-INPUT-"
            + purpose
            + "\nversion=1\ntenant="
            + tenant
            + "\nrun="
            + loaded.run().id()
            + "\nrunVersion="
            + loaded.run().version()
            + "\nrunEvidence="
            + loaded.run().evidenceDigest()
            + "\nevidence="
            + loaded.evaluation().evidenceDigest()
            + "\nsources="
            + PayrollInputService.versionsDigest(loaded.evaluation())
            + "\nlatest="
            + loaded.state().latest()
            + "\nlocked="
            + loaded.state().locked()
            + "\nblockers="
            + String.join(",", blockers)
            + "\nexpiresAt="
            + expiresAt
            + "\n");
  }

  /** Whether a digest matches the state now and has not expired (the 15 to 30 minute window). */
  private static boolean fresh(
      String purpose,
      TenantId tenant,
      Loaded loaded,
      List<String> blockers,
      String reviewed,
      Instant now) {
    Instant next = PayrollInputService.expiry(now).minus(PayrollInputService.WINDOW);
    for (Instant expiresAt : List.of(next, next.plus(PayrollInputService.WINDOW))) {
      if (expiresAt.isAfter(now)
          && digest(purpose, tenant, loaded, blockers, expiresAt).equals(reviewed)) {
        return true;
      }
    }
    return false;
  }

  private static Map<String, Object> runMetadata(Run run) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", PayrollInputs.SCHEMA_VERSION);
    metadata.put("runVersion", run.version());
    metadata.put("periodStart", run.scope().periodStart().toString());
    metadata.put("periodEnd", run.scope().periodEnd().toString());
    metadata.put("weekCount", run.weekCount());
    metadata.put("employeeCount", run.totals().employees());
    metadata.put("timesheetCount", run.totals().timesheets());
    metadata.put("rowCount", run.totals().rows());
    metadata.put("workedMinuteBand", PayrollInputs.minuteBand(run.totals().workedMinutes()));
    return metadata;
  }

  /** Stable IDs, dates, versions, counts and the minute band; never a digest or an explanation. */
  private static Map<String, Object> runData(Run run, Instant now) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("runId", run.id().toString());
    data.put("runVersion", run.version());
    data.put("schemaVersion", PayrollInputs.SCHEMA_VERSION);
    data.put("legalEntityId", run.scope().legalEntityId().toString());
    data.put("periodStart", run.scope().periodStart().toString());
    data.put("periodEnd", run.scope().periodEnd().toString());
    data.put("weekCount", run.weekCount());
    data.put("employeeCount", run.totals().employees());
    data.put("timesheetCount", run.totals().timesheets());
    data.put("rowCount", run.totals().rows());
    data.put("workedMinuteBand", PayrollInputs.minuteBand(run.totals().workedMinutes()));
    data.put("occurredAt", now.toString());
    return data;
  }

  /** The integrity reference: identifiers, the evidence digest and the actor, hashed. */
  private String stateOf(PayrollCaller caller, Run run, String detail, Instant now) {
    Map<String, Object> state = new TreeMap<>();
    state.put("tenantId", caller.tenant().toString());
    state.put("runId", run.id().toString());
    state.put("runVersion", run.version());
    state.put("evidenceDigest", run.evidenceDigest());
    state.put("workedMinutes", run.totals().workedMinutes());
    state.put("detail", detail);
    state.put("at", now.toString());
    state.put("by", caller.subject());
    return Fingerprints.sha256(json.writeValueAsString(state));
  }

  private void record(
      PayrollCaller caller,
      String action,
      String resourceType,
      UUID resourceId,
      Instant now,
      Map<String, Object> metadata,
      String stateSha256) {
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            now,
            caller.subject(),
            action,
            resourceType,
            resourceId,
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            stateSha256));
  }

  private void emit(
      PayrollCaller caller, String type, UUID subject, Instant now, Map<String, Object> data) {
    outbox.append(
        new EventEnvelope(
            UUID.randomUUID().toString(),
            type,
            PayrollInputs.SCHEMA_VERSION,
            caller.tenant().toString(),
            SOURCE,
            subject.toString(),
            now.toString(),
            caller.correlationId(),
            null,
            data));
  }

  /** The fail-closed disclosure audit: the view and count; identifiers only in the digest. */
  private void disclose(PayrollCaller caller, String action, String view, UUID target, int count) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", PayrollInputs.SCHEMA_VERSION);
    metadata.put("view", view);
    metadata.put("page", "first");
    metadata.put("resultCount", count);
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            inputs.now(),
            caller.subject(),
            action,
            "organization",
            caller.tenant().value(),
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(
                "DIVALHR-PAYROLL-INPUT-DISCLOSURE\nversion=1\naction="
                    + action
                    + "\ntarget="
                    + target
                    + "\nby="
                    + caller.subject()
                    + "\n")));
  }

  /** The stable conflict of a constraint the locks should have made unreachable; no DB text. */
  private static ApiException mapped(DataIntegrityViolationException violated) {
    String constraint = PayrollConstraintViolations.constraint(violated).orElse("");
    return switch (constraint) {
      case PayrollConstraintViolations.RUN_VERSION,
          PayrollConstraintViolations.SUPERSESSION_VALID,
          PayrollConstraintViolations.SUPERSESSION_ONCE,
          PayrollConstraintViolations.LOCK_LATEST ->
          new ApiException(ErrorCode.PAYROLL_INPUT_NOT_LATEST, Map.of());
      case PayrollConstraintViolations.SUPERSESSION_LOCKED, PayrollConstraintViolations.LOCK_ONE ->
          new ApiException(ErrorCode.PAYROLL_INPUT_RUN_LOCKED, Map.of());
      case PayrollConstraintViolations.LOCK_VALID,
          PayrollConstraintViolations.RUN_EXACT,
          PayrollConstraintViolations.RUN_CHAIN ->
          PayrollInputService.stale();
      case PayrollConstraintViolations.EXPORT_ONE ->
          new ApiException(ErrorCode.PAYROLL_INPUT_NOT_LOCKED, Map.of());
      default -> throw violated;
    };
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

  private static long micros(Instant instant) {
    return ChronoUnit.MICROS.between(Instant.EPOCH, instant);
  }

  private static Instant createdAt(String code) {
    if (code == null || !code.matches("^G[0-9]{1,18}$")) {
      throw new ApiException(ErrorCode.CURSOR_INVALID, Map.of());
    }
    return Instant.EPOCH.plus(Long.parseLong(code.substring(1)), ChronoUnit.MICROS);
  }
}
