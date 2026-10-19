package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.GeneratePayrollInputsRequest;
import com.divalhr.core.payroll.api.PayrollInputPreviewRequest;
import com.divalhr.core.payroll.api.PayrollInputResponses;
import com.divalhr.core.payroll.api.PayrollInputResponses.Counts;
import com.divalhr.core.payroll.api.PayrollInputResponses.Detail;
import com.divalhr.core.payroll.api.PayrollInputResponses.EmployeeView;
import com.divalhr.core.payroll.api.PayrollInputResponses.Page;
import com.divalhr.core.payroll.api.PayrollInputResponses.Preview;
import com.divalhr.core.payroll.api.PayrollInputResponses.Result;
import com.divalhr.core.payroll.api.PayrollInputResponses.ScopeView;
import com.divalhr.core.payroll.api.PayrollInputResponses.SourceWeekView;
import com.divalhr.core.payroll.api.PayrollInputResponses.Summary;
import com.divalhr.core.payroll.api.PayrollInputResponses.WeekView;
import com.divalhr.core.payroll.application.PayrollInputCommand.Generate;
import com.divalhr.core.payroll.application.PayrollInputCommand.Target;
import com.divalhr.core.payroll.domain.PayrollInputs;
import com.divalhr.core.payroll.domain.PayrollInputs.Evaluation;
import com.divalhr.core.payroll.domain.PayrollInputs.Run;
import com.divalhr.core.payroll.domain.PayrollInputs.Scope;
import com.divalhr.core.payroll.domain.PayrollInputs.SourceWeek;
import com.divalhr.core.payroll.internal.JdbcPayrollInputRepository;
import com.divalhr.core.payroll.internal.PayrollConstraintViolations;
import com.divalhr.core.platform.access.LockedApprovedTime;
import com.divalhr.core.platform.access.LockedApprovedTime.ApprovedRow;
import com.divalhr.core.platform.access.LockedApprovedTime.Week;
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
import com.divalhr.core.platform.tenancy.OrganizationDirectory;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.UnitKind;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.UnitView;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
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
 * Time-based payroll inputs (MVP-090A, Issue #141; ADR 0023). Authorization (role {@code
 * tenant-admin} with privileged MFA) has run before this; tenant, actor and scope never come from
 * the request.
 *
 * <p>A preview resolves the legal entity (one safe 404 for an unknown, foreign or malformed one),
 * reads the business time zone, asks the people module's port for every week of the legal entity's
 * sites that matters in the period (generated, or holding scheduled work) and, for every locked
 * week whose evidence is unchanged, its approved rows; it evaluates the rows within the period
 * ({@link PayrollInputs#evaluate}) and returns the covered weeks, the counts, the exclusions and
 * blockers with stable codes, the source versions, the preview digest and its expiry. Nothing is
 * written by a preview but its disclosure audit.
 *
 * <p>A generation is one bounded transaction: idempotency, the tenant's scheduling lock through the
 * port (so no lock, unlock, correction, refresh or approval interleaves), the evaluation again, the
 * digests compared (a changed evidence or source version, or an expired preview, is one stable
 * conflict), then the immutable run, source weeks, employee inputs and source rows (the deferred
 * exactness check proves the reconciliation), the audit record ({@value #GENERATE}) and the outbox
 * event ({@value #GENERATED_EVENT}). A run of this legal entity and period that already exists with
 * identical evidence is returned (outcome EXISTING); with different evidence it is a conflict:
 * generated evidence is never replaced. Names, employee numbers, subjects, digests of previews and
 * idempotency keys never enter audit metadata, events, logs, metrics or errors.
 */
@Service
public class PayrollInputService {

  /** Preview (interceptor operation and disclosure audit action). */
  public static final String PREVIEW = "payroll-input.preview";

  /** Generation (idempotency operation, interceptor operation and audit action). */
  public static final String GENERATE = "payroll-input.generate";

  /** History (interceptor operation and disclosure audit action). */
  public static final String LIST = "payroll-input.list";

  /** One run (interceptor operation and disclosure audit action). */
  public static final String READ = "payroll-input.read";

  /** Outbox event of a generation. */
  public static final String GENERATED_EVENT = "payroll.payroll-input.generated.v1";

  /** Per-subject reads. */
  public static final String SUBJECT_READ_BUCKET = "payroll-input-read";

  /** Per-subject writes (previews included). */
  public static final String SUBJECT_WRITE_BUCKET = "payroll-input-write";

  /** Per-tenant writes. */
  public static final String TENANT_WRITE_BUCKET = "payroll-input-write";

  /** The preview window: a preview expires at the end of the next window. */
  static final Duration WINDOW = Duration.ofMinutes(15);

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.payroll-input");
  private static final String SOURCE = "core-api/payroll";
  private static final String RESOURCE = "payroll-input-run";
  private static final Duration TRANSACTION_TIMEOUT = Duration.ofSeconds(30);
  private static final IdempotentOperation.Spec GENERATE_SPEC =
      new IdempotentOperation.Spec(GENERATE, "payroll_input", "generate", "generated", 200);
  private static final Pattern LIMIT = Pattern.compile("^[0-9]{1,3}$");
  private static final Pattern UUID_SHAPE =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  private static final int DEFAULT_LIMIT = 20;
  private static final int MAX_LIMIT = 100;

  private final IdempotentOperation operations;
  private final LockedApprovedTime approvedTime;
  private final JdbcPayrollInputRepository runs;
  private final OrganizationPlacementDirectory units;
  private final OrganizationDirectory organizations;
  private final CursorCodec cursors;
  private final Clock clock;
  private final AuditRecorder audit;
  private final OutboxWriter outbox;
  private final OperationMetrics metrics;
  private final PlatformTransactionManager transactionManager;
  private final JsonMapper json;

  /**
   * Creates the service.
   *
   * @param operations shared idempotent command flow
   * @param approvedTime the people module's locked-approved-time port
   * @param runs run storage
   * @param units the tenant module's unit port (legal entity, sites)
   * @param organizations the tenant module's organization port (business time zone)
   * @param cursors signed cursors
   * @param clock the platform clock
   * @param audit audit recorder
   * @param outbox outbox writer
   * @param metrics operation metrics
   * @param transactionManager transaction manager
   * @param json JSON mapper
   */
  public PayrollInputService(
      IdempotentOperation operations,
      LockedApprovedTime approvedTime,
      JdbcPayrollInputRepository runs,
      OrganizationPlacementDirectory units,
      OrganizationDirectory organizations,
      CursorCodec cursors,
      Clock clock,
      AuditRecorder audit,
      OutboxWriter outbox,
      OperationMetrics metrics,
      PlatformTransactionManager transactionManager,
      JsonMapper json) {
    this.operations = operations;
    this.approvedTime = approvedTime;
    this.runs = runs;
    this.units = units;
    this.organizations = organizations;
    this.cursors = cursors;
    this.clock = clock;
    this.audit = audit;
    this.outbox = outbox;
    this.metrics = metrics;
    this.transactionManager = transactionManager;
    this.json = json;
  }

  // ------------------------------------------------------------------------------------------
  // Preview and generation
  // ------------------------------------------------------------------------------------------

  /**
   * What a run of the scope would hold now.
   *
   * @param caller verified tenant administrator
   * @param body legal entity and period
   * @return the preview, after its disclosure audit committed
   */
  public Preview preview(PayrollCaller caller, PayrollInputPreviewRequest body) {
    return observed(
        PREVIEW,
        () -> {
          Target target = PayrollInputCommand.target(body);
          return read(
              () -> {
                TenantId tenant = caller.tenant();
                Resolved resolved = resolve(tenant, target);
                // R142-1: the tenant's scheduling lock, as the generation takes it, so the weeks,
                // lock states, approvals and existing run are read from one committed state: no
                // lock, unlock, correction, refresh or approval interleaves with the evaluation.
                approvedTime.lockForPayrollInputs(tenant);
                Evaluation evaluation = evaluate(tenant, resolved);
                Instant expiresAt = expiry(now());
                String versions = versionsDigest(evaluation);
                String digest = previewDigest(tenant, evaluation, versions, expiresAt);
                Run existing =
                    runs.byPeriod(
                            tenant,
                            target.legalEntityId(),
                            target.periodStart(),
                            target.periodEnd())
                        .orElse(null);
                disclosed(
                    caller,
                    PREVIEW,
                    "payroll-input-preview",
                    evaluation.totals().employees(),
                    "DIVALHR-PAYROLL-INPUT-PREVIEW\nversion=1\nevidence="
                        + evaluation.evidenceDigest()
                        + "\n");
                Map<UUID, SourceWeek> sources = new HashMap<>();
                evaluation.sourceWeeks().forEach(w -> sources.put(w.generationId(), w));
                List<WeekView> weeks = new ArrayList<>();
                for (Week week : evaluation.weeks()) {
                  SourceWeek source =
                      week.generation() == null ? null : sources.get(week.generation().id());
                  weeks.add(WeekView.of(week, source, resolved.sites().get(week.siteId())));
                }
                return new Preview(
                    ScopeView.of(evaluation.scope(), resolved.legalEntity()),
                    weeks,
                    Counts.of(evaluation.totals()),
                    PayrollInputResponses.exclusions(evaluation.exclusions()),
                    PayrollInputResponses.blockers(evaluation.blockers()),
                    evaluation.ready(),
                    versions,
                    digest,
                    expiresAt,
                    existing == null ? null : existing.id(),
                    existing == null
                        ? null
                        : existing.evidenceDigest().equals(evaluation.evidenceDigest()));
              });
        });
  }

  /**
   * Generates the run of a reviewed preview, returns the existing run of identical evidence, or
   * replays an identical command.
   *
   * @param caller verified tenant administrator
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body scope, digests and confirmation
   * @return the result
   */
  public IdempotentOperation.Result<Result> generate(
      PayrollCaller caller, String idempotencyKey, GeneratePayrollInputsRequest body) {
    return command(
        GENERATE,
        () -> {
          Generate generate =
              operations.validated(
                  GENERATE_SPEC, () -> PayrollInputCommand.generate(idempotencyKey, body));
          return operations.execute(
              GENERATE_SPEC,
              caller.subject(),
              idempotencyKey,
              generate.canonical(caller.tenant()),
              Result.class,
              TRANSACTION_TIMEOUT,
              () -> generated(caller, generate));
        });
  }

  private IdempotentOperation.Completed<Result> generated(PayrollCaller caller, Generate generate) {
    TenantId tenant = caller.tenant();
    Resolved resolved = resolve(tenant, generate.target());
    // Lock order step 1: the tenant's scheduling lock, through the port, before any evidence is
    // read, so that the evaluation is the one that is written.
    approvedTime.lockForPayrollInputs(tenant);
    Evaluation evaluation = evaluate(tenant, resolved);
    Instant now = now();
    String versions = versionsDigest(evaluation);
    if (!versions.equals(generate.sourceVersionsDigest())
        || !fresh(tenant, evaluation, versions, generate.previewDigest(), now)) {
      throw stale();
    }
    if (!evaluation.ready()) {
      throw new ApiException(ErrorCode.PAYROLL_INPUT_NOT_READY, Map.of());
    }
    var existing =
        runs.byPeriod(
            tenant,
            evaluation.scope().legalEntityId(),
            evaluation.scope().periodStart(),
            evaluation.scope().periodEnd());
    if (existing.isPresent()) {
      Run run = existing.get();
      if (!run.evidenceDigest().equals(evaluation.evidenceDigest())) {
        throw new ApiException(ErrorCode.PAYROLL_INPUT_RUN_EXISTS, Map.of());
      }
      return new IdempotentOperation.Completed<>(
          new Result("EXISTING", Summary.of(run, resolved.legalEntity())),
          run.id(),
          Outcome.UNCHANGED);
    }
    Run run =
        new Run(
            UUID.randomUUID(),
            evaluation.scope(),
            1,
            evaluation.sourceWeeks().size(),
            evaluation.totals(),
            evaluation.exclusions(),
            evaluation.evidenceDigest(),
            now);
    try {
      runs.insert(tenant, run, evaluation, caller.subject());
      runs.checkConsistent();
    } catch (DataIntegrityViolationException violated) {
      throw mapped(violated);
    }
    recorded(caller, run, now);
    event(caller, run, evaluation, now);
    LOG.atInfo()
        .addKeyValue("operation", GENERATE)
        .addKeyValue("outcome", "generated")
        .addKeyValue("employees", PayrollInputs.countBand(run.totals().employees()))
        .addKeyValue("weeks", PayrollInputs.countBand(run.weekCount()))
        .log("payroll_input_generated");
    return new IdempotentOperation.Completed<>(
        new Result("GENERATED", Summary.of(run, resolved.legalEntity())),
        run.id(),
        Outcome.CREATED);
  }

  // ------------------------------------------------------------------------------------------
  // History and detail
  // ------------------------------------------------------------------------------------------

  /**
   * One page of the tenant's runs, newest first, optionally of one legal entity.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw filter, or {@code null}
   * @param cursor opaque cursor, or {@code null}
   * @param limit page size (1 to 100, default 20)
   * @return the page, after its disclosure audit committed
   */
  public Page list(PayrollCaller caller, String legalEntityId, String cursor, String limit) {
    return observed(
        LIST,
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
          return read(
              () -> {
                TenantId tenant = caller.tenant();
                Map<String, String> filters = new TreeMap<>();
                filters.put("legalEntity", legalEntity == null ? "-" : legalEntity.toString());
                filters.put("limit", Integer.toString(size));
                CursorScope scope = new CursorScope(LIST, tenant, filters);
                Instant afterAt = null;
                UUID afterId = null;
                if (cursor != null) {
                  KeysetPosition position = cursors.decode(cursor, scope);
                  afterAt = generatedAt(position.code());
                  afterId = position.id();
                }
                List<Run> rows = runs.page(tenant, legalEntity, afterAt, afterId, size + 1);
                List<Run> shown = rows.subList(0, Math.min(rows.size(), size));
                String next =
                    rows.size() > size
                        ? cursors.encode(
                            scope,
                            new KeysetPosition(
                                "G" + micros(shown.get(shown.size() - 1).generatedAt()),
                                shown.get(shown.size() - 1).id()))
                        : null;
                Set<UUID> ids = new HashSet<>();
                shown.forEach(r -> ids.add(r.scope().legalEntityId()));
                Map<UUID, UnitView> resolved =
                    ids.isEmpty() ? Map.of() : units.resolveIds(tenant, ids);
                StringBuilder text = new StringBuilder("DIVALHR-PAYROLL-INPUT-LIST\nversion=1\n");
                List<Summary> items =
                    shown.stream()
                        .map(
                            r -> {
                              text.append("run=").append(r.id()).append('\n');
                              return Summary.of(
                                  r,
                                  kind(
                                      resolved.get(r.scope().legalEntityId()),
                                      UnitKind.LEGAL_ENTITY));
                            })
                        .toList();
                disclosed(caller, LIST, "payroll-input-runs", items.size(), text.toString());
                return new Page(items, next);
              });
        });
  }

  /**
   * One run with its source weeks and employee inputs.
   *
   * @param caller verified tenant administrator
   * @param runId path value
   * @return the run, after its disclosure audit committed
   */
  public Detail run(PayrollCaller caller, String runId) {
    return observed(
        READ,
        () -> {
          UUID id = PayrollInputCommand.id(runId);
          return read(
              () -> {
                TenantId tenant = caller.tenant();
                Run run = runs.run(tenant, id).orElseThrow(PayrollInputCommand::notFound);
                List<SourceWeek> weeks = runs.weeks(tenant, run.id());
                List<PayrollInputs.EmployeeInput> employees = runs.employees(tenant, run.id());
                disclosed(
                    caller,
                    READ,
                    "payroll-input-run",
                    employees.size(),
                    "DIVALHR-PAYROLL-INPUT-READ\nversion=1\nrun=" + id + "\n");
                Set<UUID> ids = new HashSet<>();
                ids.add(run.scope().legalEntityId());
                weeks.forEach(w -> ids.add(w.siteId()));
                Map<UUID, UnitView> resolved = units.resolveIds(tenant, ids);
                return new Detail(
                    Summary.of(
                        run,
                        kind(resolved.get(run.scope().legalEntityId()), UnitKind.LEGAL_ENTITY)),
                    weeks.stream()
                        .map(
                            w ->
                                SourceWeekView.of(w, kind(resolved.get(w.siteId()), UnitKind.SITE)))
                        .toList(),
                    employees.stream().map(EmployeeView::of).toList());
              });
        });
  }

  // ------------------------------------------------------------------------------------------
  // Evaluation
  // ------------------------------------------------------------------------------------------

  /** The verified legal entity, its sites and the business time zone. */
  private record Resolved(
      Target target, UnitView legalEntity, Map<UUID, UnitView> sites, String timezone) {}

  /** The legal entity and its sites, or the one safe 404. */
  private Resolved resolve(TenantId tenant, Target target) {
    UnitView legalEntity =
        kind(
            units.resolveIds(tenant, Set.of(target.legalEntityId())).get(target.legalEntityId()),
            UnitKind.LEGAL_ENTITY);
    if (legalEntity == null) {
      throw PayrollInputCommand.notFound();
    }
    String timezone =
        organizations
            .find(tenant)
            .map(OrganizationDirectory.OrganizationSummary::timezone)
            .orElseThrow(PayrollInputCommand::notFound);
    // The supported pilot boundary: a period ends no later than today in the business zone (no
    // payroll inputs are prepared for days that have not happened).
    if (target.periodEnd().isAfter(today(timezone))) {
      FieldErrors errors = new FieldErrors();
      errors.add("periodEnd", Constraint.RANGE);
      errors.throwIfAny();
    }
    Map<UUID, UnitView> sites = new LinkedHashMap<>();
    for (UnitView site : units.sitesOf(tenant, target.legalEntityId())) {
      // A site that is not effective on any day of the period holds nothing of it.
      if (site.effectiveOn(target.periodEnd())
          || site.effectiveOn(target.periodStart())
          || (site.effectiveFrom().isAfter(target.periodStart())
              && !site.effectiveFrom().isAfter(target.periodEnd()))) {
        sites.put(site.id(), site);
      }
    }
    return new Resolved(target, legalEntity, sites, timezone);
  }

  /** The evaluation of the scope now. */
  private Evaluation evaluate(TenantId tenant, Resolved resolved) {
    Target target = resolved.target();
    Scope scope =
        new Scope(
            target.legalEntityId(), target.periodStart(), target.periodEnd(), resolved.timezone());
    List<Week> weeks =
        approvedTime.weeks(
            tenant, resolved.sites().keySet(), target.periodStart(), target.periodEnd());
    Map<UUID, List<ApprovedRow>> approved = new HashMap<>();
    for (Week week : weeks) {
      if (week.generation() != null && week.generation().locked()) {
        approved.put(
            week.generation().id(), approvedTime.approvedRows(tenant, week.generation().id()));
      }
    }
    return PayrollInputs.evaluate(scope, weeks, approved);
  }

  /** The end of the window after the current one (15 to 30 minutes from {@code at}). */
  static Instant expiry(Instant at) {
    long window = WINDOW.toMillis();
    long start = Math.floorDiv(at.toEpochMilli(), window) * window;
    return Instant.ofEpochMilli(start + 2 * window);
  }

  /** The digest of the source versions the preview showed. */
  private static String versionsDigest(Evaluation evaluation) {
    return Fingerprints.sha256(
        "DIVALHR-PAYROLL-INPUT-SOURCES\nversion=1\n" + evaluation.sourceVersions());
  }

  /** The preview digest: the evidence, the source versions, the readiness and the expiry. */
  private static String previewDigest(
      TenantId tenant, Evaluation evaluation, String versions, Instant expiresAt) {
    return Fingerprints.sha256(
        "DIVALHR-PAYROLL-INPUT-PREVIEW\nversion=1\ntenant="
            + tenant
            + "\nevidence="
            + evaluation.evidenceDigest()
            + "\nsources="
            + versions
            + "\nready="
            + evaluation.ready()
            + "\nexpiresAt="
            + expiresAt
            + "\n");
  }

  /** Whether a preview digest matches the evidence now and has not expired. */
  private static boolean fresh(
      TenantId tenant, Evaluation evaluation, String versions, String digest, Instant now) {
    Instant next = expiry(now).minus(WINDOW);
    for (Instant expiresAt : List.of(next, next.plus(WINDOW))) {
      if (expiresAt.isAfter(now)
          && previewDigest(tenant, evaluation, versions, expiresAt).equals(digest)) {
        return true;
      }
    }
    return false;
  }

  // ------------------------------------------------------------------------------------------
  // Audit and outbox
  // ------------------------------------------------------------------------------------------

  /** The generation audit: bounded metadata; identifiers, digests and the actor in the digest. */
  private void recorded(PayrollCaller caller, Run run, Instant now) {
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
    Map<String, Object> state = new TreeMap<>();
    state.put("tenantId", caller.tenant().toString());
    state.put("runId", run.id().toString());
    state.put("legalEntityId", run.scope().legalEntityId().toString());
    state.put("periodStart", run.scope().periodStart().toString());
    state.put("periodEnd", run.scope().periodEnd().toString());
    state.put("businessTimezone", run.scope().businessTimezone());
    state.put("evidenceDigest", run.evidenceDigest());
    state.put("workedMinutes", run.totals().workedMinutes());
    state.put("generatedAt", now.toString());
    state.put("by", caller.subject());
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            now,
            caller.subject(),
            GENERATE,
            RESOURCE,
            run.id(),
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(json.writeValueAsString(state))));
  }

  /** Stable IDs, dates, versions, counts and the minute band; the source generations and locks. */
  private void event(PayrollCaller caller, Run run, Evaluation evaluation, Instant now) {
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
    List<Map<String, Object>> sources = new ArrayList<>();
    for (SourceWeek week : evaluation.sourceWeeks()) {
      Map<String, Object> source = new LinkedHashMap<>();
      source.put("siteId", week.siteId().toString());
      source.put("weekStart", week.weekStart().toString());
      source.put("generationId", week.generationId().toString());
      source.put("generationVersion", week.generationVersion());
      source.put("lockActionId", week.lockActionId().toString());
      source.put("lockVersion", week.lockVersion());
      sources.add(source);
    }
    data.put("sources", sources);
    data.put("status", "GENERATED");
    data.put("occurredAt", now.toString());
    outbox.append(
        new EventEnvelope(
            UUID.randomUUID().toString(),
            GENERATED_EVENT,
            PayrollInputs.SCHEMA_VERSION,
            caller.tenant().toString(),
            SOURCE,
            run.id().toString(),
            now.toString(),
            caller.correlationId(),
            null,
            data));
  }

  /** The fail-closed disclosure audit: the view and count; identifiers only in the digest. */
  private void disclosed(PayrollCaller caller, String action, String view, int count, String text) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", PayrollInputs.SCHEMA_VERSION);
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

  /** The generation instant as epoch microseconds (a cursor code is {@code [A-Z0-9_-]}). */
  private static long micros(Instant instant) {
    return ChronoUnit.MICROS.between(Instant.EPOCH, instant);
  }

  private static Instant generatedAt(String code) {
    if (code == null || !code.matches("^G[0-9]{1,18}$")) {
      throw new ApiException(ErrorCode.CURSOR_INVALID, Map.of());
    }
    return Instant.EPOCH.plus(Long.parseLong(code.substring(1)), ChronoUnit.MICROS);
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
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  /**
   * Today in a zone (the last supported period end).
   *
   * @param zone zone
   * @return today
   */
  LocalDate today(String zone) {
    return LocalDate.ofInstant(clock.instant(), ZoneId.of(zone));
  }

  private static ApiException stale() {
    return new ApiException(ErrorCode.PAYROLL_INPUT_PREVIEW_STALE, Map.of());
  }

  /** The stable conflict of a constraint the locks should have made unreachable; no DB text. */
  private static ApiException mapped(DataIntegrityViolationException violated) {
    String constraint = PayrollConstraintViolations.constraint(violated).orElse("");
    return switch (constraint) {
      case PayrollConstraintViolations.RUN_VERSION ->
          new ApiException(ErrorCode.PAYROLL_INPUT_RUN_EXISTS, Map.of());
      case PayrollConstraintViolations.RUN_EXACT -> stale();
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
          .log("payroll_input_command_rejected");
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
          .log("payroll_input_read");
      return result;
    } catch (ApiException rejected) {
      Outcome outcome =
          switch (rejected.code()) {
            case PAYROLL_INPUT_TARGET_NOT_FOUND -> Outcome.NOT_FOUND;
            case VALIDATION_FAILED, CURSOR_INVALID -> Outcome.VALIDATION_FAILED;
            default -> Outcome.STATE_CONFLICT;
          };
      metrics.record(operation, outcome);
      LOG.atInfo()
          .addKeyValue("operation", operation)
          .addKeyValue("outcome", outcome.name().toLowerCase(Locale.ROOT))
          .addKeyValue("code", rejected.code().name())
          .log("payroll_input_read_rejected");
      throw rejected;
    } catch (RuntimeException failure) {
      metrics.record(operation, Outcome.FAILURE);
      LOG.atWarn()
          .addKeyValue("operation", operation)
          .addKeyValue("outcome", "failure")
          .log("payroll_input_read_failed");
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
