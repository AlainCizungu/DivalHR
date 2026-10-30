package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.CancelCompensationRequest;
import com.divalhr.core.payroll.api.CommitCompensationRequest;
import com.divalhr.core.payroll.api.CompensationCommandRequest;
import com.divalhr.core.payroll.api.CompensationResponses.CancellationPreview;
import com.divalhr.core.payroll.api.CompensationResponses.IntervalView;
import com.divalhr.core.payroll.api.CompensationResponses.MoneyView;
import com.divalhr.core.payroll.api.CompensationResponses.Preview;
import com.divalhr.core.payroll.api.CompensationResponses.Receipt;
import com.divalhr.core.payroll.api.CompensationResponses.RecordView;
import com.divalhr.core.payroll.application.CompensationCommands.Cancel;
import com.divalhr.core.payroll.application.CompensationCommands.Commit;
import com.divalhr.core.payroll.application.CompensationCommands.Draft;
import com.divalhr.core.payroll.application.CompensationReader.Loaded;
import com.divalhr.core.payroll.domain.CompensationAction;
import com.divalhr.core.payroll.domain.CompensationBlocker;
import com.divalhr.core.payroll.domain.CompensationDates.Span;
import com.divalhr.core.payroll.domain.CompensationEvaluator;
import com.divalhr.core.payroll.domain.CompensationEvaluator.Context;
import com.divalhr.core.payroll.domain.CompensationEvaluator.Evaluation;
import com.divalhr.core.payroll.domain.CompensationMoney;
import com.divalhr.core.payroll.domain.CompensationTimeline.Fact;
import com.divalhr.core.payroll.domain.CompensationTimeline.Interval;
import com.divalhr.core.payroll.domain.CompensationTimeline.Plan;
import com.divalhr.core.payroll.internal.CompensationConstraintViolations;
import com.divalhr.core.payroll.internal.JdbcCompensationRepository;
import com.divalhr.core.payroll.internal.JdbcCompensationRepository.ActiveFact;
import com.divalhr.core.payroll.internal.JdbcCompensationRepository.NewChange;
import com.divalhr.core.payroll.internal.JdbcPayrollRunRepository;
import com.divalhr.core.platform.access.EmploymentIntervals;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Monthly base compensation for tenant administrators with privileged MFA (MVP-093A, Issue #175,
 * ADR 0032): the record and its immutable history, previews, commits and the cancellation of a
 * scheduled change. Authorization (role {@code tenant-admin}, exact MFA, active membership) has run
 * before this; tenant, actor and scope never come from the request.
 *
 * <p>Boundary: recording compensation calculates nothing. Attendance, schedules and timesheets
 * never scale the amount, and nothing here prorates, prices, deducts, pays or produces a payslip.
 *
 * <p>Every command runs in one idempotent transaction in the global lock order: the idempotency
 * reservation, the employee's employment rows {@code FOR SHARE} through the people port, the
 * tenant payroll-population advisory lock in SHARED mode (5c, MVP-094A, ADR 0033 section 8: a
 * payroll run creation takes it exclusive, so no compensation command interleaves with a run's
 * recalculation), the employee's compensation advisory lock (5d), then the deferred checks, audit
 * and outbox. Under the
 * locks it re-reads the state, compares the version, the expiry and the digests, evaluates the
 * rules again and only then writes. Restricted financial and HR data: the amount, the explanation
 * and the before and after values never enter logs, metrics, audit metadata, outbox events,
 * idempotency receipts, cursors or error bodies.
 */
@Service
public class CompensationService {

  /** Read of the record (interceptor operation and first-page disclosure audit action). */
  public static final String READ = "compensation.read";

  /** Cursor pages of the history (disclosure audit action). */
  public static final String HISTORY_READ = "compensation.history-read";

  /** Preview (interceptor operation; disclosed as {@link #READ}). */
  public static final String PREVIEW = "compensation.preview";

  /** Commit (idempotency operation and interceptor operation). */
  public static final String COMMIT = "compensation.commit";

