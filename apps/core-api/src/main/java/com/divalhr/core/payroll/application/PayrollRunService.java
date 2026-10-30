package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.CommitPayrollRunRequest;
import com.divalhr.core.payroll.api.PayrollResponses.PolicyAppliedView;
import com.divalhr.core.payroll.api.PayrollResponses.PreviewLine;
import com.divalhr.core.payroll.api.PayrollResponses.RangeView;
import com.divalhr.core.payroll.api.PayrollResponses.RunDetail;
import com.divalhr.core.payroll.api.PayrollResponses.RunLine;
import com.divalhr.core.payroll.api.PayrollResponses.RunPage;
import com.divalhr.core.payroll.api.PayrollResponses.RunPreview;
import com.divalhr.core.payroll.api.PayrollResponses.RunReceipt;
import com.divalhr.core.payroll.api.PayrollResponses.SourceView;
import com.divalhr.core.payroll.api.PayrollRunCommandRequest;
import com.divalhr.core.payroll.application.PayrollCommands.RunCommit;
import com.divalhr.core.payroll.application.PayrollCommands.RunDraft;
import com.divalhr.core.payroll.domain.CompensationCurrency;
import com.divalhr.core.payroll.domain.PayrollBlocker;
import com.divalhr.core.payroll.domain.PayrollCalculator;
import com.divalhr.core.payroll.domain.PayrollCalculator.Context;
import com.divalhr.core.payroll.domain.PayrollCalculator.Employee;
import com.divalhr.core.payroll.domain.PayrollCalculator.Fact;
import com.divalhr.core.payroll.domain.PayrollCalculator.Line;
import com.divalhr.core.payroll.domain.PayrollCalculator.Range;
import com.divalhr.core.payroll.domain.PayrollCalculator.Source;
import com.divalhr.core.payroll.domain.PayrollExplanation;
import com.divalhr.core.payroll.domain.PayrollPeriod;
import com.divalhr.core.payroll.domain.PayrollPolicy;
import com.divalhr.core.payroll.domain.PayrollPolicyTimeline;
import com.divalhr.core.payroll.internal.JdbcPayrollRunRepository;
import com.divalhr.core.payroll.internal.JdbcPayrollRunRepository.LineRow;
import com.divalhr.core.payroll.internal.JdbcPayrollRunRepository.NewLine;
import com.divalhr.core.payroll.internal.JdbcPayrollRunRepository.NewRun;
import com.divalhr.core.payroll.internal.JdbcPayrollRunRepository.RangeRow;
import com.divalhr.core.payroll.internal.JdbcPayrollRunRepository.RunPosition;
import com.divalhr.core.payroll.internal.JdbcPayrollRunRepository.RunRow;
import com.divalhr.core.payroll.internal.JdbcPayrollRunRepository.SourceRow;
import com.divalhr.core.payroll.internal.PayrollRunConstraintViolations;
import com.divalhr.core.platform.access.PayrollPopulation;
import com.divalhr.core.platform.access.PayrollPopulation.Member;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.tenancy.TenantId;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Monthly payroll runs of a legal entity for tenant administrators with privileged MFA (MVP-094A,
 * Issue #177, ADR 0033): the preview, the frozen creation and the reads. Authorization (role {@code
 * tenant-admin}, exact MFA, active membership) has run before this; tenant, actor and scope never
 * come from the request.
 *
 * <p>Boundary: a run records the contractual gross, the monthly base compensation after the
 * entity's proration policy. It is not taxable gross, net pay or money owed, and a draft is not
 * statutory payroll. Attendance, schedules and timesheets are never read: worked hours never scale
 * a monthly salary.
 *
 * <p>A creation runs in one idempotent transaction in the lock order of ADR 0033: the idempotency
 * reservation (0), every employment overlapping the month {@code FOR SHARE} through the people port
 * (2), the tenant payroll-population lock exclusive (5c), the legal entity's run lock (5e), then the
 * deferred checks, the audit record and the outbox event (6). Under the locks it re-reads the
 * policy, the population and the compensation, recomputes, and compares the version, the blockers,
 * the expiry and the digests before it writes anything. Restricted financial and HR data: amounts,
 * employee numbers, day counts, digests and explanations never enter logs, metrics, audit metadata,
 * outbox events, idempotency receipts, cursors or error bodies.
 */
