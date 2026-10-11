package com.divalhr.core.people.schedule.application;

import com.divalhr.core.people.application.BusinessCalendar;
import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.schedule.api.CancelWorkScheduleRequest;
import com.divalhr.core.people.schedule.api.ReplaceWorkScheduleRequest;
import com.divalhr.core.people.schedule.api.WorkScheduleReplacementRequest;
import com.divalhr.core.people.schedule.api.WorkScheduleResponses.ChangeReceipt;
import com.divalhr.core.people.schedule.api.WorkScheduleResponses.Preview;
import com.divalhr.core.people.schedule.api.WorkScheduleResponses.Totals;
import com.divalhr.core.people.schedule.application.WorkScheduleChangeCommand.Cancellation;
import com.divalhr.core.people.schedule.application.WorkScheduleChangeCommand.Proposal;
import com.divalhr.core.people.schedule.application.WorkScheduleChangeCommand.Replacement;
import com.divalhr.core.people.schedule.application.WorkSchedulePlanner.Plan;
import com.divalhr.core.people.schedule.application.WorkSchedulePlanner.Replacing;
import com.divalhr.core.people.schedule.domain.Lifecycle;
import com.divalhr.core.people.schedule.domain.PlannedSlot;
import com.divalhr.core.people.schedule.domain.ScheduleView;
import com.divalhr.core.people.schedule.domain.SlotDraft;
import com.divalhr.core.people.schedule.domain.WorkSchedule;
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
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * Cancellation and full replacement of an entirely future published schedule (MVP-050B, D50B-1 to
 * D50B-11; ADR 0014). The original schedule and its slots stay immutable evidence: a cancellation
 * or a replacement appends one terminal record and removes the original's operational coverage, so
 * its site dates and employee instants become free; a replacement also publishes the successor, of
 * the same site and period, with its own coverage. A schedule has at most one terminal action, and
 * a replaced schedule's successor may itself be replaced (A, B, C, never a branch or a cycle).
 *
 * <p>The change window: both actions, and a replacement preview, require every original slot to
 * start strictly after one server instant from the platform clock ({@code 409
 * WORK_SCHEDULE_CHANGE_WINDOW_CLOSED} otherwise), and every proposed replacement slot to start
 * after it too (slot issue {@code NOT_FUTURE}). The mutation checks the window again; a preview
 * never holds it open.
 *
 * <p>Lock order: idempotency (0), the tenant's scheduling lock (1), the original schedule {@code
 * FOR UPDATE} (2), the replacement's employees' employments {@code FOR SHARE} in UUID order (3),
 * then the evaluation, the evidence and coverage changes, the deferred checks, the audit record and
 * the outbox event, committed with the idempotency completion. Any failure leaves the original
 * operational.
 *
 * <p>The reason is Restricted HR: stored as evidence, returned to tenant administrators only, never
 * in audit metadata, events, logs, metrics or errors (only inside the audit's state digest).
 */
@Service
public class WorkScheduleChangeService {

  /** Cancellation (idempotency operation, interceptor operation and audit action). */
  public static final String CANCEL = "work-schedule.cancel";

  /** Replacement preview (interceptor operation and fail-closed disclosure audit action). */
  public static final String REPLACEMENT_PREVIEW = "work-schedule.replacement-preview";

  /** Replacement (idempotency operation, interceptor operation and audit action). */
  public static final String REPLACE = "work-schedule.replace";

  /** Outbox event of a cancellation. */
  public static final String CANCELLED_EVENT = "people.work-schedule.cancelled.v1";

  /** Outbox event of a replacement. */
  public static final String REPLACED_EVENT = "people.work-schedule.replaced.v1";

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.schedule");
  private static final String SOURCE = "core-api/people";
  private static final String RESOURCE = "work-schedule";
  private static final Duration TRANSACTION_TIMEOUT = Duration.ofSeconds(20);
  private static final IdempotentOperation.Spec CANCEL_SPEC =
      new IdempotentOperation.Spec(CANCEL, "work_schedule", "cancel", "cancelled", 200);
  private static final IdempotentOperation.Spec REPLACE_SPEC =
      new IdempotentOperation.Spec(REPLACE, "work_schedule", "replace", "replaced", 201);

  private final IdempotentOperation operations;
  private final WorkSchedulePlanner planner;
  private final JdbcWorkScheduleRepository schedules;
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
   * @param planner proposal evaluation
   * @param schedules schedule storage
   * @param calendar the platform clock
   * @param audit audit recorder
   * @param outbox outbox writer
   * @param metrics operation metrics
   * @param transactionManager transaction manager
   * @param json JSON mapper
   */
  public WorkScheduleChangeService(
      IdempotentOperation operations,
      WorkSchedulePlanner planner,
      JdbcWorkScheduleRepository schedules,
      BusinessCalendar calendar,
      AuditRecorder audit,
      OutboxWriter outbox,
      OperationMetrics metrics,
      PlatformTransactionManager transactionManager,
      JsonMapper json) {
    this.operations = operations;
    this.planner = planner;
    this.schedules = schedules;
    this.calendar = calendar;
    this.audit = audit;
    this.outbox = outbox;
    this.metrics = metrics;
    this.transactionManager = transactionManager;
    this.json = json;
  }

  // ------------------------------------------------------------------------------------------
  // Cancellation
  // ------------------------------------------------------------------------------------------

  /**
   * Cancels an entirely future published schedule, or replays an earlier identical cancellation.
   *
   * @param caller verified tenant administrator
   * @param scheduleId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the reason and the confirmation
   * @return the receipt
   */
  public IdempotentOperation.Result<ChangeReceipt> cancel(
      PeopleCaller caller,
      String scheduleId,
      String idempotencyKey,
      CancelWorkScheduleRequest body) {
    Cancellation cancellation =
        operations.validated(
            CANCEL_SPEC,
            () ->
                WorkScheduleChangeCommand.cancellation(
                    WorkScheduleChangeCommand.scheduleId(scheduleId), idempotencyKey, body));
    return operations.execute(
        CANCEL_SPEC,
        caller.subject(),
        idempotencyKey,
        cancellation.canonical(caller.tenant()),
        ChangeReceipt.class,
        TRANSACTION_TIMEOUT,
        () -> cancelled(caller, cancellation));
  }

  private IdempotentOperation.Completed<ChangeReceipt> cancelled(
      PeopleCaller caller, Cancellation cancellation) {
    TenantId tenant = caller.tenant();
    schedules.lock(tenant);
    ScheduleView view = open(tenant, cancellation.scheduleId());
    WorkSchedule schedule = view.schedule();
    Instant now = now();
    windowOpen(tenant, schedule.id(), now);
    try {
      schedules.insertCancellation(
          tenant, schedule.id(), cancellation.reason(), now, caller.subject());
      schedules.releaseCoverage(tenant, schedule.id());
      schedules.checkComplete(tenant);
    } catch (DataIntegrityViolationException violated) {
      throw mapped(violated);
    }
    Map<String, Object> ids = ids(schedule);
    Map<String, Object> state = new TreeMap<>(ids);
    state.remove("schemaVersion");
    state.put("tenantId", tenant.toString());
    state.put("status", Lifecycle.Status.CANCELLED.name());
    state.put("reason", cancellation.reason());
    state.put("cancelledAt", now.toString());
    state.put("cancelledBy", caller.subject());
    recorded(caller, CANCEL, schedule.id(), ids, state, now);
    event(caller, CANCELLED_EVENT, schedule.id(), ids, now);
    LOG.atInfo()
        .addKeyValue("operation", CANCEL)
        .addKeyValue("slotCount", schedule.slotCount())
        .addKeyValue("assignedCount", schedule.assignedCount())
        .log("work_schedule_cancellation_recorded");
    return new IdempotentOperation.Completed<>(
        new ChangeReceipt(schedule.id(), Lifecycle.Status.CANCELLED, now, null, null),
        schedule.id(),
        Outcome.UPDATED);
  }

  // ------------------------------------------------------------------------------------------
  // Replacement preview
  // ------------------------------------------------------------------------------------------

  /**
   * Evaluates a full replacement of an entirely future published schedule against the current
   * facts, the original's own coverage aside. A fail-closed disclosure: the preview, which names
   * the assigned employees, is returned only after its audit record committed.
   *
   * @param caller verified tenant administrator
   * @param scheduleId path value
   * @param body the proposed slots and the reason
   * @return the preview, with the replacement digest
   */
  public Preview previewReplacement(
      PeopleCaller caller, String scheduleId, WorkScheduleReplacementRequest body) {
    try {
      Proposal proposal =
          WorkScheduleChangeCommand.proposal(
              WorkScheduleChangeCommand.scheduleId(scheduleId), body);
      Preview preview =
          read(
              () -> {
                TenantId tenant = caller.tenant();
                ScheduleView view =
                    schedules
                        .find(tenant, proposal.scheduleId())
                        .orElseThrow(WorkScheduleChangeService::notFound);
                published(view);
                Instant now = now();
                windowOpen(tenant, view.schedule().id(), now);
                Plan plan = evaluate(tenant, view, proposal, now);
                Preview result = Preview.of(plan, digest(view, proposal, plan));
                disclosed(caller, result);
                return result;
              });
      metrics.record(REPLACEMENT_PREVIEW, Outcome.LISTED);
      LOG.atInfo()
          .addKeyValue("operation", REPLACEMENT_PREVIEW)
          .addKeyValue("slotCount", preview.totals().slots())
          .addKeyValue("assignedCount", preview.totals().assigned())
          .addKeyValue("uncoveredCount", preview.totals().uncovered())
          .addKeyValue("publishable", preview.publishable())
          .addKeyValue("outcome", "previewed")
          .log("work_schedule_replacement_previewed");
      return preview;
    } catch (ApiException rejected) {
      Outcome outcome =
          switch (rejected.code()) {
            case WORK_SCHEDULE_NOT_FOUND -> Outcome.NOT_FOUND;
            case WORK_SCHEDULE_ALREADY_CANCELLED,
                WORK_SCHEDULE_ALREADY_REPLACED,
                WORK_SCHEDULE_CHANGE_WINDOW_CLOSED,
                WORK_SCHEDULE_OVERLAP ->
                Outcome.STATE_CONFLICT;
            default -> Outcome.VALIDATION_FAILED;
          };
      metrics.record(REPLACEMENT_PREVIEW, outcome);
      LOG.atInfo()
          .addKeyValue("operation", REPLACEMENT_PREVIEW)
          .addKeyValue("outcome", outcome.name().toLowerCase(Locale.ROOT))
          .addKeyValue("code", rejected.code().name())
          .log("work_schedule_replacement_preview_rejected");
      throw rejected;
    } catch (RuntimeException failure) {
      metrics.record(REPLACEMENT_PREVIEW, Outcome.FAILURE);
      throw failure;
    }
  }

  // ------------------------------------------------------------------------------------------
  // Replacement
  // ------------------------------------------------------------------------------------------

  /**
   * Replaces an entirely future published schedule with exactly the reviewed proposal, or replays
   * an earlier identical replacement.
   *
   * @param caller verified tenant administrator
   * @param scheduleId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the proposal, the reason, the preview's digest and the confirmation
   * @return the receipt
   */
  public IdempotentOperation.Result<ChangeReceipt> replace(
      PeopleCaller caller,
      String scheduleId,
      String idempotencyKey,
      ReplaceWorkScheduleRequest body) {
    Replacement replacement =
        operations.validated(
            REPLACE_SPEC,
            () ->
                WorkScheduleChangeCommand.replacement(
                    WorkScheduleChangeCommand.scheduleId(scheduleId), idempotencyKey, body));
    return operations.execute(
        REPLACE_SPEC,
        caller.subject(),
        idempotencyKey,
        replacement.canonical(caller.tenant()),
        ChangeReceipt.class,
        TRANSACTION_TIMEOUT,
        () -> replaced(caller, replacement));
  }

  private IdempotentOperation.Completed<ChangeReceipt> replaced(
      PeopleCaller caller, Replacement replacement) {
    TenantId tenant = caller.tenant();
    Proposal proposal = replacement.proposal();
    schedules.lock(tenant);
    ScheduleView view = open(tenant, proposal.scheduleId());
    WorkSchedule original = view.schedule();
    Instant now = now();
    windowOpen(tenant, original.id(), now);
    Set<UUID> employees = new LinkedHashSet<>();
    for (SlotDraft slot : proposal.slots()) {
      if (slot.employeeId() != null) {
        employees.add(slot.employeeId());
      }
    }
    schedules.shareEmployments(
        tenant, employees, original.firstDate(), original.lastDate().plusDays(1));
    Plan plan = evaluate(tenant, view, proposal, now);
    for (PlannedSlot slot : plan.slots()) {
      if (slot.issue() != null) {
        throw new ApiException(
            ErrorCode.valueOf(slot.issue().code()),
            Map.of(
                "position",
                Integer.toString(slot.draft().position()),
                "reason",
                slot.issue().reason()));
      }
    }
    if (!digest(view, proposal, plan).equals(replacement.previewDigest())) {
      throw new ApiException(ErrorCode.WORK_SCHEDULE_PREVIEW_STALE, Map.of());
    }
    WorkSchedule successor =
        new WorkSchedule(
            UUID.randomUUID(),
            original.siteId(),
            original.firstDate(),
            original.lastDate(),
            plan.zone().getId(),
            plan.slots().size(),
            plan.assigned(),
            now);
    List<UUID> slotIds = new ArrayList<>(plan.slots().size());
    for (int i = 0; i < plan.slots().size(); i++) {
      slotIds.add(UUID.randomUUID());
    }
    try {
      schedules.insertEvidence(tenant, successor, plan.slots(), slotIds, caller.subject());
      schedules.insertReplacement(
          tenant, original.id(), successor.id(), proposal.reason(), now, caller.subject());
      schedules.releaseCoverage(tenant, original.id());
      schedules.insertCoverage(tenant, successor, plan.slots(), slotIds);
      schedules.checkComplete(tenant);
    } catch (DataIntegrityViolationException violated) {
      throw mapped(violated);
    }
    Totals totals =
        new Totals(successor.slotCount(), successor.assignedCount(), successor.uncoveredCount());
    Map<String, Object> ids = ids(original);
    ids.put("replacementScheduleId", successor.id().toString());
    ids.put("replacementSlotCount", totals.slots());
    ids.put("replacementAssignedCount", totals.assigned());
    ids.put("replacementUncoveredCount", totals.uncovered());
    Map<String, Object> state = new TreeMap<>(ids);
    state.remove("schemaVersion");
    state.put("tenantId", tenant.toString());
    state.put("status", Lifecycle.Status.REPLACED.name());
    state.put("reason", proposal.reason());
    state.put("replacedAt", now.toString());
    state.put("replacedBy", caller.subject());
    state.put("siteTimezone", successor.siteTimezone());
    List<Map<String, Object>> slots = new ArrayList<>(plan.slots().size());
    for (int i = 0; i < plan.slots().size(); i++) {
      PlannedSlot slot = plan.slots().get(i);
      Map<String, Object> item = new TreeMap<>();
      item.put("id", slotIds.get(i).toString());
      item.put("position", slot.draft().position());
      item.put("start", WorkScheduleCommand.wire(slot.draft().start()));
      item.put("end", WorkScheduleCommand.wire(slot.draft().end()));
      item.put("startAt", String.valueOf(slot.startAt()));
      item.put("endAt", String.valueOf(slot.endAt()));
      item.put("labelEn", slot.draft().labelEn());
      item.put("labelFr", slot.draft().labelFr());
      item.put(
          "employeeId",
          slot.draft().employeeId() == null ? "" : slot.draft().employeeId().toString());
      slots.add(item);
    }
    state.put("replacementSlots", slots);
    recorded(caller, REPLACE, original.id(), ids, state, now);
    event(caller, REPLACED_EVENT, original.id(), ids, now);
    LOG.atInfo()
        .addKeyValue("operation", REPLACE)
        .addKeyValue("slotCount", totals.slots())
        .addKeyValue("assignedCount", totals.assigned())
        .addKeyValue("uncoveredCount", totals.uncovered())
        .log("work_schedule_replacement_recorded");
    return new IdempotentOperation.Completed<>(
        new ChangeReceipt(original.id(), Lifecycle.Status.REPLACED, now, successor.id(), totals),
        original.id(),
        Outcome.CREATED);
  }

  // ------------------------------------------------------------------------------------------
  // Shared
  // ------------------------------------------------------------------------------------------

  /** The original, locked, and still published (lock order step 2). */
  private ScheduleView open(TenantId tenant, UUID id) {
    ScheduleView view =
        schedules.lockSchedule(tenant, id).orElseThrow(WorkScheduleChangeService::notFound);
    published(view);
    return view;
  }

  private static void published(ScheduleView view) {
    switch (view.lifecycle().status()) {
      case CANCELLED -> throw new ApiException(ErrorCode.WORK_SCHEDULE_ALREADY_CANCELLED, Map.of());
      case REPLACED -> throw new ApiException(ErrorCode.WORK_SCHEDULE_ALREADY_REPLACED, Map.of());
      case PUBLISHED -> {
        // operational
      }
    }
  }

  /** Every slot of the schedule starts strictly after the instant (D50B-4). */
  private void windowOpen(TenantId tenant, UUID id, Instant now) {
    if (!schedules.earliestStart(tenant, id).isAfter(now)) {
      throw new ApiException(ErrorCode.WORK_SCHEDULE_CHANGE_WINDOW_CLOSED, Map.of());
    }
  }

  private Instant now() {
    return calendar.now().truncatedTo(ChronoUnit.MICROS);
  }

  private Plan evaluate(TenantId tenant, ScheduleView view, Proposal proposal, Instant now) {
    WorkSchedule original = view.schedule();
    return planner.plan(
        tenant,
        proposal.command(original.siteId(), original.firstDate(), original.lastDate()),
        new Replacing(original.id(), now));
  }

  /**
   * Digest v1 of a replacement preview: the SHA-256 of
   *
   * <pre>
   * DIVALHR-WORK-SCHEDULE-REPLACEMENT-PREVIEW
   * version=1
   * original=&lt;id&gt;|&lt;state&gt;|&lt;published at&gt;|&lt;slots&gt;|&lt;assigned&gt;|&lt;site time zone&gt;
   * reason=&lt;reason&gt;
   * </pre>
   *
   * followed by {@code plan=<the proposal's preview digest v1>}, which binds the site, the period,
   * the site's business date and time zone, every normalized slot with its issue and every
   * eligibility and conflict fact. The reason contains no line break by grammar. The digest is only
   * ever compared.
   */
  private static String digest(ScheduleView view, Proposal proposal, Plan plan) {
    WorkSchedule original = view.schedule();
    String header =
        "DIVALHR-WORK-SCHEDULE-REPLACEMENT-PREVIEW\nversion=1\noriginal="
            + original.id()
            + '|'
            + view.lifecycle().status()
            + '|'
            + original.publishedAt()
            + '|'
            + original.slotCount()
            + '|'
            + original.assignedCount()
            + '|'
            + original.siteTimezone()
            + "\nreason="
            + proposal.reason()
            + '\n';
    return Fingerprints.sha256(header + "plan=" + plan.digest() + '\n');
  }

  private static ApiException mapped(DataIntegrityViolationException violated) {
    // Never expected under the locks; the stable conflict, no database text.
    String constraint = ScheduleConstraintViolations.constraint(violated).orElse("");
    return switch (constraint) {
      case ScheduleConstraintViolations.SITE_OVERLAP ->
          new ApiException(ErrorCode.WORK_SCHEDULE_OVERLAP, Map.of());
      case ScheduleConstraintViolations.EMPLOYEE_OVERLAP ->
          new ApiException(ErrorCode.WORK_SCHEDULE_EMPLOYEE_CONFLICT, Map.of());
      case ScheduleConstraintViolations.CHANGE_WINDOW ->
          new ApiException(ErrorCode.WORK_SCHEDULE_CHANGE_WINDOW_CLOSED, Map.of());
      case ScheduleConstraintViolations.SINGLE_TERMINAL ->
          new ApiException(ErrorCode.WORK_SCHEDULE_PREVIEW_STALE, Map.of());
      default -> throw violated;
    };
  }

  private static Map<String, Object> ids(WorkSchedule schedule) {
    Map<String, Object> ids = new LinkedHashMap<>();
    ids.put("schemaVersion", WorkScheduleService.SCHEMA_VERSION);
    ids.put("scheduleId", schedule.id().toString());
    ids.put("siteId", schedule.siteId().toString());
    ids.put("firstDate", schedule.firstDate().toString());
    ids.put("lastDate", schedule.lastDate().toString());
    ids.put("slotCount", schedule.slotCount());
    ids.put("assignedCount", schedule.assignedCount());
    ids.put("uncoveredCount", schedule.uncoveredCount());
    return ids;
  }

  /**
   * The audit record: IDs, site, dates and counts; the whole state, reason included, as a digest.
   */
  private void recorded(
      PeopleCaller caller,
      String action,
      UUID scheduleId,
      Map<String, Object> ids,
      Map<String, Object> state,
      Instant now) {
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            now,
            caller.subject(),
            action,
            RESOURCE,
            scheduleId,
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            new LinkedHashMap<>(ids),
            Fingerprints.sha256(json.writeValueAsString(state))));
  }

  private void event(
      PeopleCaller caller, String type, UUID scheduleId, Map<String, Object> ids, Instant now) {
    Map<String, Object> data = new LinkedHashMap<>(ids);
    data.remove("schemaVersion");
    outbox.append(
        new EventEnvelope(
            UUID.randomUUID().toString(),
            type,
            WorkScheduleService.SCHEMA_VERSION,
            caller.tenant().toString(),
            SOURCE,
            scheduleId.toString(),
            now.toString(),
            caller.correlationId(),
            null,
            data));
  }

  /** The disclosure audit of a replacement preview: counts only; identifiers in the digest. */
  private void disclosed(PeopleCaller caller, Preview preview) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", WorkScheduleService.SCHEMA_VERSION);
    metadata.put("view", "work-schedule-replacement-preview");
    metadata.put("page", "first");
    metadata.put("resultCount", preview.slots().size());
    StringBuilder text =
        new StringBuilder(
                "DIVALHR-WORK-SCHEDULE-REPLACEMENT-PREVIEW-DISCLOSURE\nversion=1\ndigest=")
            .append(preview.previewDigest())
            .append('\n');
    preview
        .slots()
        .forEach(
            slot ->
                text.append("p=")
                    .append(slot.position())
                    .append(";employee=")
                    .append(slot.employee() == null ? "-" : slot.employee().id())
                    .append('\n'));
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            calendar.now().truncatedTo(ChronoUnit.MICROS),
            caller.subject(),
            REPLACEMENT_PREVIEW,
            "organization",
            caller.tenant().value(),
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(text.toString())));
  }

  private static ApiException notFound() {
    return new ApiException(ErrorCode.WORK_SCHEDULE_NOT_FOUND, Map.of());
  }

  /** A bounded read transaction; the result is returned only after it committed. */
  private <T> T read(Supplier<T> work) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setTimeout((int) TRANSACTION_TIMEOUT.toSeconds());
    T result = template.execute(status -> work.get());
    if (result == null) {
      throw new IllegalStateException("read transaction returned nothing");
    }
    return result;
  }
}
