package com.divalhr.core.people.schedule.application;

import com.divalhr.core.people.application.BusinessCalendar;
import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.schedule.api.PublishWorkScheduleRequest;
import com.divalhr.core.people.schedule.api.WorkScheduleRequest;
import com.divalhr.core.people.schedule.api.WorkScheduleResponses.Detail;
import com.divalhr.core.people.schedule.api.WorkScheduleResponses.Page;
import com.divalhr.core.people.schedule.api.WorkScheduleResponses.Preview;
import com.divalhr.core.people.schedule.api.WorkScheduleResponses.Receipt;
import com.divalhr.core.people.schedule.api.WorkScheduleResponses.Summary;
import com.divalhr.core.people.schedule.api.WorkScheduleResponses.Totals;
import com.divalhr.core.people.schedule.application.WorkSchedulePlanner.Plan;
import com.divalhr.core.people.schedule.domain.PlannedSlot;
import com.divalhr.core.people.schedule.domain.PublishedSlot;
import com.divalhr.core.people.schedule.domain.SlotDraft;
import com.divalhr.core.people.schedule.domain.WorkSchedule;
import com.divalhr.core.people.schedule.internal.JdbcWorkScheduleRepository;
import com.divalhr.core.people.schedule.internal.ScheduleConstraintViolations;
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
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.UnitView;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * Site work schedules for tenant administrators (MVP-050A, Issue #99; ADR 0014): preview a proposed
 * schedule, publish exactly what was previewed, list the published schedules and read one with its
 * slots. There is no draft, amendment, cancellation or deletion: every stored row is published
 * evidence.
 *
 * <p>A preview evaluates the proposal against the current facts ({@link WorkSchedulePlanner}) and
 * returns its normalized slots, issues, totals and digest; it writes only its disclosure audit
 * record. A publication is one bounded transaction: idempotency (step 0), the tenant's scheduling
 * lock (step 1), every employment of the assigned employees overlapping the dates {@code FOR SHARE}
 * in UUID order (step 2), then the same evaluation. A slot issue refuses with its stable code; a
 * plan whose digest differs from the reviewed one is {@code 409 WORK_SCHEDULE_PREVIEW_STALE}, never
 * recomputed and published. Otherwise the schedule and its slots are inserted, the deferred
 * completeness checks run, and the {@value #PUBLISH} audit record and the {@value #PUBLISHED_EVENT}
 * outbox event are written, all committed together with the idempotency completion.
 *
 * <p>Labels, employee names and numbers never enter audit metadata, events, logs or errors; the
 * publisher subject is stored as evidence and never returned.
 */
@Service
public class WorkScheduleService {

  /** Preview (interceptor operation and disclosure audit action). */
  public static final String PREVIEW = "work-schedule.preview";

  /** Publication (idempotency operation, interceptor operation and audit action). */
  public static final String PUBLISH = "work-schedule.publish";

  /** List (interceptor operation). */
  public static final String LIST = "work-schedule.list";

  /** One schedule (interceptor operation). */
  public static final String GET = "work-schedule.get";

  /** The administrators' fail-closed disclosure audit action. */
  public static final String READ = "work-schedule.read";

  /** Outbox event of a publication. */
  public static final String PUBLISHED_EVENT = "people.work-schedule.published.v1";

  /** Per-subject administrators' read bucket (60 per minute). */
  public static final String SUBJECT_READ_BUCKET = "work-schedule-read";

  /** Per-subject administrators' preview and publication bucket (20 per minute). */
  public static final String SUBJECT_WRITE_BUCKET = "work-schedule-write";

  /** Per-tenant publication bucket (100 per 10 minutes). */
  public static final String TENANT_WRITE_BUCKET = "work-schedule-write";

  /** Event and audit schema version. */
  static final int SCHEMA_VERSION = 1;

  static final int DEFAULT_LIMIT = 25;
  static final int MAX_LIMIT = 50;

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.schedule");
  private static final String SOURCE = "core-api/people";
  private static final String RESOURCE = "work-schedule";
  private static final String POSITION = "WS";
  private static final Pattern LIMIT = Pattern.compile("^[0-9]{1,3}$");
  private static final Pattern UUID_SHAPE =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  private static final Duration TRANSACTION_TIMEOUT = Duration.ofSeconds(20);
  private static final IdempotentOperation.Spec PUBLISH_SPEC =
      new IdempotentOperation.Spec(PUBLISH, "work_schedule", "publish", "published", 201);

  private final IdempotentOperation operations;
  private final WorkSchedulePlanner planner;
  private final JdbcWorkScheduleRepository schedules;
  private final OrganizationPlacementDirectory directory;
  private final BusinessCalendar calendar;
  private final CursorCodec cursors;
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
   * @param directory the tenant module's unit port (site codes and names)
   * @param calendar the organization's business date and the platform clock
   * @param cursors signed cursors
   * @param audit audit recorder
   * @param outbox outbox writer
   * @param metrics operation metrics
   * @param transactionManager transaction manager
   * @param json JSON mapper
   */
  public WorkScheduleService(
      IdempotentOperation operations,
      WorkSchedulePlanner planner,
      JdbcWorkScheduleRepository schedules,
      OrganizationPlacementDirectory directory,
      BusinessCalendar calendar,
      CursorCodec cursors,
      AuditRecorder audit,
      OutboxWriter outbox,
      OperationMetrics metrics,
      PlatformTransactionManager transactionManager,
      JsonMapper json) {
    this.operations = operations;
    this.planner = planner;
    this.schedules = schedules;
    this.directory = directory;
    this.calendar = calendar;
    this.cursors = cursors;
    this.audit = audit;
    this.outbox = outbox;
    this.metrics = metrics;
    this.transactionManager = transactionManager;
    this.json = json;
  }

  // ------------------------------------------------------------------------------------------
  // Preview
  // ------------------------------------------------------------------------------------------

  /**
   * Evaluates a proposed schedule against the current facts. A fail-closed disclosure: the preview,
   * which names the assigned employees, is returned only after its audit record committed.
   *
   * @param caller verified tenant administrator
   * @param body the proposal
   * @return the preview
   */
  public Preview preview(PeopleCaller caller, WorkScheduleRequest body) {
    return observed(
        PREVIEW,
        "work_schedule_preview_rejected",
        () -> {
          WorkScheduleCommand command = WorkScheduleCommand.preview(body);
          Preview preview =
              read(
                  () -> {
                    Plan plan = planner.plan(caller.tenant(), command);
                    Preview result = Preview.of(plan);
                    disclosed(
                        caller,
                        PREVIEW,
                        "work-schedule-preview",
                        "first",
                        result.slots().size(),
                        previewText(result));
                    return result;
                  });
          LOG.atInfo()
              .addKeyValue("operation", PREVIEW)
              .addKeyValue("slotCount", preview.totals().slots())
              .addKeyValue("assignedCount", preview.totals().assigned())
              .addKeyValue("uncoveredCount", preview.totals().uncovered())
              .addKeyValue("publishable", preview.publishable())
              .addKeyValue("outcome", "previewed")
              .log("work_schedule_previewed");
          return preview;
        });
  }

  private static String previewText(Preview preview) {
    StringBuilder text =
        new StringBuilder("DIVALHR-WORK-SCHEDULE-PREVIEW-DISCLOSURE\nversion=1\ndigest=")
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
    return text.toString();
  }

  // ------------------------------------------------------------------------------------------
  // Publish
  // ------------------------------------------------------------------------------------------

  /**
   * Publishes a previewed schedule, or replays an earlier identical publication.
   *
   * @param caller verified tenant administrator
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the proposal, its preview digest and the confirmation
   * @return the receipt
   */
  public IdempotentOperation.Result<Receipt> publish(
      PeopleCaller caller, String idempotencyKey, PublishWorkScheduleRequest body) {
    WorkScheduleCommand.Publication publication =
        operations.validated(
            PUBLISH_SPEC, () -> WorkScheduleCommand.publication(idempotencyKey, body));
    return operations.execute(
        PUBLISH_SPEC,
        caller.subject(),
        idempotencyKey,
        publication.canonical(caller.tenant()),
        Receipt.class,
        TRANSACTION_TIMEOUT,
        () -> published(caller, publication));
  }

  private IdempotentOperation.Completed<Receipt> published(
      PeopleCaller caller, WorkScheduleCommand.Publication publication) {
    TenantId tenant = caller.tenant();
    WorkScheduleCommand command = publication.command();
    schedules.lock(tenant);
    Set<UUID> employees = new LinkedHashSet<>();
    for (SlotDraft slot : command.slots()) {
      if (slot.employeeId() != null) {
        employees.add(slot.employeeId());
      }
    }
    if (!command.lastDate().isBefore(command.firstDate())) {
      schedules.shareEmployments(
          tenant, employees, command.firstDate(), command.lastDate().plusDays(1));
    }
    Plan plan = planner.plan(tenant, command);
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
    if (!plan.digest().equals(publication.previewDigest())) {
      throw new ApiException(ErrorCode.WORK_SCHEDULE_PREVIEW_STALE, Map.of());
    }
    Instant now = calendar.now().truncatedTo(ChronoUnit.MICROS);
    int assigned = plan.assigned();
    WorkSchedule schedule =
        new WorkSchedule(
            UUID.randomUUID(),
            plan.site().id(),
            plan.firstDate(),
            plan.lastDate(),
            plan.zone().getId(),
            plan.slots().size(),
            assigned,
            now);
    List<UUID> slotIds = new ArrayList<>(plan.slots().size());
    for (int i = 0; i < plan.slots().size(); i++) {
      slotIds.add(UUID.randomUUID());
    }
    try {
      schedules.insert(tenant, schedule, plan.slots(), slotIds, caller.subject());
      schedules.checkComplete(tenant);
    } catch (DataIntegrityViolationException violated) {
      // Never expected under the lock; the stable conflict, no database text.
      String constraint = ScheduleConstraintViolations.constraint(violated).orElse("");
      if (ScheduleConstraintViolations.SITE_OVERLAP.equals(constraint)) {
        throw new ApiException(ErrorCode.WORK_SCHEDULE_OVERLAP, Map.of());
      }
      if (ScheduleConstraintViolations.EMPLOYEE_OVERLAP.equals(constraint)) {
        throw new ApiException(ErrorCode.WORK_SCHEDULE_EMPLOYEE_CONFLICT, Map.of());
      }
      throw violated;
    }
    Totals totals = new Totals(schedule.slotCount(), assigned, schedule.uncoveredCount());
    Map<String, Object> ids = new LinkedHashMap<>();
    ids.put("schemaVersion", SCHEMA_VERSION);
    ids.put("scheduleId", schedule.id().toString());
    ids.put("siteId", schedule.siteId().toString());
    ids.put("firstDate", schedule.firstDate().toString());
    ids.put("lastDate", schedule.lastDate().toString());
    ids.put("slotCount", totals.slots());
    ids.put("assignedCount", totals.assigned());
    ids.put("uncoveredCount", totals.uncovered());
    recorded(caller, schedule, plan, slotIds, ids, now);
    publishedEvent(caller, schedule, ids, now);
    LOG.atInfo()
        .addKeyValue("operation", PUBLISH)
        .addKeyValue("slotCount", totals.slots())
        .addKeyValue("assignedCount", totals.assigned())
        .addKeyValue("uncoveredCount", totals.uncovered())
        .log("work_schedule_publication_recorded");
    return new IdempotentOperation.Completed<>(
        new Receipt(schedule.id(), now, totals), schedule.id(), Outcome.CREATED);
  }

  /** The audit record: IDs, site, dates and counts; the whole published state only as a digest. */
  private void recorded(
      PeopleCaller caller,
      WorkSchedule schedule,
      Plan plan,
      List<UUID> slotIds,
      Map<String, Object> ids,
      Instant now) {
    Map<String, Object> afterState = new TreeMap<>(ids);
    afterState.remove("schemaVersion");
    afterState.put("tenantId", caller.tenant().toString());
    afterState.put("timezone", schedule.timezone());
    afterState.put("publishedAt", now.toString());
    afterState.put("publishedBy", caller.subject());
    List<Map<String, Object>> slots = new ArrayList<>(plan.slots().size());
    for (int i = 0; i < plan.slots().size(); i++) {
      PlannedSlot slot = plan.slots().get(i);
      Map<String, Object> state = new TreeMap<>();
      state.put("id", slotIds.get(i).toString());
      state.put("position", slot.draft().position());
      state.put("start", WorkScheduleCommand.wire(slot.draft().start()));
      state.put("end", WorkScheduleCommand.wire(slot.draft().end()));
      state.put("startAt", String.valueOf(slot.startAt()));
      state.put("endAt", String.valueOf(slot.endAt()));
      state.put("labelEn", slot.draft().labelEn());
      state.put("labelFr", slot.draft().labelFr());
      state.put(
          "employeeId",
          slot.draft().employeeId() == null ? "" : slot.draft().employeeId().toString());
      slots.add(state);
    }
    afterState.put("slots", slots);
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            now,
            caller.subject(),
            PUBLISH,
            RESOURCE,
            schedule.id(),
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            new LinkedHashMap<>(ids),
            Fingerprints.sha256(json.writeValueAsString(afterState))));
  }

  private void publishedEvent(
      PeopleCaller caller, WorkSchedule schedule, Map<String, Object> ids, Instant now) {
    Map<String, Object> data = new LinkedHashMap<>(ids);
    data.remove("schemaVersion");
    outbox.append(
        new EventEnvelope(
            UUID.randomUUID().toString(),
            PUBLISHED_EVENT,
            SCHEMA_VERSION,
            caller.tenant().toString(),
            SOURCE,
            schedule.id().toString(),
            now.toString(),
            caller.correlationId(),
            null,
            data));
  }

  // ------------------------------------------------------------------------------------------
  // List
  // ------------------------------------------------------------------------------------------

  /**
   * One page of the tenant's published schedules, latest period first, optionally of one site and
   * overlapping the dates. A fail-closed disclosure.
   *
   * @param caller verified tenant administrator
   * @param siteId site filter or {@code null}
   * @param from date filter or {@code null}
   * @param to date filter or {@code null}
   * @param cursor opaque cursor or {@code null}
   * @param limit page size (1 to 50, default 25)
   * @return the page
   */
  public Page list(
      PeopleCaller caller, String siteId, String from, String to, String cursor, String limit) {
    return observed(
        LIST,
        "work_schedule_list_rejected",
        () -> {
          FieldErrors errors = new FieldErrors();
          UUID site = WorkScheduleCommand.optionalUuid(errors, "siteId", siteId);
          LocalDate fromDate = WorkScheduleCommand.optionalDate(errors, "from", from);
          LocalDate toDate = WorkScheduleCommand.optionalDate(errors, "to", to);
          if (fromDate != null && toDate != null && toDate.isBefore(fromDate)) {
            errors.add("to", Constraint.RANGE);
          }
          int size = limit(limit, errors);
          errors.throwIfAny();
          CursorScope scope = scope(caller, site, fromDate, toDate, size);
          Page page =
              read(
                  () -> {
                    TenantId tenant = caller.tenant();
                    LocalDate afterDate = null;
                    UUID afterId = null;
                    if (cursor != null) {
                      KeysetPosition position = cursors.decode(cursor, scope);
                      if (!POSITION.equals(position.code())) {
                        throw invalidCursor();
                      }
                      afterDate =
                          schedules
                              .find(tenant, position.id())
                              .map(WorkSchedule::firstDate)
                              .orElseThrow(WorkScheduleService::invalidCursor);
                      afterId = position.id();
                    }
                    List<WorkSchedule> rows =
                        schedules.page(
                            tenant, site, fromDate, toDate, afterDate, afterId, size + 1);
                    List<WorkSchedule> shown = rows.subList(0, Math.min(rows.size(), size));
                    String next =
                        rows.size() > size
                            ? cursors.encode(
                                scope,
                                new KeysetPosition(POSITION, shown.get(shown.size() - 1).id()))
                            : null;
                    Map<UUID, UnitView> sites = sites(tenant, shown);
                    Page listed =
                        new Page(
                            shown.stream().map(s -> Summary.of(s, sites.get(s.siteId()))).toList(),
                            next,
                            calendar.today(tenant));
                    StringBuilder text =
                        new StringBuilder("DIVALHR-WORK-SCHEDULE-LIST-DISCLOSURE\nversion=1\n");
                    shown.forEach(s -> text.append("id=").append(s.id()).append('\n'));
                    disclosed(
                        caller,
                        READ,
                        "work-schedules",
                        cursor == null ? "first" : "next",
                        shown.size(),
                        text.toString());
                    return listed;
                  });
          LOG.atInfo()
              .addKeyValue("operation", LIST)
              .addKeyValue("page", cursor == null ? "first" : "next")
              .addKeyValue("resultCount", page.items().size())
              .addKeyValue("outcome", "listed")
              .log("work_schedule_listed");
          return page;
        });
  }

  /** Bound to the operation, tenant, subject (a digest), filters and page size. */
  private static CursorScope scope(
      PeopleCaller caller, UUID site, LocalDate from, LocalDate to, int size) {
    Map<String, String> filters = new HashMap<>();
    filters.put(
        "subject", Fingerprints.sha256("DIVALHR-WORK-SCHEDULE\nsubject=" + caller.subject()));
    filters.put("siteId", site == null ? "-" : site.toString());
    filters.put("from", from == null ? "-" : from.toString());
    filters.put("to", to == null ? "-" : to.toString());
    filters.put("limit", Integer.toString(size));
    return new CursorScope(LIST, caller.tenant(), filters);
  }

  // ------------------------------------------------------------------------------------------
  // One schedule
  // ------------------------------------------------------------------------------------------

  /**
   * One published schedule with every slot. Unknown and foreign-tenant schedules are the same
   * {@code 404 WORK_SCHEDULE_NOT_FOUND}. A fail-closed disclosure.
   *
   * @param caller verified tenant administrator
   * @param scheduleId path value
   * @return the schedule
   */
  public Detail get(PeopleCaller caller, String scheduleId) {
    return observed(
        GET,
        "work_schedule_get_rejected",
        () -> {
          UUID id = scheduleId(scheduleId);
          Detail detail =
              read(
                  () -> {
                    TenantId tenant = caller.tenant();
                    WorkSchedule schedule =
                        schedules.find(tenant, id).orElseThrow(WorkScheduleService::notFound);
                    List<PublishedSlot> slots = schedules.slots(tenant, id);
                    Detail found =
                        Detail.of(
                            schedule,
                            sites(tenant, List.of(schedule)).get(schedule.siteId()),
                            slots);
                    StringBuilder text =
                        new StringBuilder("DIVALHR-WORK-SCHEDULE-DISCLOSURE\nversion=1\nid=")
                            .append(schedule.id())
                            .append('\n');
                    slots.forEach(
                        slot ->
                            text.append("slot=")
                                .append(slot.id())
                                .append(";employee=")
                                .append(slot.employeeId() == null ? "-" : slot.employeeId())
                                .append('\n'));
                    disclosed(
                        caller, READ, "work-schedule", "first", slots.size(), text.toString());
                    return found;
                  });
          LOG.atInfo()
              .addKeyValue("operation", GET)
              .addKeyValue("resultCount", detail.slots().size())
              .addKeyValue("outcome", "listed")
              .log("work_schedule_read");
          return detail;
        });
  }

  private static UUID scheduleId(String raw) {
    if (raw == null || !UUID_SHAPE.matcher(raw).matches()) {
      throw notFound();
    }
    return UUID.fromString(raw.toLowerCase(Locale.ROOT));
  }

  // ------------------------------------------------------------------------------------------
  // Shared
  // ------------------------------------------------------------------------------------------

  private Map<UUID, UnitView> sites(TenantId tenant, List<WorkSchedule> rows) {
    Set<UUID> ids = new LinkedHashSet<>();
    rows.forEach(row -> ids.add(row.siteId()));
    return ids.isEmpty() ? Map.of() : directory.resolveIds(tenant, ids);
  }

  /** The disclosure audit: the view, page and count; identifiers only in the digest. */
  private void disclosed(
      PeopleCaller caller, String action, String view, String page, int count, String text) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", SCHEMA_VERSION);
    metadata.put("view", view);
    metadata.put("page", page);
    metadata.put("resultCount", count);
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            calendar.now().truncatedTo(ChronoUnit.MICROS),
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

  /** Records an administrators' read or preview outcome; rejections log only their code. */
  private <T> T observed(String operation, String rejectedEvent, Supplier<T> work) {
    try {
      T result = work.get();
      metrics.record(operation, Outcome.LISTED);
      return result;
    } catch (ApiException rejected) {
      Outcome outcome =
          rejected.code() == ErrorCode.WORK_SCHEDULE_NOT_FOUND
              ? Outcome.NOT_FOUND
              : Outcome.VALIDATION_FAILED;
      metrics.record(operation, outcome);
      LOG.atInfo()
          .addKeyValue("operation", operation)
          .addKeyValue("outcome", outcome.name().toLowerCase(Locale.ROOT))
          .addKeyValue("code", rejected.code().name())
          .log(rejectedEvent);
      throw rejected;
    } catch (RuntimeException failure) {
      metrics.record(operation, Outcome.FAILURE);
      throw failure;
    }
  }

  static int limit(String raw, FieldErrors errors) {
    if (raw == null || raw.isEmpty()) {
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

  static ApiException invalidCursor() {
    return new ApiException(ErrorCode.CURSOR_INVALID, Map.of());
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