@Service
public class PayrollRunService {

  /** Previews (interceptor operation; disclosed as a run read). */
  public static final String PREVIEW = "payroll-run.preview";

  /** Run list and detail reads (interceptor operation and disclosure audit action). */
  public static final String READ = "payroll-run.read";

  /** Creation (idempotency operation, interceptor operation and audit action). */
  public static final String CREATE = "payroll-run.create";

  /** Per-subject reads. */
  public static final String SUBJECT_READ_BUCKET = "payroll-run-read";

  /** Per-subject previews. */
  public static final String SUBJECT_PREVIEW_BUCKET = "payroll-run-preview";

  /** Per-subject creations. */
  public static final String SUBJECT_CREATE_BUCKET = "payroll-run-create";

  /** Per-tenant previews. */
  public static final String TENANT_PREVIEW_BUCKET = "payroll-run-preview";

  /** Per-tenant creations. */
  public static final String TENANT_CREATE_BUCKET = "payroll-run-create";

  /** The most employees a run calculates (409 above, before anything is calculated). */
  public static final int MAX_EMPLOYEES = 5_000;

  /** The most versions of one chain. */
  public static final int MAX_VERSIONS = 25;

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.payroll-run");
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(60);
  private static final String ROLE = "tenant-admin";
  private static final IdempotentOperation.Spec CREATE_SPEC =
      new IdempotentOperation.Spec(CREATE, "payroll_run", "create", "created", 201);

  private final IdempotentOperation operations;
  private final PayrollPopulation population;
  private final JdbcPayrollRunRepository runs;
  private final PayrollEntities entities;
  private final CompensationCalendar calendar;
  private final PayrollRecorder recorder;
  private final PayrollOperations plumbing;
  private final CursorCodec cursors;
  private final MeterRegistry meters;

  /**
   * Creates the service.
   *
   * @param operations shared idempotent command flow
   * @param population the people module's payroll population port
   * @param runs payroll storage
   * @param entities legal entity resolver
   * @param calendar business date and preview window
   * @param recorder audit and outbox
   * @param plumbing transactions, metrics and logs
   * @param cursors signed cursors
   * @param meters bounded calculation metrics
   */
  public PayrollRunService(
      IdempotentOperation operations,
      PayrollPopulation population,
      JdbcPayrollRunRepository runs,
      PayrollEntities entities,
      CompensationCalendar calendar,
      PayrollRecorder recorder,
      PayrollOperations plumbing,
      CursorCodec cursors,
      MeterRegistry meters) {
    this.operations = operations;
    this.population = population;
    this.runs = runs;
    this.entities = entities;
    this.calendar = calendar;
    this.recorder = recorder;
    this.plumbing = plumbing;
    this.cursors = cursors;
    this.meters = meters;
  }

  // ------------------------------------------------------------------------------------------
  // The calculation
  // ------------------------------------------------------------------------------------------

  /** Everything one calculation read and decided. */
  private record Computation(
      PayrollPolicy policy, List<Line> lines, String inputDigest, int members) {

    Computation {
      lines = List.copyOf(lines);
    }

    int withGross() {
      return (int) lines.stream().filter(Line::hasGross).count();
    }

    int blocked() {
      return (int) lines.stream().filter(Line::blocked).count();
    }

    long total() {
      long total = 0;
      for (Line line : lines) {
        if (line.hasGross()) {
          total += line.grossMinor();
        }
      }
      return total;
    }
  }