  /** Cancellation preview (interceptor operation; disclosed as {@link #READ}). */
  public static final String CANCEL_PREVIEW = "compensation.cancel-preview";

  /** Cancellation (idempotency operation, interceptor operation and audit action). */
  public static final String CANCEL = "compensation.scheduled-change-cancel";

  /** Per-subject reads. */
  public static final String SUBJECT_READ_BUCKET = "compensation-read";

  /** Per-subject previews. */
  public static final String SUBJECT_PREVIEW_BUCKET = "compensation-preview";

  /** Per-subject commands. */
  public static final String SUBJECT_WRITE_BUCKET = "compensation-write";

  /** Per-tenant previews. */
  public static final String TENANT_PREVIEW_BUCKET = "compensation-preview";

  /** Per-tenant commands. */
  public static final String TENANT_WRITE_BUCKET = "compensation-write";

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.compensation");
  private static final Duration TIMEOUT = Duration.ofSeconds(15);
  private static final IdempotentOperation.Spec COMMIT_SPEC =
      new IdempotentOperation.Spec(COMMIT, "compensation", "commit", "committed", 201);
  private static final IdempotentOperation.Spec CANCEL_SPEC =
      new IdempotentOperation.Spec(CANCEL, "compensation", "cancel", "cancelled", 201);

  private final IdempotentOperation operations;
  private final EmploymentIntervals employments;
  private final JdbcCompensationRepository compensation;
  private final JdbcPayrollRunRepository payrollRuns;
  private final CompensationReader reader;
  private final CompensationCalendar calendar;
  private final CompensationQuotas quotas;
  private final CompensationRecorder recorder;
  private final OperationMetrics metrics;
  private final PlatformTransactionManager transactionManager;

  /**
   * Creates the service.
   *
   * @param operations shared idempotent command flow
   * @param employments the people module's employment port
   * @param compensation compensation storage
   * @param payrollRuns payroll storage (the tenant payroll-population lock)
   * @param reader read model
   * @param calendar business date and preview window
   * @param quotas hourly quotas
   * @param recorder audit and outbox
   * @param metrics operation metrics
   * @param transactionManager transaction manager
   */
  public CompensationService(
      IdempotentOperation operations,
      EmploymentIntervals employments,
      JdbcCompensationRepository compensation,
      JdbcPayrollRunRepository payrollRuns,
      CompensationReader reader,
      CompensationCalendar calendar,
      CompensationQuotas quotas,
      CompensationRecorder recorder,
      OperationMetrics metrics,
      PlatformTransactionManager transactionManager) {
    this.operations = operations;
    this.employments = employments;
    this.compensation = compensation;
    this.payrollRuns = payrollRuns;
    this.reader = reader;
    this.calendar = calendar;
    this.quotas = quotas;
    this.recorder = recorder;
    this.metrics = metrics;
    this.transactionManager = transactionManager;
  }

  // ------------------------------------------------------------------------------------------
  // Read
  // ------------------------------------------------------------------------------------------

  /**
   * The employee's compensation and a page of its history.
   *
   * @param caller verified tenant administrator
   * @param employeeId raw path value
   * @param cursor opaque cursor, or {@code null}
   * @param limit raw page size, or {@code null}
   * @return the record, after its disclosure audit committed
   */
  public RecordView read(PayrollCaller caller, String employeeId, String cursor, String limit) {
    return observed(
        READ,
        () -> {
          UUID employee = CompensationCommands.id(employeeId);
          FieldErrors errors = new FieldErrors();
          int size = CompensationReader.limit(limit, errors);
          errors.throwIfAny();
          return inReadTransaction(
              caller.tenant(),
              () -> {
                TenantId tenant = caller.tenant();
                employments.read(tenant, employee).orElseThrow(CompensationCommands::notFound);
                Loaded loaded =
                    reader.load(tenant, employee, caller.subject(), true, READ, cursor, size);
                recorder.disclosed(
                    caller,
                    cursor == null ? READ : HISTORY_READ,
                    employee,
                    "record",
                    cursor == null,
                    loaded.changes().size(),
                    calendar.now());
                return new RecordView(
                    loaded.version(),
                    loaded.businessDate(),
                    loaded.current(),
                    loaded.scheduled(),
                    loaded.changes(),
                    loaded.nextCursor());
              });
        });
  }

