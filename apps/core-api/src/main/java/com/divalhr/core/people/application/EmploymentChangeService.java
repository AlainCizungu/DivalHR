package com.divalhr.core.people.application;

import com.divalhr.core.people.api.CancelEmploymentChangeRequest;
import com.divalhr.core.people.api.CreateEmploymentChangeRequest;
import com.divalhr.core.people.api.EmploymentChangeRequest;
import com.divalhr.core.people.api.EmploymentHistoryResponses.EmploymentChangeCancellationPreview;
import com.divalhr.core.people.api.EmploymentHistoryResponses.EmploymentChangePreview;
import com.divalhr.core.people.api.EmploymentHistoryResponses.EmploymentChangeResult;
import com.divalhr.core.people.api.EmploymentHistoryResponses.PreviewWarning;
import com.divalhr.core.people.domain.history.Assignment;
import com.divalhr.core.people.domain.history.AssignmentKind;
import com.divalhr.core.people.domain.history.AssignmentValue;
import com.divalhr.core.people.domain.history.ChangeReason;
import com.divalhr.core.people.domain.history.ChangeTiming;
import com.divalhr.core.people.domain.history.ChangeType;
import com.divalhr.core.people.domain.history.EmploymentTimeline;
import com.divalhr.core.people.domain.history.NewAssignment;
import com.divalhr.core.people.domain.history.TimelinePlan;
import com.divalhr.core.people.domain.history.TimelineRuleException;
import com.divalhr.core.people.internal.ConstraintViolations;
import com.divalhr.core.people.internal.JdbcEmploymentHistoryRepository;
import com.divalhr.core.people.internal.JdbcEmploymentHistoryRepository.ChangeRecord;
import com.divalhr.core.people.internal.JdbcEmploymentHistoryRepository.EmploymentRecord;
import com.divalhr.core.people.internal.TransactionLimits;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * MVP-021: previews, records and cancels effective-dated employment changes (H1-H12, M21-2, M21-4,
 * M21-5).
 *
 * <p>Commit order, in one bounded transaction after the idempotency key is reserved: the tenant's
 * manager-graph lock (when a MANAGER row is involved; the V14 trigger takes the same lock), the
 * employment row lock, the version check ({@code 409 EMPLOYMENT_VERSION_CONFLICT}), the full
 * recomputation and re-validation, the digest comparison ({@code 409 EMPLOYMENT_PREVIEW_CHANGED}),
 * then the writes: the change row, the supersession of the replaced rows, the new rows, the
 * version, the deferred database checks (run immediately so that a violation is mapped by
 * constraint name), and the audit and outbox records. Nothing is updated in place.
 */
@Service
public class EmploymentChangeService {

  /** Change preview operation. */
  public static final String PREVIEW = "employment-change.preview";

  /** Change commit operation. */
  public static final String CREATE = "employment-change.create";

  /** Cancellation preview operation. */
  public static final String CANCEL_PREVIEW = "employment-change.cancel-preview";

  /** Cancellation operation (and its audit action). */
  public static final String CANCEL = "employment-change.cancel";

  /** Audit action of a change effective after the business date. */
  public static final String SCHEDULE = "employment-change.schedule";

  /** Audit action of a change effective on or before the business date. */
  public static final String APPLY = "employment-change.apply";

  /** Audit action of a correction. */
  public static final String CORRECT = "employment-change.correct";

  /** Per-subject bucket of writes. */
  public static final String SUBJECT_WRITE_BUCKET = "employment-change";

  /** Per-tenant bucket of writes. */
  public static final String TENANT_WRITE_BUCKET = "employment-change-write";

  private static final IdempotentOperation.Spec CREATE_SPEC =
      new IdempotentOperation.Spec(CREATE, "employment_change", "create", "created", 201);
  private static final IdempotentOperation.Spec CANCEL_SPEC =
      new IdempotentOperation.Spec(CANCEL, "employment_change", "cancel", "cancelled", 200);