  /**
   * Reads the policy, the population, the employments, the placements and the compensation and
   * calculates every member. Under the creation's locks the result is stable; in a preview it is a
   * consistent best effort that the creation verifies again.
   */
  private Computation compute(TenantId tenant, UUID entity, RunDraft draft) {
    PayrollPeriod period = draft.period();
    List<PayrollPolicy> policies = runs.policies(tenant, entity);
    PayrollPolicy policy =
        PayrollPolicyTimeline.inForce(policies, period.last())
            .orElseThrow(() -> new ApiException(ErrorCode.PAYROLL_POLICY_NOT_FOUND, Map.of()));
    List<Member> members =
        population.read(tenant, entity, period.first(), period.last(), MAX_EMPLOYEES + 1);
    if (members.size() > MAX_EMPLOYEES) {
      throw new ApiException(ErrorCode.PAYROLL_RUN_LIMIT_REACHED, Map.of());
    }
    Set<UUID> ids = new HashSet<>();
    for (Member member : members) {
      ids.add(member.employeeId());
    }
    Map<UUID, List<Fact>> facts = runs.facts(tenant, ids, period.first(), period.last());
    Context context = new Context(entity, period, draft.currency(), policy.method());
    List<Employee> employees = new ArrayList<>();
    List<Line> lines = new ArrayList<>();
    for (Member member : members) {
      Employee employee =
          new Employee(
              member.employeeId(),
              member.employeeNumber(),
              member.employments().stream()
                  .map(e -> new PayrollCalculator.Employment(e.id(), e.version(), e.start(), e.end()))
                  .toList(),
              member.placements().stream()
                  .map(p -> new PayrollCalculator.Placement(p.id(), p.legalEntityId(), p.start(), p.end()))
                  .toList(),
              facts.getOrDefault(member.employeeId(), List.of()));
      employees.add(employee);
      lines.add(PayrollCalculator.calculate(context, employee));
    }
    String digest =
        PayrollDigests.input(tenant, entity, period, draft.currency(), policy, employees);
    return new Computation(policy, lines, digest, members.size());
  }

  private static void requirePeriod(PayrollPeriod period, LocalDate today) {
    if (!period.isRunnable(today)) {
      throw new ApiException(ErrorCode.PAYROLL_PERIOD_INVALID, Map.of());
    }
  }

  // ------------------------------------------------------------------------------------------
  // Preview
  // ------------------------------------------------------------------------------------------