  // ------------------------------------------------------------------------------------------
  // Previews
  // ------------------------------------------------------------------------------------------

  /**
   * Validates a command without writing: the intervals before and after, the warnings and the
   * stable blockers.
   *
   * @param caller verified tenant administrator
   * @param employeeId raw path value
   * @param body the command
   * @return the preview, after its disclosure audit committed
   */
  public Preview preview(PayrollCaller caller, String employeeId, CompensationCommandRequest body) {
    return observed(
        PREVIEW,
        () -> {
          UUID employee = CompensationCommands.id(employeeId);
          Draft draft = CompensationCommands.draft(body);
          quotas.requirePreview(caller.tenant(), caller.subject(), calendar.now());
          return inReadTransaction(
              caller.tenant(),
              () -> {
                TenantId tenant = caller.tenant();
                State state = state(tenant, employee, false);
                Instant now = calendar.now();
                UUID changeId = UUID.randomUUID();
                Evaluation evaluation =
                    CompensationEvaluator.evaluate(draft.command(), state.context(), changeId);
                Instant expiresAt = CompensationCalendar.expiry(now);
                Plan plan = evaluation.plan();
                String digest = null;
                if (plan != null) {
                  digest =
                      CompensationDigests.preview(
                          tenant,
                          employee,
                          draft,
                          state.context().version(),
                          state.employmentDigest(),
                          changeId,
                          plan,
                          expiresAt);
                }
                recorder.disclosed(caller, READ, employee, "preview", true, 1, now);
                return new Preview(
                    draft.action().name(),
                    echo(draft),
                    draft.effectiveFrom(),
                    plan == null ? List.of() : before(plan),
                    plan == null ? List.of() : CompensationReader.intervals(plan.created()),
                    names(evaluation.warnings()),
                    names(evaluation.blockers()),
                    draft.action() == CompensationAction.CORRECTED,
                    evaluation.requiresAcknowledgement(),
                    state.context().version(),
                    state.employmentDigest(),
                    digest,
                    digest == null ? null : expiresAt);
              });
        });
  }

  /**
   * Validates the cancellation of a scheduled change without writing.
   *
   * @param caller verified tenant administrator
   * @param employeeId raw path value
   * @param changeId raw path value
   * @return the preview, after its disclosure audit committed
   */
  public CancellationPreview previewCancellation(
      PayrollCaller caller, String employeeId, String changeId) {
    return observed(
        CANCEL_PREVIEW,
        () -> {
          UUID employee = CompensationCommands.id(employeeId);
          UUID target = CompensationCommands.id(changeId);
          quotas.requirePreview(caller.tenant(), caller.subject(), calendar.now());
          return inReadTransaction(
              caller.tenant(),
              () -> {
                TenantId tenant = caller.tenant();
                State state = state(tenant, employee, false);
                compensation
                    .change(tenant, employee, target)
                    .orElseThrow(CompensationCommands::notFound);
                Instant now = calendar.now();
                Evaluation evaluation =
                    CompensationEvaluator.evaluateCancellation(
                        target, isScheduledChange(state, target), state.context());
                Instant expiresAt = CompensationCalendar.expiry(now);
                Plan plan = evaluation.plan();
                String digest = null;
                if (plan != null) {
                  digest =
                      CompensationDigests.cancellation(
                          tenant,
                          employee,
                          target,
                          state.context().version(),
                          state.employmentDigest(),
                          plan,
                          expiresAt);
                }
                recorder.disclosed(caller, READ, employee, "cancellation-preview", true, 1, now);
                return new CancellationPreview(
                    target,
                    plan == null ? List.of() : before(plan),
                    plan == null ? List.of() : CompensationReader.intervals(plan.created()),
                    names(evaluation.warnings()),
                    names(evaluation.blockers()),
                    state.context().version(),
                    state.employmentDigest(),
                    digest,
                    digest == null ? null : expiresAt);
              });
        });
  }