  private final JdbcEmploymentHistoryRepository history;
  private final EmploymentHistoryRules rules;
  private final EmploymentHistoryViews views;
  private final EmploymentHistoryEvents events;
  private final BusinessCalendar calendar;
  private final EmployeeDirectoryService directory;
  private final IdempotentOperation operations;
  private final TransactionLimits limits;
  private final EmploymentHistoryProperties properties;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param history history repository
   * @param rules hierarchy and manager rules
   * @param views response builder
   * @param events audit and outbox
   * @param calendar business date
   * @param directory fail-closed disclosure of previews
   * @param operations idempotent operation flow
   * @param limits transaction limits
   * @param properties settings
   */
  @Autowired
  public EmploymentChangeService(
      JdbcEmploymentHistoryRepository history,
      EmploymentHistoryRules rules,
      EmploymentHistoryViews views,
      EmploymentHistoryEvents events,
      BusinessCalendar calendar,
      EmployeeDirectoryService directory,
      IdempotentOperation operations,
      TransactionLimits limits,
      EmploymentHistoryProperties properties) {
    this(
        history,
        rules,
        views,
        events,
        calendar,
        directory,
        operations,
        limits,
        properties,
        Clock.systemUTC());
  }

  EmploymentChangeService(
      JdbcEmploymentHistoryRepository history,
      EmploymentHistoryRules rules,
      EmploymentHistoryViews views,
      EmploymentHistoryEvents events,
      BusinessCalendar calendar,
      EmployeeDirectoryService directory,
      IdempotentOperation operations,
      TransactionLimits limits,
      EmploymentHistoryProperties properties,
      Clock clock) {
    this.history = history;
    this.rules = rules;
    this.views = views;
    this.events = events;
    this.calendar = calendar;
    this.directory = directory;
    this.operations = operations;
    this.limits = limits;
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * A computed change: what it would write and the digest binding it.
   *
   * @param employment the employment (as read or locked)
   * @param timing timing
   * @param plan rows superseded and created
   * @param digest preview or cancellation digest
   * @param requiresReason whether a reason is needed
   * @param requiresAcknowledgement whether acknowledgeRetroactive must be true
   */
  private record Planned(
      EmploymentRecord employment,
      ChangeTiming timing,
      TimelinePlan plan,
      String digest,
      boolean requiresReason,
      boolean requiresAcknowledgement) {}

  // ------------------------------------------------------------------------------------------
  // Change and correction
  // ------------------------------------------------------------------------------------------

  /**
   * Previews a change or correction. Writes nothing but the disclosure audit record.
   *
   * @param caller verified caller
   * @param employeeId raw path value
   * @param request body
   * @return the preview
   */
  public EmploymentChangePreview preview(
      PeopleCaller caller, String employeeId, EmploymentChangeRequest request) {
    UUID id = directory.validated(PREVIEW, () -> EmployeeDirectoryService.employeeId(employeeId));
    EmploymentChangeCommand command =
        directory.validated(
            PREVIEW,
            () -> {
              FieldErrors errors = new FieldErrors();
              EmploymentChangeCommand parsed = EmploymentChangeCommand.parse(request, errors);
              errors.throwIfAny();
              return parsed;
            });
    TenantId tenant = caller.tenant();
    return directory.disclose(
        caller,
        PREVIEW,
        "change-preview",
        "first",
        () -> {
          EmploymentRecord employment = locate(tenant, id, command.effectiveFrom(), false);
          // The recorded change's ID is not known yet; the digest writes it as "self".
          Planned planned = planChange(tenant, id, employment, command, UUID.randomUUID());
          EmploymentChangePreview preview =
              new EmploymentChangePreview(
                  employment.version(),
                  planned.timing(),
                  planned.requiresReason(),
                  planned.requiresAcknowledgement(),
                  views.periods(tenant, planned.plan()),
                  planned.plan().laterChangeLimits()
                      ? List.of(PreviewWarning.LATER_CHANGE_LIMITS_PERIOD)
                      : List.of(),
                  planned.digest());
          return disclosure(preview, id, planned.plan());
        });
  }

  /** A validated commit. */
  private record Commit(
      EmploymentChangeCommand command, EmploymentChangeCommand.Confirmation confirmation) {}

  /**
   * Records a previewed change or correction, or replays an identical earlier one.
   *
   * @param caller verified caller
   * @param employeeId raw path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param request body
   * @return the recorded change and whether it was replayed
   */
  public IdempotentOperation.Result<EmploymentChangeResult> create(
      PeopleCaller caller,
      String employeeId,
      String idempotencyKey,
      CreateEmploymentChangeRequest request) {
    UUID id = EmployeeDirectoryService.employeeId(employeeId);
    Commit commit =
        operations.validated(
            CREATE_SPEC,
            () -> {
              FieldErrors errors = new FieldErrors();
              EmployeeImportService.requireKey(errors, idempotencyKey);
              EmploymentChangeCommand command = EmploymentChangeCommand.parse(request, errors);
              EmploymentChangeCommand.Confirmation confirmation =
                  EmploymentChangeCommand.confirmation(request, errors);
              errors.throwIfAny();
              return new Commit(command, confirmation);
            });
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("tenantId", caller.tenant().toString());
    canonical.put("employeeId", id.toString());
    canonical.put("command", commit.command().canonical());
    canonical.put("expectedVersion", commit.confirmation().expectedVersion());
    canonical.put("previewDigest", commit.confirmation().previewDigest());
    canonical.put("acknowledgeRetroactive", commit.confirmation().acknowledgeRetroactive());
    return bounded(
        () ->
            operations.execute(
                CREATE_SPEC,
                caller.subject(),
                idempotencyKey,
                canonical,
                EmploymentChangeResult.class,
                properties.transactionTimeout(),
                () -> createInTransaction(caller, id, commit)));
  }

  private IdempotentOperation.Completed<EmploymentChangeResult> createInTransaction(
      PeopleCaller caller, UUID employeeId, Commit commit) {
    TenantId tenant = caller.tenant();
    EmploymentChangeCommand command = commit.command();
    limits.apply(properties.statementTimeout());
    if (command.changes().containsKey(AssignmentKind.MANAGER)) {
      history.lockManagerGraph(tenant);
    }
    EmploymentRecord employment = locate(tenant, employeeId, command.effectiveFrom(), true);
    if (employment.version() != commit.confirmation().expectedVersion()) {
      throw new ApiException(ErrorCode.EMPLOYMENT_VERSION_CONFLICT, Map.of());
    }
    UUID changeId = UUID.randomUUID();
    Planned planned = planChange(tenant, employeeId, employment, command, changeId);
    if (!planned.digest().equals(commit.confirmation().previewDigest())) {
      throw new ApiException(ErrorCode.EMPLOYMENT_PREVIEW_CHANGED, Map.of());
    }
    if (planned.requiresAcknowledgement() && !commit.confirmation().acknowledgeRetroactive()) {
      new FieldErrors().add("acknowledgeRetroactive", Constraint.REQUIRED).throwIfAny();
    }
    Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    long version = employment.version() + 1;
    ChangeRecord change =
        new ChangeRecord(
            changeId,
            employeeId,
            employment.id(),
            command.type(),
            command.effectiveFrom(),
            command.changes().keySet(),
            command.reason(),
            planned.timing(),
            null,
            false,
            now,
            version);
    String action;
    if (command.type() == ChangeType.CORRECTION) {
      action = CORRECT;
    } else {
      action = planned.timing() == ChangeTiming.SCHEDULED ? SCHEDULE : APPLY;
    }
    write(
        caller,
        employment,
        change,
        planned.plan(),
        now,
        () -> {},
        action,
        EmploymentHistoryEvents.CHANGED,
        changeId);
    return new IdempotentOperation.Completed<>(
        new EmploymentChangeResult(EmploymentHistoryViews.change(change), version),
        changeId,
        Outcome.CREATED);
  }

  // ------------------------------------------------------------------------------------------
  // Cancellation
  // ------------------------------------------------------------------------------------------

  /**
   * Previews the cancellation of a scheduled change. Writes nothing but the disclosure audit
   * record.
   *
   * @param caller verified caller
   * @param employeeId raw path value
   * @param changeId raw path value
   * @return the preview
   */
  public EmploymentChangeCancellationPreview previewCancellation(
      PeopleCaller caller, String employeeId, String changeId) {
    UUID id =
        directory.validated(CANCEL_PREVIEW, () -> EmployeeDirectoryService.employeeId(employeeId));
    UUID cancelled = directory.validated(CANCEL_PREVIEW, () -> changeId(changeId));
    TenantId tenant = caller.tenant();
    return directory.disclose(
        caller,
        CANCEL_PREVIEW,
        "cancel-preview",
        "first",
        () -> {
          history.employee(tenant, id).orElseThrow(EmployeeDirectoryService::notFound);
          ChangeRecord change = change(tenant, id, cancelled);
          EmploymentRecord employment =
              history
                  .employmentById(tenant, change.employmentId())
                  .orElseThrow(() -> new IllegalStateException("change without employment"));
          Planned planned = planCancellation(tenant, id, change, employment);
          EmploymentChangeCancellationPreview preview =
              new EmploymentChangeCancellationPreview(
                  employment.version(), views.periods(tenant, planned.plan()), planned.digest());
          return disclosure(preview, id, planned.plan());
        });
  }

  /**
   * Cancels a scheduled change as previewed, or replays an identical earlier cancellation.
   *
   * @param caller verified caller
   * @param employeeId raw path value
   * @param changeId raw path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param request body
   * @return the cancellation and whether it was replayed
   */
  public IdempotentOperation.Result<EmploymentChangeResult> cancel(
      PeopleCaller caller,
      String employeeId,
      String changeId,
      String idempotencyKey,
      CancelEmploymentChangeRequest request) {
    UUID id = EmployeeDirectoryService.employeeId(employeeId);
    UUID cancelled = changeId(changeId);
    EmploymentChangeCommand.CancellationConfirmation confirmation =
        operations.validated(
            CANCEL_SPEC,
            () -> {
              FieldErrors errors = new FieldErrors();
              EmployeeImportService.requireKey(errors, idempotencyKey);
              EmploymentChangeCommand.CancellationConfirmation parsed =
                  EmploymentChangeCommand.cancellation(request, errors);
              errors.throwIfAny();
              return parsed;
            });
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("tenantId", caller.tenant().toString());
    canonical.put("employeeId", id.toString());
    canonical.put("changeId", cancelled.toString());
    canonical.put("expectedVersion", confirmation.expectedVersion());
    canonical.put("cancellationDigest", confirmation.cancellationDigest());
    return bounded(
        () ->
            operations.execute(
                CANCEL_SPEC,
                caller.subject(),
                idempotencyKey,
                canonical,
                EmploymentChangeResult.class,
                properties.transactionTimeout(),
                () -> cancelInTransaction(caller, id, cancelled, confirmation)));
  }

  private IdempotentOperation.Completed<EmploymentChangeResult> cancelInTransaction(
      PeopleCaller caller,
      UUID employeeId,
      UUID cancelledId,
      EmploymentChangeCommand.CancellationConfirmation confirmation) {
    TenantId tenant = caller.tenant();
    limits.apply(properties.statementTimeout());
    history.employee(tenant, employeeId).orElseThrow(EmployeeDirectoryService::notFound);
    ChangeRecord unlocked = change(tenant, employeeId, cancelledId);
    if (unlocked.kinds().contains(AssignmentKind.MANAGER)) {
      history.lockManagerGraph(tenant);
    }
    EmploymentRecord employment =
        history
            .lockEmployment(tenant, unlocked.employmentId())
            .orElseThrow(() -> new IllegalStateException("change without employment"));
    if (employment.version() != confirmation.expectedVersion()) {
      throw new ApiException(ErrorCode.EMPLOYMENT_VERSION_CONFLICT, Map.of());
    }
    // Re-read under the lock: a concurrent cancellation may have changed its state.
    ChangeRecord cancelled = change(tenant, employeeId, cancelledId);
    Planned planned = planCancellation(tenant, employeeId, cancelled, employment);
    if (!planned.digest().equals(confirmation.cancellationDigest())) {
      throw new ApiException(ErrorCode.EMPLOYMENT_PREVIEW_CHANGED, Map.of());
    }
    Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    long version = employment.version() + 1;
    UUID cancellationId = UUID.randomUUID();
    ChangeRecord cancellation =
        new ChangeRecord(
            cancellationId,
            employeeId,
            employment.id(),
            ChangeType.CANCELLATION,
            cancelled.effectiveFrom(),
            cancelled.kinds(),
            null,
            ChangeTiming.SCHEDULED,
            cancelledId,
            false,
            now,
            version);
    write(
        caller,
        employment,
        cancellation,
        planned.plan(),
        now,
        () -> history.markCancelled(tenant, cancelledId),
        CANCEL,
        EmploymentHistoryEvents.CHANGE_CANCELLED,
        cancelledId);
    return new IdempotentOperation.Completed<>(
        new EmploymentChangeResult(EmploymentHistoryViews.change(cancellation), version),
        cancellationId,
        Outcome.CREATED);
  }

  // ------------------------------------------------------------------------------------------
  // Planning
  // ------------------------------------------------------------------------------------------

  /** The employment a change at a date applies to; the employee must be in the tenant. */
  private EmploymentRecord locate(TenantId tenant, UUID employeeId, LocalDate day, boolean lock) {
    history.employee(tenant, employeeId).orElseThrow(EmployeeDirectoryService::notFound);
    EmploymentRecord employment =
        history
            .employment(tenant, employeeId, day)
            .orElseThrow(() -> new IllegalStateException("employee without employment"));
    if (!lock) {
      return employment;
    }
    return history
        .lockEmployment(tenant, employment.id())
        .orElseThrow(() -> new IllegalStateException("employment vanished"));
  }

  private Planned planChange(
      TenantId tenant,
      UUID employeeId,
      EmploymentRecord employment,
      EmploymentChangeCommand command,
      UUID changeId) {
    LocalDate today = calendar.today(tenant);
    int window = properties.retroactiveDays();
    List<Assignment> rows = history.assignments(tenant, employment.id());
    EmploymentTimeline timeline =
        new EmploymentTimeline(employment.start(), employment.end(), rows, UUID::randomUUID);
    LocalDate day = command.effectiveFrom();
    ChangeTiming timing;
    TimelinePlan plan;
    boolean requiresReason;
    boolean requiresAcknowledgement;
    if (command.type() == ChangeType.CHANGE) {
      timing = ChangeTiming.of(day, today);
      requiresReason = timing == ChangeTiming.RETROACTIVE;
      requiresAcknowledgement = requiresReason;
      EmploymentHistoryRules.checkWindow(day, today, window);
      if (requiresReason && command.reason() == null) {
        new FieldErrors().add("reasonCode", Constraint.REQUIRED).throwIfAny();
      }
      Map<AssignmentKind, Optional<AssignmentValue>> changes = new EnumMap<>(AssignmentKind.class);
      command
          .changes()
          .forEach((kind, value) -> changes.put(kind, value.map(v -> normalized(tenant, v))));
      plan = rule(() -> timeline.planChange(changeId, day, changes));
    } else {
      Assignment target =
          rows.stream()
              .filter(row -> row.active() && row.id().equals(command.correctsAssignmentId()))
              .findFirst()
              .orElse(null);
      FieldErrors errors = new FieldErrors();
      if (target == null) {
        errors.add("correctsAssignmentId", Constraint.RANGE).throwIfAny();
        throw new IllegalStateException("unreachable");
      }
      Map.Entry<AssignmentKind, Optional<AssignmentValue>> change =
          command.changes().entrySet().iterator().next();
      if (change.getKey() != target.kind()) {
        errors.add("changes", Constraint.FORMAT);
      }
      if (!day.equals(target.from())) {
        errors.add("effectiveFrom", Constraint.RANGE);
      }
      errors.throwIfAny();
      if (target.to() != null) {
        EmploymentHistoryRules.checkWindow(target.to(), today, window);
      }
      timing = ChangeTiming.of(target.from(), today);
      requiresReason = true;
      requiresAcknowledgement = false;
      AssignmentValue value = normalized(tenant, change.getValue().orElseThrow());
      plan = rule(() -> timeline.planCorrection(changeId, target, value));
    }
    for (NewAssignment row : plan.created()) {
      if (row.origin().equals(changeId)) {
        check(tenant, employeeId, row);
      }
    }
    String digest =
        EmploymentHistoryDigests.preview(
            employment.id(), employment.version(), timing, command, changeId, plan);
    return new Planned(employment, timing, plan, digest, requiresReason, requiresAcknowledgement);
  }

  private Planned planCancellation(
      TenantId tenant, UUID employeeId, ChangeRecord change, EmploymentRecord employment) {
    LocalDate today = calendar.today(tenant);
    if (change.type() != ChangeType.CHANGE
        || change.cancelled()
        // MVP-022: a separation's direct-report changes are reversed only with the separation.
        || change.reason() == ChangeReason.MANAGER_SEPARATED
        || !change.effectiveFrom().isAfter(today)) {
      throw new ApiException(ErrorCode.EMPLOYMENT_CHANGE_NOT_CANCELLABLE, Map.of());
    }
    List<Assignment> rows = history.assignments(tenant, employment.id());
    EmploymentTimeline timeline =
        new EmploymentTimeline(employment.start(), employment.end(), rows, UUID::randomUUID);
    TimelinePlan plan =
        rule(() -> timeline.planCancellation(change.id(), change.effectiveFrom(), change.kinds()));
    for (NewAssignment row : plan.created()) {
      check(tenant, employeeId, row);
    }
    String digest =
        EmploymentHistoryDigests.cancellation(
            employment.id(), employment.version(), change.id(), plan);
    return new Planned(employment, ChangeTiming.SCHEDULED, plan, digest, false, false);
  }

  private AssignmentValue normalized(TenantId tenant, AssignmentValue value) {
    return value instanceof AssignmentValue.Placement placement
        ? rules.normalize(tenant, placement)
        : value;
  }

  /** Re-validates a new or restored row against the hierarchy and the reporting graph. */
  private void check(TenantId tenant, UUID employeeId, NewAssignment row) {
    if (row.value() instanceof AssignmentValue.Placement placement) {
      rules.checkEffective(tenant, placement, row.from(), row.to());
    } else if (row.value() instanceof AssignmentValue.Manager manager) {
      rules.checkManager(tenant, employeeId, manager.employeeId(), row.from(), row.to());
    }
  }

  private static TimelinePlan rule(Supplier<TimelinePlan> planning) {
    try {
      return planning.get();
    } catch (TimelineRuleException refused) {
      throw switch (refused.rule()) {
        case NO_EFFECT ->
            new ApiException(
                ErrorCode.EMPLOYMENT_CHANGE_NO_EFFECT,
                refused.kind() == null ? Map.of() : Map.of("field", refused.kind().name()));
        case DATE_TAKEN -> new ApiException(ErrorCode.EMPLOYMENT_CHANGE_DATE_TAKEN, Map.of());
        case OUTSIDE_EMPLOYMENT ->
            new ApiException(ErrorCode.EMPLOYMENT_DATE_OUTSIDE_EMPLOYMENT, Map.of());
        case HAS_DEPENDENTS ->
            new ApiException(ErrorCode.EMPLOYMENT_CHANGE_HAS_DEPENDENTS, Map.of());
      };
    }
  }

  // ------------------------------------------------------------------------------------------
  // Writing
  // ------------------------------------------------------------------------------------------

  private void write(
      PeopleCaller caller,
      EmploymentRecord employment,
      ChangeRecord change,
      TimelinePlan plan,
      Instant now,
      Runnable afterInsert,
      String action,
      String eventType,
      UUID eventChangeId) {
    TenantId tenant = caller.tenant();
    try {
      history.insertChange(tenant, change, caller.subject());
      afterInsert.run();
      List<UUID> superseded = new ArrayList<>();
      plan.superseded().forEach(row -> superseded.add(row.id()));
      if (history.supersede(tenant, superseded, change.id(), now) != superseded.size()) {
        throw new ApiException(ErrorCode.EMPLOYMENT_PREVIEW_CHANGED, Map.of());
      }
      history.insertAssignments(tenant, employment, change.id(), plan.created());
      history.setVersion(tenant, employment.id(), change.versionAfter());
      history.checkDeferred(tenant);
    } catch (DataAccessException violated) {
      throw mapped(violated);
    }
    List<Assignment> after = history.assignments(tenant, employment.id());
    events.recorded(
        tenant,
        caller.subject(),
        action,
        eventType,
        new EmploymentHistoryEvents.Write(
            change.employeeId(),
            employment.id(),
            change.id(),
            eventChangeId,
            plan.superseded().size(),
            plan.created().size(),
            change.versionAfter(),
            EmploymentHistoryDigests.timeline(employment.id(), change.versionAfter(), after)),
        now,
        caller.correlationId());
  }

  /**
   * Maps only the named V14 constraints (M21-4); anything else stays an internal error.
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
      case "employment_assignment_manager_acyclic" ->
          new ApiException(ErrorCode.MANAGER_INVALID, Map.of("reason", "CYCLE"));
      case "employment_assignment_manager_depth" ->
          new ApiException(ErrorCode.MANAGER_INVALID, Map.of("reason", "CHAIN_TOO_DEEP"));
      case "employment_assignment_no_overlap", "employment_placement_coverage" ->
          new ApiException(ErrorCode.EMPLOYMENT_PREVIEW_CHANGED, Map.of());
      case "employment_change_cancelled_once" ->
          new ApiException(ErrorCode.EMPLOYMENT_CHANGE_NOT_CANCELLABLE, Map.of());
      default -> violated;
    };
  }

  // ------------------------------------------------------------------------------------------
  // Shared
  // ------------------------------------------------------------------------------------------

  private ChangeRecord change(TenantId tenant, UUID employeeId, UUID changeId) {
    return history
        .change(tenant, employeeId, changeId)
        .orElseThrow(() -> new ApiException(ErrorCode.EMPLOYMENT_CHANGE_NOT_FOUND, Map.of()));
  }

  /**
   * Parses a change ID; malformed IDs are indistinguishable from unknown ones.
   *
   * @param raw raw path value
   * @return the ID
   */
  static UUID changeId(String raw) {
    try {
      return EmployeeDirectoryService.employeeId(raw);
    } catch (ApiException malformed) {
      throw new ApiException(ErrorCode.EMPLOYMENT_CHANGE_NOT_FOUND, Map.of());
    }
  }

  private static <T> EmployeeDirectoryService.Disclosure<T> disclosure(
      T body, UUID employeeId, TimelinePlan plan) {
    return new EmployeeDirectoryService.Disclosure<>(
        body,
        "employee",
        employeeId,
        plan.superseded().size() + plan.created().size(),
        plan.superseded().stream().map(Assignment::id).toList());
  }

  /**
   * Runs a write and turns a statement, lock or transaction timeout into {@code 503
   * EMPLOYMENT_CHANGE_TIMEOUT}: the transaction was rolled back entirely.
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