  /**
   * Calculates a month without writing: the policy, the population, the blockers, the contractual
   * gross of each employee and the digests.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param body the month and currency
   * @return the preview, after its disclosure audit committed
   */
  public RunPreview preview(
      PayrollCaller caller, String legalEntityId, PayrollRunCommandRequest body) {
    long started = System.nanoTime();
    int[] counts = {0, 0};
    try {
      RunPreview preview =
          plumbing.observed(
              LOG,
              "payroll_run_preview",
              PREVIEW,
              () -> {
                PayrollCommands.id(legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
                RunDraft draft = PayrollCommands.runDraft(body);
                return plumbing.inReadTransaction(
                    caller.tenant(),
                    READ_TIMEOUT,
                    () -> {
                      TenantId tenant = caller.tenant();
                      UUID entity =
                          entities.require(
                              tenant, legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
                      Instant now = calendar.now();
                      requirePeriod(draft.period(), calendar.today(tenant));
                      Computation computation = compute(tenant, entity, draft);
                      counts[0] = computation.withGross();
                      counts[1] = computation.blocked();
                      Optional<RunRow> current =
                          runs.draft(
                              tenant, entity, draft.period().first(), draft.currency());
                      int version = current.map(RunRow::version).orElse(0);
                      boolean identical =
                          current
                              .filter(row -> row.inputDigest().equals(computation.inputDigest()))
                              .isPresent();
                      boolean creatable =
                          computation.blocked() == 0 && computation.withGross() > 0 && !identical;
                      Instant expiresAt = CompensationCalendar.expiry(now);
                      String previewDigest =
                          creatable
                              ? PayrollDigests.run(
                                  tenant,
                                  entity,
                                  draft.period(),
                                  draft.currency(),
                                  version,
                                  computation.inputDigest(),
                                  expiresAt)
                              : null;
                      recorder.disclosed(
                          caller,
                          PayrollRecorder.RUN_READ,
                          "payroll-run",
                          entity,
                          entity,
                          "preview",
                          true,
                          computation.members(),
                          now);
                      List<PreviewLine> lines =
                          computation.lines().stream()
                              .map(line -> previewLine(draft.currency(), line))
                              .toList();
                      return new RunPreview(
                          entity,
                          draft.period().text(),
                          draft.currency().name(),
                          PayrollViews.applied(computation.policy()),
                          version,
                          current.map(PayrollViews::summary).orElse(null),
                          identical,
                          computation.withGross(),
                          computation.blocked(),
                          PayrollViews.money(draft.currency(), computation.total()),
                          lines,
                          computation.inputDigest(),
                          previewDigest,
                          previewDigest == null ? null : expiresAt);
                    });
              });
      measure(PREVIEW, "listed", started, counts[0], counts[1]);
      return preview;
    } catch (ApiException rejected) {
      measure(PREVIEW, PayrollOperations.outcomeOf(rejected.code()).name(), started, 0, 0);
      throw rejected;
    }
  }

  private static PreviewLine previewLine(CompensationCurrency currency, Line line) {
    return new PreviewLine(
        line.employeeId(),
        line.employeeNumber(),
        line.eligibleDays(),
        ranges(line.ranges()),
        line.denominator(),
        line.fullMonth(),
        line.hasGross() ? PayrollViews.money(currency, line.monthlyMinor()) : null,
        line.hasGross() ? PayrollViews.money(currency, line.grossMinor()) : null,
        line.explanations().stream().map(PayrollExplanation::name).toList(),
        line.blockers().stream().map(PayrollBlocker::name).toList());
  }

  private static List<RangeView> ranges(List<Range> ranges) {
    return ranges.stream().map(range -> new RangeView(range.from(), range.to())).toList();
  }

  // ------------------------------------------------------------------------------------------
  // Creation
  // ------------------------------------------------------------------------------------------

  /**
   * Creates a previewed run, or replays an identical creation.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the previewed month with its digests
   * @return the amount-free receipt
   */
  public IdempotentOperation.Result<RunReceipt> create(
      PayrollCaller caller,
      String legalEntityId,
      String idempotencyKey,
      CommitPayrollRunRequest body) {
    long started = System.nanoTime();
    int[] counts = {0, 0};
    IdempotentOperation.Result<RunReceipt> result =
        plumbing.command(
            CREATE,
            () -> {
              record Parsed(UUID entity, RunCommit commit) {}
              Parsed parsed =
                  operations.validated(
                      CREATE_SPEC,
                      () ->
                          new Parsed(
                              PayrollCommands.id(legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND),
                              PayrollCommands.runCommit(idempotencyKey, body)));
              return operations.execute(
                  CREATE_SPEC,
                  caller.subject(),
                  idempotencyKey,
                  parsed.commit().canonical(caller.tenant(), parsed.entity()),
                  RunReceipt.class,
                  WRITE_TIMEOUT,
                  () -> created(caller, parsed.entity(), parsed.commit(), counts));
            });
    measure(CREATE, result.replayed() ? "replayed" : "created", started, counts[0], counts[1]);
    return result;
  }

  private IdempotentOperation.Completed<RunReceipt> created(
      PayrollCaller caller, UUID entity, RunCommit command, int[] counts) {
    TenantId tenant = caller.tenant();
    runs.limits(tenant, WRITE_TIMEOUT);
    RunDraft draft = command.draft();
    PayrollPeriod period = draft.period();
    entities.require(tenant, entity, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
    Instant now = calendar.now();
    requirePeriod(period, calendar.today(tenant));

    // Lock order (ADR 0033 section 8): 2 employment rows FOR SHARE, 5c the tenant population lock
    // exclusive, 5e the legal entity's run lock; only then is anything read.
    population.lockShared(tenant, period.first(), period.last());
    runs.lockPopulation(tenant, false);
    runs.lockRun(tenant, entity);

    Computation computation = compute(tenant, entity, draft);
    counts[0] = computation.withGross();
    counts[1] = computation.blocked();
    Optional<RunRow> current = runs.draft(tenant, entity, period.first(), draft.currency());
    int version = current.map(RunRow::version).orElse(0);
    if (version != command.expectedVersion()) {
      throw new ApiException(ErrorCode.PAYROLL_RUN_VERSION_CONFLICT, Map.of());
    }
    if (computation.blocked() > 0 || computation.withGross() == 0) {
      throw new ApiException(ErrorCode.PAYROLL_RUN_BLOCKED, Map.of());
    }
    Instant expiresAt = command.expiresAt();
    String previewDigest =
        PayrollDigests.run(
            tenant,
            entity,
            period,
            draft.currency(),
            version,
            computation.inputDigest(),
            expiresAt);
    if (!expiresAt.isAfter(now)
        || expiresAt.isAfter(CompensationCalendar.expiry(now))
        || !CompensationCalendar.onGrid(expiresAt)
        || !computation.inputDigest().equals(command.inputDigest())
        || !previewDigest.equals(command.previewDigest())) {
      throw new ApiException(ErrorCode.PAYROLL_RUN_PREVIEW_STALE, Map.of());
    }
    if (current.isPresent() && current.get().inputDigest().equals(computation.inputDigest())) {
      // Nothing changed since the current draft: nothing is recorded, no version is created.
      throw new ApiException(ErrorCode.PAYROLL_RUN_PREVIEW_STALE, Map.of());
    }
    if (version >= MAX_VERSIONS) {
      throw new ApiException(ErrorCode.PAYROLL_RUN_LIMIT_REACHED, Map.of());
    }

    UUID runId = UUID.randomUUID();
    List<NewLine> lines = new ArrayList<>();
    for (Line line : computation.lines()) {
      if (line.hasGross()) {
        lines.add(newLine(line));
      }
    }
    NewRun run =
        new NewRun(
            runId,
            entity,
            period.first(),
            draft.currency(),
            version + 1,
            computation.policy(),
            computation.withGross(),
            computation.total(),
            computation.inputDigest(),
            now,
            caller.subject());
    try {
      // Supersede the old DRAFT first, naming the successor (its key is checked at commit), then
      // insert the successor, its lines, ranges and sources.
      if (current.isPresent()) {
        int changed = runs.supersede(tenant, entity, current.get().id(), runId, now);
        if (changed != 1) {
          throw new ApiException(ErrorCode.PAYROLL_RUN_VERSION_CONFLICT, Map.of());
        }
      }
      runs.insertRun(tenant, run);
      runs.insertLines(tenant, runId, lines);
      runs.checkDeferred(tenant);
    } catch (DataIntegrityViolationException violated) {
      throw integrity(violated);
    }
    UUID superseded = current.map(RunRow::id).orElse(null);
    recorder.runCreated(
        caller,
        new PayrollRecorder.RunRecorded(
            entity,
            runId,
            version + 1,
            period.text(),
            draft.currency(),
            computation.policy().sequence(),
            computation.withGross(),
            superseded,
            computation.inputDigest(),
            now));
    LOG.atInfo()
        .addKeyValue("operation", CREATE)
        .addKeyValue("outcome", "recorded")
        .addKeyValue("employees", employeeBucket(computation.withGross()))
        .log("payroll_run_recorded");
    return new IdempotentOperation.Completed<>(
        new RunReceipt(
            runId,
            entity,
            period.text(),
            draft.currency().name(),
            version + 1,
            "DRAFT",
            superseded,
            computation.withGross()),
        runId,
        Outcome.CREATED);
  }

  private static NewLine newLine(Line line) {
    UUID lineId = UUID.randomUUID();
    List<RangeRow> ranges = new ArrayList<>();
    for (int i = 0; i < line.ranges().size(); i++) {
      Range range = line.ranges().get(i);
      ranges.add(new RangeRow(lineId, i + 1, range.from(), range.to()));
    }
    List<SourceRow> sources = new ArrayList<>();
    for (int i = 0; i < line.sources().size(); i++) {
      Source source = line.sources().get(i);
      sources.add(
          new SourceRow(
              lineId, i + 1, source.kind(), source.id(), source.version(), source.from(), source.to()));
    }
    LineRow row =
        new LineRow(
            lineId,
            line.employeeId(),
            line.employeeNumber(),
            line.eligibleDays(),
            line.denominator(),
            line.fullMonth(),
            line.monthlyMinor(),
            line.grossMinor(),
            line.explanations());
    return new NewLine(row, ranges, sources);
  }

  /** A guard that still fires is a lost race (or a defect); no database text is exposed. */
  private static RuntimeException integrity(DataIntegrityViolationException violated) {
    Optional<ErrorCode> answer =
        PayrollRunConstraintViolations.constraint(violated)
            .flatMap(PayrollRunConstraintViolations::answer);
    if (answer.isPresent()) {
      return new ApiException(answer.get(), Map.of());
    }
    return violated;
  }

  // ------------------------------------------------------------------------------------------
  // Reads
  // ------------------------------------------------------------------------------------------

  /**
   * A page of the legal entity's run versions.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param cursor opaque cursor, or {@code null}
   * @param limit raw page size, or {@code null}
   * @return the page, after its disclosure audit committed
   */
  public RunPage list(PayrollCaller caller, String legalEntityId, String cursor, String limit) {
    return plumbing.observed(
        LOG,
        "payroll_run_read",
        READ,
        () -> {
          PayrollCommands.id(legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
          FieldErrors errors = new FieldErrors();
          int size =
              PayrollCommands.limit(
                  limit, PayrollCommands.DEFAULT_RUN_LIMIT, PayrollCommands.MAX_RUN_LIMIT, errors);
          errors.throwIfAny();
          return plumbing.inReadTransaction(
              caller.tenant(),
              READ_TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                UUID entity =
                    entities.require(tenant, legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
                CursorScope scope = scope(caller, "list", entity, null, size);
                RunPosition after = null;
                if (cursor != null) {
                  KeysetPosition position = cursors.decode(cursor, scope);
                  RunRow at =
                      runs.run(tenant, entity, position.id())
                          .filter(row -> runCode(row).equals(position.code()))
                          .orElseThrow(() -> new ApiException(ErrorCode.CURSOR_INVALID, Map.of()));
                  after =
                      new RunPosition(
                          at.periodStart(), at.currency().name(), at.version(), at.id());
                }
                List<RunRow> rows = runs.runPage(tenant, entity, after, size + 1);
                List<RunRow> shown = rows.subList(0, Math.min(rows.size(), size));
                String next = null;
                if (rows.size() > size) {
                  RunRow last = shown.get(shown.size() - 1);
                  next = cursors.encode(scope, new KeysetPosition(runCode(last), last.id()));
                }
                recorder.disclosed(
                    caller,
                    PayrollRecorder.RUN_READ,
                    "payroll-run",
                    entity,
                    entity,
                    "list",
                    cursor == null,
                    shown.size(),
                    calendar.now());
                return new RunPage(shown.stream().map(PayrollViews::summary).toList(), next);
              });
        });
  }

  /**
   * One run with a page of its lines.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param runId raw path value
   * @param cursor opaque cursor, or {@code null}
   * @param limit raw page size, or {@code null}
   * @return the run, after its disclosure audit committed
   */
  public RunDetail get(
      PayrollCaller caller, String legalEntityId, String runId, String cursor, String limit) {
    return plumbing.observed(
        LOG,
        "payroll_run_read",
        READ,
        () -> {
          UUID entityId = PayrollCommands.id(legalEntityId, ErrorCode.PAYROLL_RUN_NOT_FOUND);
          UUID id = PayrollCommands.id(runId, ErrorCode.PAYROLL_RUN_NOT_FOUND);
          FieldErrors errors = new FieldErrors();
          int size =
              PayrollCommands.limit(
                  limit,
                  PayrollCommands.DEFAULT_LINE_LIMIT,
                  PayrollCommands.MAX_LINE_LIMIT,
                  errors);
          errors.throwIfAny();
          return plumbing.inReadTransaction(
              caller.tenant(),
              READ_TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                UUID entity = entities.require(tenant, entityId, ErrorCode.PAYROLL_RUN_NOT_FOUND);
                RunRow run =
                    runs.run(tenant, entity, id)
                        .orElseThrow(
                            () -> new ApiException(ErrorCode.PAYROLL_RUN_NOT_FOUND, Map.of()));
                CursorScope scope = scope(caller, "run", entity, id, size);
                String afterNumber = null;
                UUID afterId = null;
                if (cursor != null) {
                  KeysetPosition position = cursors.decode(cursor, scope);
                  LineRow at =
                      runs.line(tenant, id, position.id())
                          .filter(line -> "L1".equals(position.code()))
                          .orElseThrow(() -> new ApiException(ErrorCode.CURSOR_INVALID, Map.of()));
                  afterNumber = at.employeeNumber();
                  afterId = at.id();
                }
                List<LineRow> rows = runs.linePage(tenant, id, afterNumber, afterId, size + 1);
                List<LineRow> shown = rows.subList(0, Math.min(rows.size(), size));
                String next = null;
                if (rows.size() > size) {
                  next =
                      cursors.encode(
                          scope, new KeysetPosition("L1", shown.get(shown.size() - 1).id()));
                }
                List<UUID> lineIds = shown.stream().map(LineRow::id).toList();
                Map<UUID, List<RangeRow>> ranges = runs.ranges(tenant, id, lineIds);
                Map<UUID, List<SourceRow>> sources = runs.sources(tenant, id, lineIds);
                List<RunLine> lines = new ArrayList<>();
                for (LineRow line : shown) {
                  lines.add(runLine(run.currency(), line, ranges, sources));
                }
                recorder.disclosed(
                    caller,
                    PayrollRecorder.RUN_READ,
                    "payroll-run",
                    id,
                    entity,
                    "run",
                    cursor == null,
                    shown.size(),
                    calendar.now());
                return new RunDetail(
                    PayrollViews.summary(run),
                    new PolicyAppliedView(
                        run.policyId(),
                        run.policySequence(),
                        PayrollViews.payDay(run.payDay()),
                        run.method().name()),
                    run.inputDigest(),
                    lines,
                    next);
              });
        });
  }

  private static RunLine runLine(
      CompensationCurrency currency,
      LineRow line,
      Map<UUID, List<RangeRow>> ranges,
      Map<UUID, List<SourceRow>> sources) {
    return new RunLine(
        line.employeeId(),
        line.employeeNumber(),
        line.eligibleDays(),
        ranges.getOrDefault(line.id(), List.of()).stream()
            .map(range -> new RangeView(range.from(), range.to()))
            .toList(),
        line.denominator(),
        line.fullMonth(),
        PayrollViews.money(currency, line.monthlyMinor()),
        PayrollViews.money(currency, line.grossMinor()),
        line.explanations().stream().map(PayrollExplanation::name).toList(),
        sources.getOrDefault(line.id(), List.of()).stream()
            .map(
                source ->
                    new SourceView(
                        source.kind().name(),
                        source.sourceId(),
                        source.sourceVersion(),
                        source.from(),
                        source.to()))
            .toList());
  }

  private static CursorScope scope(
      PayrollCaller caller, String view, UUID entity, UUID run, int size) {
    Map<String, String> filters = new TreeMap<>();
    filters.put("view", view);
    filters.put("entity", entity.toString());
    if (run != null) {
      filters.put("run", run.toString());
    }
    filters.put("role", ROLE);
    filters.put("subject", caller.subject());
    filters.put("limit", Integer.toString(size));
    return new CursorScope(READ, caller.tenant(), filters);
  }

  /** The cursor code of a run: month, currency and version only. */
  private static String runCode(RunRow run) {
    YearMonth month = YearMonth.from(run.periodStart());
    return String.format(
        Locale.ROOT,
        "R%04d%02d%s%d",
        month.getYear(),
        month.getMonthValue(),
        run.currency().name(),
        run.version());
  }

  // ------------------------------------------------------------------------------------------
  // Metrics
  // ------------------------------------------------------------------------------------------

  /** Safe metrics: operation, outcome, employee-count bucket and blocker-count bucket only. */
  private void measure(String operation, String outcome, long startedNanos, int employees, int blocked) {
    String tagOutcome = outcome.toLowerCase(Locale.ROOT);
    meters
        .counter(
            "divalhr.payroll.run.calculations",
            "operation",
            operation,
            "outcome",
            tagOutcome,
            "employees",
            employeeBucket(employees),
            "blockers",
            blockerBucket(blocked))
        .increment();
    meters
        .timer("divalhr.payroll.run.duration", "operation", operation, "outcome", tagOutcome)
        .record(Duration.ofNanos(System.nanoTime() - startedNanos));
  }

  static String employeeBucket(int employees) {
    if (employees == 0) {
      return "0";
    }
    if (employees < 10) {
      return "1-9";
    }
    if (employees < 100) {
      return "10-99";
    }
    return employees < 1000 ? "100-999" : "1000+";
  }

  static String blockerBucket(int blocked) {
    if (blocked == 0) {
      return "0";
    }
    return blocked < 10 ? "1-9" : "10+";
  }
}