  // ------------------------------------------------------------------------------------------
  // Commands
  // ------------------------------------------------------------------------------------------

  /**
   * Records a previewed command, or replays an identical one.
   *
   * @param caller verified tenant administrator
   * @param employeeId raw path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the previewed command with its digests
   * @return the amount-free receipt
   */
  public IdempotentOperation.Result<Receipt> commit(
      PayrollCaller caller,
      String employeeId,
      String idempotencyKey,
      CommitCompensationRequest body) {
    return command(
        COMMIT,
        () -> {
          record Parsed(UUID employee, Commit commit) {}
          Parsed parsed =
              operations.validated(
                  COMMIT_SPEC,
                  () ->
                      new Parsed(
                          CompensationCommands.id(employeeId),
                          CompensationCommands.commit(idempotencyKey, body)));
          return operations.execute(
              COMMIT_SPEC,
              caller.subject(),
              idempotencyKey,
              parsed.commit().canonical(caller.tenant(), parsed.employee()),
              Receipt.class,
              TIMEOUT,
              () -> committed(caller, parsed.employee(), parsed.commit()));
        });
  }

  /**
   * Cancels a scheduled change, or replays an identical cancellation.
   *
   * @param caller verified tenant administrator
   * @param employeeId raw path value
   * @param changeId raw path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the previewed cancellation with its digests
   * @return the amount-free receipt
   */
  public IdempotentOperation.Result<Receipt> cancel(
      PayrollCaller caller,
      String employeeId,
      String changeId,
      String idempotencyKey,
      CancelCompensationRequest body) {
    return command(
        CANCEL,
        () -> {
          record Parsed(UUID employee, Cancel cancel) {}
          Parsed parsed =
              operations.validated(
                  CANCEL_SPEC,
                  () ->
                      new Parsed(
                          CompensationCommands.id(employeeId),
                          CompensationCommands.cancel(
                              idempotencyKey, CompensationCommands.id(changeId), body)));
          return operations.execute(
              CANCEL_SPEC,
              caller.subject(),
              idempotencyKey,
              parsed.cancel().canonical(caller.tenant(), parsed.employee()),
              Receipt.class,
              TIMEOUT,
              () -> cancelled(caller, parsed.employee(), parsed.cancel()));
        });
  }

  private IdempotentOperation.Completed<Receipt> committed(
      PayrollCaller caller, UUID employee, Commit command) {
    TenantId tenant = caller.tenant();
    compensation.limits(tenant, TIMEOUT);
    Instant now = calendar.now();
    quotas.requireCommand(tenant, caller.subject(), now);
    State state = state(tenant, employee, true);
    requireFresh(
        state, command.expectedVersion(), command.employmentDigest(), command.expiresAt(), now);
    UUID changeId = UUID.randomUUID();
    Evaluation evaluation =
        CompensationEvaluator.evaluate(command.draft().command(), state.context(), changeId);
    if (!evaluation.committable()) {
      throw refused(evaluation.blockers().get(0));
    }
    Plan plan = Objects.requireNonNull(evaluation.plan(), "a committable evaluation has a plan");
    String digest =
        CompensationDigests.preview(
            tenant,
            employee,
            command.draft(),
            state.context().version(),
            state.employmentDigest(),
            changeId,
            plan,
            command.expiresAt());
    if (!digest.equals(command.previewDigest())) {
      throw new ApiException(ErrorCode.COMPENSATION_PREVIEW_STALE, Map.of());
    }
    if (evaluation.requiresAcknowledgement() && !command.acknowledgeRetroactive()) {
      FieldErrors errors = new FieldErrors();
      errors.add("acknowledgeRetroactive", Constraint.REQUIRED);
      errors.throwIfAny();
    }
    Draft draft = command.draft();
    CompensationMoney money = draft.amount().money();
    int sequence = state.context().version() + 1;
    NewChange change =
        new NewChange(
            changeId,
            sequence,
            draft.action(),
            now,
            caller.subject(),
            money,
            draft.effectiveFrom(),
            draft.target(),
            draft.reason(),
            draft.explanation(),
            command.acknowledgeRetroactive(),
            digest);
    write(tenant, employee, change, plan);
    recorder.recorded(
        caller,
        new CompensationRecorder.Recorded(
            employee,
            changeId,
            sequence,
            draft.action(),
            money.currency(),
            draft.effectiveFrom(),
            draft.target(),
            draft.reason() != null,
            evaluation.retroactive(),
            plan.superseded().size(),
            plan.created().size(),
            digest,
            now));
    logged(draft.action());
    return new IdempotentOperation.Completed<>(
        new Receipt(changeId, sequence, draft.action().name(), draft.effectiveFrom(), sequence),
        changeId,
        Outcome.CREATED);
  }

