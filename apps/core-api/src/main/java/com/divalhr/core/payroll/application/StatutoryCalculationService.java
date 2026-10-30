package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.CommitStatutoryCalculationRequest;
import com.divalhr.core.payroll.api.StatutoryCalculationCommandRequest;
import com.divalhr.core.payroll.api.StatutoryResponses.CalculationDetail;
import com.divalhr.core.payroll.api.StatutoryResponses.CalculationPage;
import com.divalhr.core.payroll.api.StatutoryResponses.CalculationPreview;
import com.divalhr.core.payroll.api.StatutoryResponses.CalculationReceipt;
import com.divalhr.core.payroll.api.StatutoryResponses.Component;
import com.divalhr.core.payroll.api.StatutoryResponses.PackSummary;
import com.divalhr.core.payroll.api.StatutoryResponses.PreviewLine;
import com.divalhr.core.payroll.api.StatutoryResponses.RunLine;
import com.divalhr.core.payroll.api.StatutoryResponses.SourceView;
import com.divalhr.core.payroll.application.StatutoryCommands.CalculationCommit;
import com.divalhr.core.payroll.application.StatutoryEvaluator.Evaluation;
import com.divalhr.core.payroll.domain.StatutoryCalculator.LineResult;
import com.divalhr.core.payroll.domain.StatutoryCalculator.Result;
import com.divalhr.core.payroll.domain.StatutoryExplanation;
import com.divalhr.core.payroll.domain.StatutoryLine;
import com.divalhr.core.payroll.domain.StatutoryLineBlocker;
import com.divalhr.core.payroll.domain.StatutoryRules;
import com.divalhr.core.payroll.domain.StatutoryRunBlocker;
import com.divalhr.core.payroll.internal.JdbcPayrollRunRepository;
import com.divalhr.core.payroll.internal.JdbcPayrollRunRepository.RunRow;
import com.divalhr.core.payroll.internal.JdbcStatutoryRepository;
import com.divalhr.core.payroll.internal.JdbcStatutoryRepository.CalculationPosition;
import com.divalhr.core.payroll.internal.JdbcStatutoryRepository.CalculationRow;
import com.divalhr.core.payroll.internal.JdbcStatutoryRepository.ComponentRow;
import com.divalhr.core.payroll.internal.JdbcStatutoryRepository.LineRow;
import com.divalhr.core.payroll.internal.JdbcStatutoryRepository.NewCalculation;
import com.divalhr.core.payroll.internal.JdbcStatutoryRepository.NewLine;
import com.divalhr.core.payroll.internal.JdbcStatutoryRulePackRepository;
import com.divalhr.core.payroll.internal.JdbcStatutoryRulePackRepository.PackRow;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Statutory payroll calculations of a frozen payroll run for tenant administrators with privileged
 * MFA (MVP-094B, Issue #179, ADR 0034): the preview, the creation and the reads. Authorization has
 * run before this; tenant, actor and scope never come from the request.
 *
 * <p>Boundary: a statutory calculation is a preview of employee deductions, employer contributions
 * and a preview net on top of the frozen contractual gross of one run. It is not a payslip, a
 * payment, an approval or a filing, and it makes no legal-compliance claim. Only a VERIFIED rule
 * pack, activated by the employer, produces amounts; the engine applies structured rules and never
 * evaluates a script.
 *
 * <p>A creation runs in one idempotent transaction in the lock order of ADR 0034 section 7: the
 * idempotency reservation, the tenant statutory lock exclusive, the legal entity's calculation
 * lock, the source run row {@code FOR SHARE}, and only then the run, the activation, the pack and
 * the profiles are re-read and recalculated. The checks run in the order the contract fixes; no
 * digest is compared before the state checks. Restricted financial and HR data: amounts, employee
 * numbers, flags, digests and traces never enter logs, metrics, audit metadata, outbox events,
 * idempotency receipts, cursors or error bodies.
 */
@Service
public class StatutoryCalculationService {

  /** Previews (interceptor operation; disclosed as a calculation read). */
  public static final String PREVIEW = "statutory-calculation.preview";

  /** List and detail reads (interceptor operation and disclosure audit action). */
  public static final String READ = StatutoryRecorder.CALCULATION_READ;

  /** Creation (idempotency operation, interceptor operation and audit action). */
  public static final String CREATE = StatutoryRecorder.CALCULATION_CREATE;

  /** Per-subject previews. */
  public static final String SUBJECT_PREVIEW_BUCKET = "statutory-calculation-preview";

  /** Per-subject creations. */
  public static final String SUBJECT_CREATE_BUCKET = "statutory-calculation-create";

  /** Per-tenant previews. */
  public static final String TENANT_PREVIEW_BUCKET = "statutory-calculation-preview";

  /** Per-tenant creations. */
  public static final String TENANT_CREATE_BUCKET = "statutory-calculation-create";

  /** The most versions of one chain. */
  public static final int MAX_VERSIONS = 25;

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.statutory-calculation");
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(60);
  private static final String ROLE = "tenant-admin";
  private static final String CALCULATION_CODE = "CALC";
  private static final String LINE_CODE = "L1";
  private static final IdempotentOperation.Spec CREATE_SPEC =
      new IdempotentOperation.Spec(CREATE, "statutory_calculation", "create", "created", 201);

  private final IdempotentOperation operations;
  private final JdbcStatutoryRepository statutory;
  private final JdbcStatutoryRulePackRepository packs;
  private final JdbcPayrollRunRepository runs;
  private final PayrollEntities entities;
  private final StatutoryEvaluator evaluator;
  private final CompensationCalendar calendar;
  private final StatutoryRecorder recorder;
  private final PayrollOperations plumbing;
  private final CursorCodec cursors;

  /**
   * Creates the service.
   *
   * @param operations shared idempotent command flow
   * @param statutory tenant-owned statutory storage
   * @param packs platform-owned rule packs
   * @param runs payroll storage (the frozen source run)
   * @param entities legal entity resolver
   * @param evaluator shared read-and-calculate component
   * @param calendar business date and preview window
   * @param recorder audit and outbox
   * @param plumbing transactions, metrics and logs
   * @param cursors signed cursors
   */
  public StatutoryCalculationService(
      IdempotentOperation operations,
      JdbcStatutoryRepository statutory,
      JdbcStatutoryRulePackRepository packs,
      JdbcPayrollRunRepository runs,
      PayrollEntities entities,
      StatutoryEvaluator evaluator,
      CompensationCalendar calendar,
      StatutoryRecorder recorder,
      PayrollOperations plumbing,
      CursorCodec cursors) {
    this.operations = operations;
    this.statutory = statutory;
    this.packs = packs;
    this.runs = runs;
    this.entities = entities;
    this.evaluator = evaluator;
    this.calendar = calendar;
    this.recorder = recorder;
    this.plumbing = plumbing;
    this.cursors = cursors;
  }

  // ------------------------------------------------------------------------------------------
  // Preview
  // ------------------------------------------------------------------------------------------

  /**
   * Calculates a frozen run without writing anything. A preview never fails for a blocker: its
   * status is {@code READY}, {@code BLOCKED} or {@code NO_CHANGE}.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param runId raw path value
   * @param body the (empty) command
   * @return the preview, after its disclosure audit committed
   */
  public CalculationPreview preview(
      PayrollCaller caller,
      String legalEntityId,
      String runId,
      StatutoryCalculationCommandRequest body) {
    return plumbing.observed(
        LOG,
        "statutory_calculation_preview",
        PREVIEW,
        () -> {
          PayrollCommands.id(legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
          UUID runUuid = PayrollCommands.id(runId, ErrorCode.PAYROLL_RUN_NOT_FOUND);
          StatutoryCommands.calculationCommand(body);
          return plumbing.inReadTransaction(
              caller.tenant(),
              READ_TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                UUID entity =
                    entities.require(tenant, legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
                RunRow run =
                    runs.run(tenant, entity, runUuid)
                        .orElseThrow(
                            () -> new ApiException(ErrorCode.PAYROLL_RUN_NOT_FOUND, Map.of()));
                Instant now = calendar.now();
                Evaluation evaluation = evaluator.evaluate(tenant, entity, run);
                CalculationPreview preview = previewOf(tenant, entity, evaluation, now);
                recorder.disclosed(
                    caller,
                    READ,
                    "statutory-calculation",
                    run.id(),
                    Map.of("legalEntityId", entity.toString(), "runId", run.id().toString()),
                    "preview",
                    true,
                    preview.lines().size(),
                    now);
                return preview;
              });
        },
        StatutoryCalculationService::previewOutcome);
  }

  /** A blocked preview is a state conflict and an unchanged one is unchanged, never a success. */
  private static Outcome previewOutcome(CalculationPreview preview) {
    return switch (preview.status()) {
      case "BLOCKED" -> Outcome.STATE_CONFLICT;
      case "NO_CHANGE" -> Outcome.UNCHANGED;
      default -> Outcome.LISTED;
    };
  }

  private static CalculationPreview previewOf(
      TenantId tenant, UUID entity, Evaluation evaluation, Instant now) {
    RunRow run = evaluation.run();
    Result result = evaluation.result();
    String status =
        !result.committable() ? "BLOCKED" : evaluation.unchanged() ? "NO_CHANGE" : "READY";
    boolean amounts = result.runBlockers().isEmpty();
    PackSummary pack =
        evaluation.resolved() == null ? null : StatutoryViews.summary(evaluation.resolved().pack());
    Instant expiresAt = "READY".equals(status) ? CompensationCalendar.expiry(now) : null;
    String calculationDigest =
        expiresAt == null
            ? null
            : StatutoryDigests.calculation(
                tenant,
                entity,
                run.id(),
                evaluation.resolved().pack().id(),
                evaluation.sourceDigest(),
                evaluation.profileDigest(),
                evaluation.packDigest(),
                evaluation.chainVersion(),
                expiresAt);
    List<PreviewLine> lines = new ArrayList<>();
    for (LineResult line : result.lines()) {
      lines.add(previewLine(line));
    }
    return new CalculationPreview(
        entity,
        run.id(),
        run.version(),
        StatutoryViews.month(run.periodStart()),
        run.currency().name(),
        status,
        pack,
        result.runBlockers().stream().map(StatutoryRunBlocker::name).toList(),
        evaluation.chainVersion(),
        evaluation.draft() == null ? null : StatutoryViews.summary(evaluation.draft()),
        result.supportedCount(),
        result.excludedCount(),
        result.blockedCount(),
        amounts
            ? StatutoryViews.totals(
                result.grossTotal(),
                result.employeeDeductionsTotal(),
                result.employerContributionsTotal(),
                result.previewNetTotal(),
                result.employerCostTotal())
            : null,
        lines,
        evaluation.sourceDigest(),
        evaluation.profileDigest(),
        evaluation.packDigest(),
        calculationDigest,
        expiresAt);
  }

  private static PreviewLine previewLine(LineResult result) {
    StatutoryLine line = result.line();
    List<String> blockers = result.blockers().stream().map(StatutoryLineBlocker::name).toList();
    if (line == null) {
      return new PreviewLine(
          result.employeeId(),
          result.employeeNumber(),
          blockers,
          false,
          StatutoryViews.cdf(result.grossMinor()),
          null,
          null,
          null,
          null,
          List.of());
    }
    List<Component> components = new ArrayList<>();
    for (StatutoryLine.Component component : line.components()) {
      components.add(
          new Component(
              component.componentId(),
              component.party().name(),
              component.category().name(),
              StatutoryViews.cdf(component.baseMinor()),
              StatutoryViews.cdf(component.amountMinor()),
              component.explanations().stream().map(StatutoryExplanation::name).toList(),
              StatutoryViews.trace(component.trace())));
    }
    return new PreviewLine(
        result.employeeId(),
        result.employeeNumber(),
        blockers,
        line.excluded(),
        StatutoryViews.cdf(line.grossMinor()),
        StatutoryViews.cdf(line.employeeDeductionsMinor()),
        StatutoryViews.cdf(line.employerContributionsMinor()),
        StatutoryViews.cdf(line.previewNetMinor()),
        StatutoryViews.cdf(line.employerCostMinor()),
        components);
  }

  // ------------------------------------------------------------------------------------------
  // Create
  // ------------------------------------------------------------------------------------------

  /**
   * Creates a previewed calculation, or replays an identical creation.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param runId raw path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the evidence the preview returned
   * @return the amount-free receipt
   */
  public IdempotentOperation.Result<CalculationReceipt> create(
      PayrollCaller caller,
      String legalEntityId,
      String runId,
      String idempotencyKey,
      CommitStatutoryCalculationRequest body) {
    return plumbing.command(
        CREATE,
        () -> {
          record Parsed(UUID entity, UUID run, CalculationCommit command) {}
          Parsed parsed =
              operations.validated(
                  CREATE_SPEC,
                  () ->
                      new Parsed(
                          PayrollCommands.id(legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND),
                          PayrollCommands.id(runId, ErrorCode.PAYROLL_RUN_NOT_FOUND),
                          StatutoryCommands.calculationCommit(idempotencyKey, body)));
          return operations.execute(
              CREATE_SPEC,
              caller.subject(),
              idempotencyKey,
              parsed.command().canonical(caller.tenant(), parsed.entity(), parsed.run()),
              CalculationReceipt.class,
              WRITE_TIMEOUT,
              () -> created(caller, parsed.entity(), parsed.run(), parsed.command()));
        });
  }

  private IdempotentOperation.Completed<CalculationReceipt> created(
      PayrollCaller caller, UUID entity, UUID runId, CalculationCommit command) {
    TenantId tenant = caller.tenant();
    runs.limits(tenant, WRITE_TIMEOUT);
    entities.require(tenant, entity, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
    Instant now = calendar.now();
    // Lock order of ADR 0034 section 7: the tenant lock exclusive, the entity's calculation lock,
    // then the source run row FOR SHARE. Everything is re-read only after the locks are held.
    statutory.lockStatutory(tenant, false);
    statutory.lockCalculation(tenant, entity);
    if (!statutory.lockSourceRun(tenant, entity, runId)) {
      throw new ApiException(ErrorCode.PAYROLL_RUN_NOT_FOUND, Map.of());
    }
    RunRow run =
        runs.run(tenant, entity, runId)
            .orElseThrow(() -> new ApiException(ErrorCode.PAYROLL_RUN_NOT_FOUND, Map.of()));
    Evaluation evaluation = evaluator.evaluate(tenant, entity, run);
    requireCommittable(evaluation, command);
    return write(caller, entity, evaluation, command, now);
  }

  /** The contract's check order; nothing is written before every check passed. */
  private static void requireCommittable(Evaluation evaluation, CalculationCommit command) {
    Result result = evaluation.result();
    var resolved = evaluation.resolved();
    // 1. A different chain version.
    if (evaluation.chainVersion() != command.expectedVersion()) {
      throw new ApiException(ErrorCode.STATUTORY_VERSION_CONFLICT, Map.of());
    }
    // 2. A source run that is no longer the current version of its month is a stale preview.
    if (!evaluation.current()) {
      throw new ApiException(ErrorCode.STATUTORY_PREVIEW_STALE, Map.of());
    }
    // 3. No activation in effect, or a pack that is not VERIFIED (DRAFT and RETIRED never count).
    if (resolved == null || !"VERIFIED".equals(resolved.pack().status())) {
      throw new ApiException(ErrorCode.STATUTORY_RULE_PACK_NOT_VERIFIED, Map.of());
    }
    // 4. A pack that does not cover the month or the currency, or that does not parse.
    if (result.runBlockers().contains(StatutoryRunBlocker.CURRENCY_NOT_SUPPORTED)
        || !StatutoryEvaluator.usable(resolved, evaluation.run().periodStart())) {
      throw new ApiException(ErrorCode.STATUTORY_RULE_PACK_NOT_APPLICABLE, Map.of());
    }
    // 5. Employee blockers: unsupported first, then missing, then any other.
    boolean unsupported = false;
    boolean missing = false;
    for (LineResult line : result.lines()) {
      unsupported |= line.blockers().contains(StatutoryLineBlocker.PROFILE_UNSUPPORTED);
      missing |= line.blockers().contains(StatutoryLineBlocker.PROFILE_MISSING);
    }
    if (unsupported) {
      throw new ApiException(ErrorCode.STATUTORY_PROFILE_UNSUPPORTED, Map.of());
    }
    if (missing) {
      throw new ApiException(ErrorCode.STATUTORY_PROFILE_MISSING, Map.of());
    }
    if (!result.committable()) {
      throw new ApiException(ErrorCode.STATUTORY_CALCULATION_BLOCKED, Map.of());
    }
    // 6. Identical to the current draft: nothing is recorded and no version is created.
    if (evaluation.unchanged()) {
      throw new ApiException(ErrorCode.STATUTORY_CALCULATION_NO_CHANGE, Map.of());
    }
  }

  private IdempotentOperation.Completed<CalculationReceipt> write(
      PayrollCaller caller,
      UUID entity,
      Evaluation evaluation,
      CalculationCommit command,
      Instant now) {
    TenantId tenant = caller.tenant();
    RunRow run = evaluation.run();
    var resolved = evaluation.resolved();
    PackRow pack = resolved.pack();
    int version = evaluation.chainVersion();
    // 7. Expiry and digests: the commit recomputes all of them from the locked state.
    Instant expiresAt = command.expiresAt();
    String calculationDigest =
        StatutoryDigests.calculation(
            tenant,
            entity,
            run.id(),
            pack.id(),
            evaluation.sourceDigest(),
            evaluation.profileDigest(),
            evaluation.packDigest(),
            version,
            expiresAt);
    if (!expiresAt.isAfter(now)
        || expiresAt.isAfter(CompensationCalendar.expiry(now))
        || !CompensationCalendar.onGrid(expiresAt)
        || !evaluation.sourceDigest().equals(command.sourceRunDigest())
        || !evaluation.profileDigest().equals(command.profileDigest())
        || !evaluation.packDigest().equals(command.rulePackDigest())
        || !calculationDigest.equals(command.calculationDigest())) {
      throw new ApiException(ErrorCode.STATUTORY_PREVIEW_STALE, Map.of());
    }
    // 8. Twenty-five versions of a chain.
    if (version >= MAX_VERSIONS) {
      throw new ApiException(ErrorCode.STATUTORY_LIMIT_REACHED, Map.of());
    }

    Result result = evaluation.result();
    UUID calculationId = UUID.randomUUID();
    CalculationRow current = evaluation.draft();
    Map<String, Integer> positions = positions(resolved.rules());
    List<NewLine> lines = new ArrayList<>();
    for (LineResult line : result.lines()) {
      lines.add(newLine(line, positions));
    }
    NewCalculation calculation =
        new NewCalculation(
            calculationId,
            entity,
            run.id(),
            run.version(),
            run.periodStart(),
            pack.id(),
            resolved.activation().id(),
            version + 1,
            result.supportedCount(),
            result.excludedCount(),
            result.grossTotal(),
            result.employeeDeductionsTotal(),
            result.employerContributionsTotal(),
            result.previewNetTotal(),
            result.employerCostTotal(),
            evaluation.sourceDigest(),
            evaluation.profileDigest(),
            evaluation.packDigest(),
            calculationDigest,
            now,
            caller.subject());
    try {
      // Supersede the old DRAFT first, naming the successor (its key is checked at commit), then
      // insert the successor, its lines and components.
      if (current != null) {
        int changed = statutory.supersede(tenant, entity, current.id(), calculationId, now);
        if (changed != 1) {
          throw new ApiException(ErrorCode.STATUTORY_VERSION_CONFLICT, Map.of());
        }
      }
      statutory.insertCalculation(tenant, calculation);
      statutory.insertLines(tenant, calculationId, run.id(), lines);
      statutory.checkDeferred(tenant);
    } catch (DataIntegrityViolationException violated) {
      throw StatutoryActivationService.integrity(violated);
    }
    UUID superseded = current == null ? null : current.id();
    String period = StatutoryViews.month(run.periodStart());
    recorder.calculationCreated(
        caller,
        new StatutoryRecorder.CalculationRecorded(
            entity,
            run.id(),
            calculationId,
            pack.id(),
            pack.version(),
            period,
            version + 1,
            result.supportedCount(),
            result.excludedCount(),
            superseded,
            calculationDigest,
            now));
    LOG.atInfo()
        .addKeyValue("operation", CREATE)
        .addKeyValue("outcome", "recorded")
        .addKeyValue("employees", PayrollRunService.employeeBucket(lines.size()))
        .log("statutory_calculation_recorded");
    return new IdempotentOperation.Completed<>(
        new CalculationReceipt(
            calculationId,
            run.id(),
            entity,
            pack.id(),
            pack.version(),
            period,
            "CDF",
            version + 1,
            "DRAFT",
            superseded,
            result.supportedCount(),
            result.excludedCount()),
        calculationId,
        Outcome.CREATED);
  }

  /** The 1-based position of each rule in the pack: the component's {@code seq}. */
  private static Map<String, Integer> positions(StatutoryRules rules) {
    Map<String, Integer> positions = new HashMap<>();
    int seq = 1;
    for (StatutoryRules.Component component : rules.components()) {
      positions.put(component.id(), seq++);
    }
    return positions;
  }

  private static NewLine newLine(LineResult result, Map<String, Integer> positions) {
    StatutoryLine line = result.line();
    UUID lineId = UUID.randomUUID();
    List<ComponentRow> components = new ArrayList<>();
    for (StatutoryLine.Component component : line.components()) {
      components.add(
          new ComponentRow(
              positions.get(component.componentId()),
              component.componentId(),
              component.party().name(),
              component.category().name(),
              component.baseMinor(),
              component.amountMinor(),
              component.explanations().stream().map(StatutoryExplanation::name).toList(),
              StatutoryViews.traceJson(component.trace())));
    }
    LineRow row =
        new LineRow(
            lineId,
            result.employeeId(),
            result.employeeNumber(),
            line.grossMinor(),
            line.employeeDeductionsMinor(),
            line.employerContributionsMinor(),
            line.previewNetMinor(),
            line.employerCostMinor(),
            line.excluded());
    return new NewLine(row, result.sourceLineId(), components);
  }

  // ------------------------------------------------------------------------------------------
  // Reads
  // ------------------------------------------------------------------------------------------

  /**
   * A page of the statutory calculations of a run.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param runId raw path value
   * @param cursor opaque cursor, or {@code null}
   * @param limit raw page size, or {@code null}
   * @return the page, after its disclosure audit committed
   */
  public CalculationPage list(
      PayrollCaller caller, String legalEntityId, String runId, String cursor, String limit) {
    return plumbing.observed(
        LOG,
        "statutory_calculation_read",
        READ,
        () -> {
          PayrollCommands.id(legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
          UUID run = PayrollCommands.id(runId, ErrorCode.PAYROLL_RUN_NOT_FOUND);
          FieldErrors errors = new FieldErrors();
          int size =
              PayrollCommands.limit(
                  limit,
                  StatutoryCommands.DEFAULT_LIST_LIMIT,
                  StatutoryCommands.MAX_LIST_LIMIT,
                  errors);
          errors.throwIfAny();
          return plumbing.inReadTransaction(
              caller.tenant(),
              READ_TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                UUID entity =
                    entities.require(tenant, legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
                runs.run(tenant, entity, run)
                    .orElseThrow(() -> new ApiException(ErrorCode.PAYROLL_RUN_NOT_FOUND, Map.of()));
                CursorScope scope = scope(caller, "list", entity, run, null, size);
                CalculationPosition after = null;
                if (cursor != null) {
                  KeysetPosition position = cursors.decode(cursor, scope);
                  CalculationRow at =
                      statutory
                          .calculation(tenant, entity, run, position.id())
                          .filter(row -> CALCULATION_CODE.equals(position.code()))
                          .orElseThrow(() -> new ApiException(ErrorCode.CURSOR_INVALID, Map.of()));
                  after = new CalculationPosition(at.version(), at.id());
                }
                List<CalculationRow> rows =
                    statutory.calculationPage(tenant, entity, run, after, size + 1);
                List<CalculationRow> shown = rows.subList(0, Math.min(rows.size(), size));
                String next = null;
                if (rows.size() > size) {
                  next =
                      cursors.encode(
                          scope,
                          new KeysetPosition(CALCULATION_CODE, shown.get(shown.size() - 1).id()));
                }
                recorder.disclosed(
                    caller,
                    READ,
                    "statutory-calculation",
                    run,
                    Map.of("legalEntityId", entity.toString(), "runId", run.toString()),
                    "list",
                    cursor == null,
                    shown.size(),
                    calendar.now());
                return new CalculationPage(
                    shown.stream().map(StatutoryViews::summary).toList(), next);
              });
        });
  }

  /**
   * One calculation with its pack, the pack's sources and a page of its lines.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param runId raw path value
   * @param calculationId raw path value
   * @param cursor opaque cursor, or {@code null}
   * @param limit raw page size, or {@code null}
   * @return the calculation, after its disclosure audit committed (first page)
   */
  public CalculationDetail get(
      PayrollCaller caller,
      String legalEntityId,
      String runId,
      String calculationId,
      String cursor,
      String limit) {
    return plumbing.observed(
        LOG,
        "statutory_calculation_read",
        READ,
        () -> {
          ErrorCode notFound = ErrorCode.STATUTORY_CALCULATION_NOT_FOUND;
          UUID entityId = PayrollCommands.id(legalEntityId, notFound);
          UUID run = PayrollCommands.id(runId, notFound);
          UUID id = PayrollCommands.id(calculationId, notFound);
          FieldErrors errors = new FieldErrors();
          int size =
              PayrollCommands.limit(
                  limit,
                  StatutoryCommands.DEFAULT_LINE_LIMIT,
                  StatutoryCommands.MAX_LINE_LIMIT,
                  errors);
          errors.throwIfAny();
          return plumbing.inReadTransaction(
              caller.tenant(),
              READ_TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                UUID entity =
                    entities.require(tenant, entityId, ErrorCode.STATUTORY_CALCULATION_NOT_FOUND);
                CalculationRow calculation =
                    statutory
                        .calculation(tenant, entity, run, id)
                        .orElseThrow(
                            () ->
                                new ApiException(
                                    ErrorCode.STATUTORY_CALCULATION_NOT_FOUND, Map.of()));
                CursorScope scope = scope(caller, "calculation", entity, run, id, size);
                String afterNumber = null;
                UUID afterId = null;
                if (cursor != null) {
                  KeysetPosition position = cursors.decode(cursor, scope);
                  LineRow at =
                      statutory
                          .line(tenant, id, position.id())
                          .filter(row -> LINE_CODE.equals(position.code()))
                          .orElseThrow(() -> new ApiException(ErrorCode.CURSOR_INVALID, Map.of()));
                  afterNumber = at.employeeNumber();
                  afterId = at.id();
                }
                List<LineRow> rows = statutory.linePage(tenant, id, afterNumber, afterId, size + 1);
                List<LineRow> shown = rows.subList(0, Math.min(rows.size(), size));
                String next = null;
                if (rows.size() > size) {
                  next =
                      cursors.encode(
                          scope, new KeysetPosition(LINE_CODE, shown.get(shown.size() - 1).id()));
                }
                Map<UUID, List<ComponentRow>> components =
                    statutory.components(tenant, id, shown.stream().map(LineRow::id).toList());
                List<RunLine> lines = new ArrayList<>();
                for (LineRow line : shown) {
                  lines.add(runLine(line, components.getOrDefault(line.id(), List.of())));
                }
                PackRow pack =
                    packs
                        .pack(calculation.packId())
                        .orElseThrow(
                            () ->
                                new ApiException(
                                    ErrorCode.STATUTORY_CALCULATION_NOT_FOUND, Map.of()));
                List<SourceView> sources =
                    packs.sources(pack.id()).stream().map(StatutoryViews::source).toList();
                if (cursor == null) {
                  recorder.disclosed(
                      caller,
                      READ,
                      "statutory-calculation",
                      id,
                      Map.of(
                          "legalEntityId",
                          entity.toString(),
                          "runId",
                          run.toString(),
                          "calculationId",
                          id.toString()),
                      "calculation",
                      true,
                      shown.size(),
                      calendar.now());
                }
                return new CalculationDetail(
                    StatutoryViews.summary(calculation),
                    StatutoryViews.summary(pack),
                    sources,
                    lines,
                    next);
              });
        });
  }

  private static RunLine runLine(LineRow line, List<ComponentRow> components) {
    List<Component> views = new ArrayList<>();
    for (ComponentRow component : components) {
      views.add(
          new Component(
              component.componentId(),
              component.party(),
              component.category(),
              StatutoryViews.cdf(component.baseMinor()),
              StatutoryViews.cdf(component.amountMinor()),
              component.explanationCodes(),
              StatutoryViews.trace(component.traceJson())));
    }
    return new RunLine(
        line.employeeId(),
        line.employeeNumber(),
        line.excluded(),
        StatutoryViews.cdf(line.grossMinor()),
        StatutoryViews.cdf(line.deductionsMinor()),
        StatutoryViews.cdf(line.contributionsMinor()),
        StatutoryViews.cdf(line.netMinor()),
        StatutoryViews.cdf(line.costMinor()),
        views);
  }

  private static CursorScope scope(
      PayrollCaller caller, String view, UUID entity, UUID run, UUID calculation, int size) {
    Map<String, String> filters = new TreeMap<>();
    filters.put("view", view);
    filters.put("entity", entity.toString());
    filters.put("run", run.toString());
    if (calculation != null) {
      filters.put("calculation", calculation.toString());
    }
    filters.put("role", ROLE);
    filters.put("subject", caller.subject());
    filters.put("limit", Integer.toString(size));
    return new CursorScope(READ, caller.tenant(), filters);
  }
}