  private IdempotentOperation.Completed<Receipt> cancelled(
      PayrollCaller caller, UUID employee, Cancel command) {
    TenantId tenant = caller.tenant();
    compensation.limits(tenant, TIMEOUT);
    Instant now = calendar.now();
    quotas.requireCommand(tenant, caller.subject(), now);
    State state = state(tenant, employee, true);
    requireFresh(
        state, command.expectedVersion(), command.employmentDigest(), command.expiresAt(), now);
    compensation
        .change(tenant, employee, command.changeId())
        .orElseThrow(CompensationCommands::notFound);
    Evaluation evaluation =
        CompensationEvaluator.evaluateCancellation(
            command.changeId(), isScheduledChange(state, command.changeId()), state.context());
    if (!evaluation.committable()) {
      throw refused(evaluation.blockers().get(0));
    }
    Plan plan = Objects.requireNonNull(evaluation.plan(), "a committable evaluation has a plan");
    String digest =
        CompensationDigests.cancellation(
            tenant,
            employee,
            command.changeId(),
            state.context().version(),
            state.employmentDigest(),
            plan,
            command.expiresAt());
    if (!digest.equals(command.cancellationDigest())) {
      throw new ApiException(ErrorCode.COMPENSATION_PREVIEW_STALE, Map.of());
    }
    Interval cancelled = activeOf(state, command.changeId()).fact().interval();
    UUID changeId = UUID.randomUUID();
    int sequence = state.context().version() + 1;
    NewChange change =
        new NewChange(
            changeId,
            sequence,
            CompensationAction.SCHEDULED_CHANGE_CANCELLED,
            now,
            caller.subject(),
            cancelled.money(),
            cancelled.from(),
            command.changeId(),
            null,
            null,
            false,
            digest);
    write(tenant, employee, change, plan);
    recorder.recorded(
        caller,
        new CompensationRecorder.Recorded(
            employee,
            changeId,
            sequence,
            CompensationAction.SCHEDULED_CHANGE_CANCELLED,
            cancelled.money().currency(),
            cancelled.from(),
            command.changeId(),
            false,
            false,
            plan.superseded().size(),
            plan.created().size(),
            digest,
            now));
    logged(CompensationAction.SCHEDULED_CHANGE_CANCELLED);
    return new IdempotentOperation.Completed<>(
        new Receipt(
            changeId,
            sequence,
            CompensationAction.SCHEDULED_CHANGE_CANCELLED.name(),
            cancelled.from(),
            sequence),
        changeId,
        Outcome.CREATED);
  }

  /** Appends the change, supersedes what it replaces, writes its facts and runs the checks. */
  private void write(TenantId tenant, UUID employee, NewChange change, Plan plan) {
    try {
      compensation.insertChange(tenant, employee, change);
      compensation.supersede(
          tenant, employee, plan.superseded().stream().map(Fact::id).toList(), change.seq());
      for (Interval created : plan.created()) {
        compensation.insertFact(tenant, employee, change.seq(), created);
      }
      compensation.checkDeferred(tenant);
    } catch (DataIntegrityViolationException violated) {
      throw integrity(violated);
    }
  }

  /** The version, the expiry and the employment digest, under the locks. */
  private void requireFresh(
      State state, int expectedVersion, String employmentDigest, Instant expiresAt, Instant now) {
    if (state.context().version() != expectedVersion) {
      throw new ApiException(ErrorCode.COMPENSATION_VERSION_CONFLICT, Map.of());
    }
    if (!expiresAt.isAfter(now)
        || expiresAt.isAfter(CompensationCalendar.expiry(now))
        || !CompensationCalendar.onGrid(expiresAt)
        || !state.employmentDigest().equals(employmentDigest)) {
      throw new ApiException(ErrorCode.COMPENSATION_PREVIEW_STALE, Map.of());
    }
  }

  // ------------------------------------------------------------------------------------------
  // State and mapping
  // ------------------------------------------------------------------------------------------

  /**
   * The employee's employment and compensation state; a command holds its locks (steps 2, 5c and
   * 5d of ADR 0033 section 8).
   */
  private record State(Context context, List<ActiveFact> active, String employmentDigest) {}

  private State state(TenantId tenant, UUID employee, boolean lock) {
    List<EmploymentIntervals.Interval> found =
        (lock ? employments.lockShared(tenant, employee) : employments.read(tenant, employee))
            .orElseThrow(CompensationCommands::notFound);
    if (lock) {
      // 5c: shared with every other compensation command, exclusive for a payroll run creation.
      payrollRuns.lockPopulation(tenant, true);
      // 5d: the employee's compensation lock.
      compensation.lock(tenant, employee);
    }
    List<Span> spans = new ArrayList<>();
    for (EmploymentIntervals.Interval interval : found) {
      spans.add(new Span(interval.id(), interval.version(), interval.start(), interval.end()));
    }
    List<ActiveFact> active = compensation.active(tenant, employee);
    Context context =
        new Context(
            calendar.today(tenant),
            spans,
            active.stream().map(ActiveFact::fact).toList(),
            compensation.version(tenant, employee));
    return new State(context, active, CompensationDigests.employments(spans));
  }

  private static Optional<ActiveFact> find(State state, UUID origin) {
    return state.active().stream()
        .filter(entry -> entry.fact().interval().origin().equals(origin))
        .findFirst();
  }

  private static ActiveFact activeOf(State state, UUID origin) {
    return find(state, origin).orElseThrow(CompensationCommands::notFound);
  }

  private static boolean isScheduledChange(State state, UUID origin) {
    return find(state, origin)
        .filter(entry -> entry.originAction() == CompensationAction.CHANGED)
        .isPresent();
  }

  private static List<IntervalView> before(Plan plan) {
    return CompensationReader.intervals(plan.superseded().stream().map(Fact::interval).toList());
  }

  private static MoneyView echo(Draft draft) {
    CompensationMoney money = draft.amount().money();
    return money == null
        ? new MoneyView(draft.currency().name(), draft.amountText())
        : CompensationReader.money(money);
  }

  private static List<String> names(List<? extends Enum<?>> values) {
    return values.stream().map(Enum::name).toList();
  }

  /** The stable answer of the first blocker of a command that cannot be committed. */
  private static ApiException refused(CompensationBlocker blocker) {
    ErrorCode code =
        switch (blocker) {
          case AMOUNT_OUT_OF_RANGE -> ErrorCode.COMPENSATION_INVALID_AMOUNT;
          case DATE_OUT_OF_RANGE -> ErrorCode.COMPENSATION_DATE_OUT_OF_RANGE;
          case EMPLOYMENT_CONFLICT -> ErrorCode.COMPENSATION_EMPLOYMENT_CONFLICT;
          case LIMIT_REACHED -> ErrorCode.COMPENSATION_LIMIT_REACHED;
          case TRANSITION_NOT_ALLOWED, NO_CHANGE, NOT_CANCELLABLE ->
              ErrorCode.COMPENSATION_TRANSITION_NOT_ALLOWED;
        };
    return new ApiException(code, Map.of());
  }

  /** A guard that still fires is a lost race (or a defect); no database text is exposed. */
  private static RuntimeException integrity(DataIntegrityViolationException violated) {
    Optional<String> constraint = CompensationConstraintViolations.constraint(violated);
    if (constraint.filter(CompensationConstraintViolations.CHANGE_LIMIT::equals).isPresent()) {
      return new ApiException(ErrorCode.COMPENSATION_LIMIT_REACHED, Map.of());
    }
    if (constraint.filter(CompensationConstraintViolations.CONFLICT::contains).isPresent()) {
      return new ApiException(ErrorCode.COMPENSATION_VERSION_CONFLICT, Map.of());
    }
    return violated;
  }

  // ------------------------------------------------------------------------------------------
  // Transactions, metrics and logs
  // ------------------------------------------------------------------------------------------

  /** A bounded read transaction; the result is returned only after it committed. */
  private <T> T inReadTransaction(TenantId tenant, Supplier<T> work) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setTimeout((int) TIMEOUT.toSeconds());
    T result =
        template.execute(
            status -> {
              compensation.limits(tenant, TIMEOUT);
              return work.get();
            });
    return Objects.requireNonNull(result, "read transaction returned nothing");
  }

  /** Records a read or preview outcome; rejections log only their code. */
  private <T> T observed(String operation, Supplier<T> work) {
    try {
      T result = work.get();
      metrics.record(operation, Outcome.LISTED);
      LOG.atInfo()
          .addKeyValue("operation", operation)
          .addKeyValue("outcome", "listed")
          .log("compensation_read");
      return result;
    } catch (ApiException rejected) {
      Outcome outcome = outcomeOf(rejected.code());
      metrics.record(operation, outcome);
      LOG.atInfo()
          .addKeyValue("operation", operation)
          .addKeyValue("outcome", outcome.name().toLowerCase(Locale.ROOT))
          .addKeyValue("code", rejected.code().name())
          .log("compensation_read_rejected");
      throw rejected;
    } catch (RuntimeException failure) {
      if (DatabaseTimeouts.isTimeout(failure)) {
        metrics.record(operation, Outcome.FAILURE);
        throw new ApiException(ErrorCode.EMPLOYMENT_CHANGE_TIMEOUT, Map.of());
      }
      metrics.record(operation, Outcome.FAILURE);
      LOG.atWarn()
          .addKeyValue("operation", operation)
          .addKeyValue("outcome", "failure")
          .log("compensation_read_failed");
      throw failure;
    }
  }

  /** A command's work; a database time limit is the stable 503 (the transaction rolled back). */
  private <T> T command(String operation, Supplier<T> work) {
    try {
      return work.get();
    } catch (ApiException refused) {
      throw refused;
    } catch (RuntimeException failure) {
      if (DatabaseTimeouts.isTimeout(failure)) {
        metrics.record(operation, Outcome.FAILURE);
        throw new ApiException(ErrorCode.EMPLOYMENT_CHANGE_TIMEOUT, Map.of());
      }
      throw failure;
    }
  }

  private static Outcome outcomeOf(ErrorCode code) {
    return switch (code) {
      case COMPENSATION_NOT_FOUND -> Outcome.NOT_FOUND;
      case VALIDATION_FAILED, CURSOR_INVALID, COMPENSATION_INVALID_AMOUNT ->
          Outcome.VALIDATION_FAILED;
      case RATE_LIMITED -> Outcome.RATE_LIMITED;
      default -> Outcome.STATE_CONFLICT;
    };
  }

  private static void logged(CompensationAction action) {
    LOG.atInfo()
        .addKeyValue("operation", action.auditAction())
        .addKeyValue("outcome", "recorded")
        .log("compensation_recorded");
  }
}
