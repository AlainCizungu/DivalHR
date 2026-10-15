package com.divalhr.core.people.schedule.application;

import com.divalhr.core.people.application.BusinessCalendar;
import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.schedule.api.CreateTimesheetExportRequest;
import com.divalhr.core.people.schedule.api.TimesheetExportPreviewRequest;
import com.divalhr.core.people.schedule.api.TimesheetExportResponses.Counts;
import com.divalhr.core.people.schedule.api.TimesheetExportResponses.Exclusions;
import com.divalhr.core.people.schedule.api.TimesheetExportResponses.Page;
import com.divalhr.core.people.schedule.api.TimesheetExportResponses.Preview;
import com.divalhr.core.people.schedule.api.TimesheetExportResponses.Result;
import com.divalhr.core.people.schedule.api.TimesheetExportResponses.ScopeView;
import com.divalhr.core.people.schedule.api.TimesheetExportResponses.Summary;
import com.divalhr.core.people.schedule.application.TimesheetExportCommand.Create;
import com.divalhr.core.people.schedule.application.TimesheetExportCommand.Filter;
import com.divalhr.core.people.schedule.application.TimesheetExportCommand.Target;
import com.divalhr.core.people.schedule.domain.TimesheetExport;
import com.divalhr.core.people.schedule.domain.TimesheetExport.Header;
import com.divalhr.core.people.schedule.domain.TimesheetExport.Line;
import com.divalhr.core.people.schedule.domain.TimesheetExport.Scope;
import com.divalhr.core.people.schedule.domain.TimesheetExport.Totals;
import com.divalhr.core.people.schedule.internal.JdbcTimesheetExportRepository;
import com.divalhr.core.people.schedule.internal.JdbcTimesheetRepository;
import com.divalhr.core.people.schedule.internal.JdbcTimesheetRepository.Generation;
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
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.UnitKind;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.UnitView;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * Approved-time exports (MVP-070E, Issue #134, T70E-1 to T70E-10; ADR 0021). Authorization (role
 * {@code tenant-admin} with privileged MFA) has run before this; tenant, actor and scope never come
 * from the request.
 *
 * <p>Eligibility (T70E-1): a timesheet of the scope's generated week whose current state is
 * APPROVED and whose current action is that APPROVED action, at its revision and cycle; its READY
 * rows are exported. DRAFT (including returned and refreshed-after-reopening), SUBMITTED and
 * REOPENED timesheets, and every superseded approval, are excluded.
 *
 * <p>A preview evaluates the scope and returns the counts, the exclusions by state, the generation
 * version, a preview digest and its expiry. The digest binds the tenant, the eligible evidence and
 * the expiry, which is the end of the next 15-minute window (15 to 30 minutes after the preview),
 * so the creation needs nothing but the digest: it is valid while the evidence is identical and the
 * window has not passed.
 *
 * <p>A creation is one bounded transaction: idempotency, the tenant's scheduling lock (every
 * timesheet command takes it first, so no approval, reopening, refresh or correction can
 * interleave), eligibility evaluated again, the digest compared, then the immutable header and rows
 * (checked by V35's binding triggers and deferred exactness), the audit record ({@value #CREATE})
 * and the outbox event ({@value #CREATED_EVENT}). Identical evidence returns the existing export
 * (outcome EXISTING); changed evidence permits a new export.
 *
 * <p>Downloads render the CSV or the manifest from the stored export; the CSV's SHA-256 must equal
 * the stored digest, and the download audit ({@value #DOWNLOAD}) commits before any byte is
 * returned (fail closed). Names, employee numbers, labels, attendance instants, subjects and
 * idempotency keys never enter audit metadata, events, logs, metrics or errors.
 */
@Service
public class TimesheetExportService {

  /** Preview (interceptor operation and disclosure audit action). */
  public static final String PREVIEW = "timesheet-export.preview";

  /** Creation (idempotency operation, interceptor operation and audit action). */
  public static final String CREATE = "timesheet-export.create";

  /** History (interceptor operation and disclosure audit action). */
  public static final String LIST = "timesheet-export.list";

  /** One export (interceptor operation and disclosure audit action). */
  public static final String READ = "timesheet-export.read";

  /** CSV and manifest downloads (interceptor operation and audit action). */
  public static final String DOWNLOAD = "timesheet-export.download";

  /** Outbox event of a creation. */
  public static final String CREATED_EVENT = "people.timesheet-export.created.v1";

  /** The preview window: a preview expires at the end of the next window. */
  static final Duration WINDOW = Duration.ofMinutes(15);

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.timesheet-export");
  private static final String SOURCE = "core-api/people";
  private static final String RESOURCE = "timesheet-export";
  private static final Duration TRANSACTION_TIMEOUT = Duration.ofSeconds(30);
  private static final IdempotentOperation.Spec CREATE_SPEC =
      new IdempotentOperation.Spec(CREATE, "timesheet_export", "create", "created", 200);
  private static final Pattern LIMIT = Pattern.compile("^[0-9]{1,3}$");
  private static final int DEFAULT_LIMIT = 20;
  private static final int MAX_LIMIT = 100;

  private final IdempotentOperation operations;
  private final JdbcWorkScheduleRepository schedules;
  private final JdbcTimesheetRepository timesheets;
  private final JdbcTimesheetExportRepository exports;
  private final OrganizationPlacementDirectory directory;
  private final CursorCodec cursors;
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
   * @param exports export storage
   * @param directory the tenant module's unit port (legal entity, site)
   * @param cursors signed cursors
   * @param calendar the platform clock
   * @param audit audit recorder
   * @param outbox outbox writer
   * @param metrics operation metrics
   * @param transactionManager transaction manager
   * @param json JSON mapper
   */
  public TimesheetExportService(
      IdempotentOperation operations,
      JdbcWorkScheduleRepository schedules,
      JdbcTimesheetRepository timesheets,
      JdbcTimesheetExportRepository exports,
      OrganizationPlacementDirectory directory,
      CursorCodec cursors,
      BusinessCalendar calendar,
      AuditRecorder audit,
      OutboxWriter outbox,
      OperationMetrics metrics,
      PlatformTransactionManager transactionManager,
      JsonMapper json) {
    this.operations = operations;
    this.schedules = schedules;
    this.timesheets = timesheets;
    this.exports = exports;
    this.directory = directory;
    this.cursors = cursors;
    this.calendar = calendar;
    this.audit = audit;
    this.outbox = outbox;
    this.metrics = metrics;
    this.transactionManager = transactionManager;
    this.json = json;
  }

  /**
   * A downloaded file.
   *
   * @param bytes content
   * @param filename safe attachment name
   */
  public record Download(byte[] bytes, String filename) {

    /** Copies the bytes. */
    public Download {
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Download d
          && java.util.Arrays.equals(bytes, d.bytes)
          && filename.equals(d.filename);
    }

    @Override
    public int hashCode() {
      return 31 * java.util.Arrays.hashCode(bytes) + filename.hashCode();
    }

    @Override
    public String toString() {
      return "TimesheetExportService.Download[" + bytes.length + " bytes]";
    }
  }

  // ------------------------------------------------------------------------------------------
  // Preview and creation
  // ------------------------------------------------------------------------------------------

  /**
   * What an export of the scope would hold now.
   *
   * @param caller verified tenant administrator
   * @param body legal entity, site and week
   * @return the preview, after its disclosure audit committed
   */
  public Preview preview(PeopleCaller caller, TimesheetExportPreviewRequest body) {
    return observed(
        PREVIEW,
        () -> {
          Target target = TimesheetExportCommand.target(body);
          return read(
              () -> {
                TenantId tenant = caller.tenant();
                Units units = units(tenant, target);
                Evaluation evaluation = evaluate(tenant, target, units);
                Instant expiresAt = expiry(now());
                String digest = previewDigest(tenant, evaluation, expiresAt);
                UUID existing =
                    exports
                        .byEvidence(
                            tenant, target.siteId(), target.weekStart(), evaluation.evidence())
                        .map(Header::id)
                        .orElse(null);
                Map<String, Integer> states = evaluation.states();
                disclosed(
                    caller,
                    PREVIEW,
                    "timesheet-export-preview",
                    evaluation.totals().timesheets(),
                    "DIVALHR-TIMESHEET-EXPORT-PREVIEW\nversion=1\nevidence="
                        + evaluation.evidence()
                        + "\n");
                return new Preview(
                    ScopeView.of(evaluation.scope(), units.legalEntity(), units.site()),
                    evaluation.scope().generationId(),
                    evaluation.scope().generationVersion(),
                    Counts.of(evaluation.totals()),
                    new Exclusions(
                        states.getOrDefault("DRAFT", 0),
                        states.getOrDefault("SUBMITTED", 0),
                        states.getOrDefault("REOPENED", 0)),
                    digest,
                    expiresAt,
                    existing);
              });
        });
  }

  /**
   * Creates the export of a reviewed preview, returns the existing export of identical evidence, or
   * replays an identical command.
   *
   * @param caller verified tenant administrator
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body scope, preview digest and confirmation
   * @return the result
   */
  public IdempotentOperation.Result<Result> create(
      PeopleCaller caller, String idempotencyKey, CreateTimesheetExportRequest body) {
    return command(
        CREATE,
        () -> {
          Create create =
              operations.validated(
                  CREATE_SPEC, () -> TimesheetExportCommand.create(idempotencyKey, body));
          return operations.execute(
              CREATE_SPEC,
              caller.subject(),
              idempotencyKey,
              create.canonical(caller.tenant()),
              Result.class,
              TRANSACTION_TIMEOUT,
              () -> created(caller, create));
        });
  }

  private IdempotentOperation.Completed<Result> created(PeopleCaller caller, Create create) {
    TenantId tenant = caller.tenant();
    schedules.lock(tenant);
    Target target = create.target();
    Units units = units(tenant, target);
    Evaluation evaluation = evaluate(tenant, target, units);
    Instant now = now();
    if (!fresh(tenant, evaluation, create.previewDigest(), now)) {
      throw stale();
    }
    if (evaluation.lines().isEmpty()) {
      throw new ApiException(ErrorCode.TIMESHEET_EXPORT_EMPTY, Map.of());
    }
    var existing =
        exports.byEvidence(tenant, target.siteId(), target.weekStart(), evaluation.evidence());
    if (existing.isPresent()) {
      Header header = existing.get();
      return new IdempotentOperation.Completed<>(
          new Result("EXISTING", Summary.of(header, units.legalEntity(), units.site())),
          header.id(),
          Outcome.UNCHANGED);
    }
    UUID id = UUID.randomUUID();
    byte[] csv = TimesheetExport.csv(id, evaluation.scope(), evaluation.lines());
    Header header =
        new Header(
            id,
            evaluation.scope(),
            evaluation.totals(),
            evaluation.evidence(),
            TimesheetExport.sha256(csv),
            now);
    try {
      exports.insert(tenant, header, evaluation.lines(), caller.subject());
      exports.checkConsistent(tenant);
    } catch (DataIntegrityViolationException violated) {
      throw mapped(violated);
    }
    recorded(caller, header, now);
    event(caller, header, now);
    LOG.atInfo()
        .addKeyValue("operation", CREATE)
        .addKeyValue("outcome", "created")
        .addKeyValue("timesheets", TimesheetService.band(header.totals().timesheets()))
        .log("timesheet_export_created");
    return new IdempotentOperation.Completed<>(
        new Result("CREATED", Summary.of(header, units.legalEntity(), units.site())),
        id,
        Outcome.CREATED);
  }

  // ------------------------------------------------------------------------------------------
  // History, detail and downloads
  // ------------------------------------------------------------------------------------------

  /**
   * One page of the tenant's exports, newest first, optionally of one site and week.
   *
   * @param caller verified tenant administrator
   * @param siteId raw site filter, or {@code null}
   * @param weekStart raw week filter, or {@code null}
   * @param cursor opaque cursor, or {@code null}
   * @param limit page size (1 to 100, default 20)
   * @return the page, after its disclosure audit committed
   */
  public Page list(
      PeopleCaller caller, String siteId, String weekStart, String cursor, String limit) {
    return observed(
        LIST,
        () -> {
          FieldErrors errors = new FieldErrors();
          Filter filter = TimesheetExportCommand.filter(siteId, weekStart, errors);
          int size = limit(limit, errors);
          errors.throwIfAny();
          return read(
              () -> {
                TenantId tenant = caller.tenant();
                Map<String, String> scopeFilters = new TreeMap<>();
                scopeFilters.put(
                    "site", filter.siteId() == null ? "-" : filter.siteId().toString());
                scopeFilters.put(
                    "week", filter.weekStart() == null ? "-" : filter.weekStart().toString());
                scopeFilters.put("limit", Integer.toString(size));
                CursorScope scope = new CursorScope(LIST, tenant, scopeFilters);
                Instant afterAt = null;
                UUID afterId = null;
                if (cursor != null) {
                  KeysetPosition position = cursors.decode(cursor, scope);
                  afterAt = createdAt(position.code());
                  afterId = position.id();
                }
                List<Header> rows =
                    exports.page(
                        tenant, filter.siteId(), filter.weekStart(), afterAt, afterId, size + 1);
                List<Header> shown = rows.subList(0, Math.min(rows.size(), size));
                String next =
                    rows.size() > size
                        ? cursors.encode(
                            scope,
                            new KeysetPosition(
                                "C" + micros(shown.get(shown.size() - 1).createdAt()),
                                shown.get(shown.size() - 1).id()))
                        : null;
                Set<UUID> ids = new HashSet<>();
                shown.forEach(
                    h -> {
                      ids.add(h.scope().legalEntityId());
                      ids.add(h.scope().period().siteId());
                    });
                Map<UUID, UnitView> resolved =
                    ids.isEmpty() ? Map.of() : directory.resolveIds(tenant, ids);
                StringBuilder text =
                    new StringBuilder("DIVALHR-TIMESHEET-EXPORT-LIST\nversion=1\n");
                List<Summary> items =
                    shown.stream()
                        .map(
                            h -> {
                              text.append("export=").append(h.id()).append('\n');
                              return Summary.of(
                                  h,
                                  kind(
                                      resolved.get(h.scope().legalEntityId()),
                                      UnitKind.LEGAL_ENTITY),
                                  kind(resolved.get(h.scope().period().siteId()), UnitKind.SITE));
                            })
                        .toList();
                disclosed(caller, LIST, "timesheet-exports", items.size(), text.toString());
                return new Page(items, next);
              });
        });
  }

  /**
   * One export: the manifest's facts.
   *
   * @param caller verified tenant administrator
   * @param exportId path value
   * @return the export, after its disclosure audit committed
   */
  public Summary export(PeopleCaller caller, String exportId) {
    return observed(
        READ,
        () -> {
          UUID id = TimesheetCommand.id(exportId);
          return read(
              () -> {
                TenantId tenant = caller.tenant();
                Header header = exports.export(tenant, id).orElseThrow(TimesheetCommand::notFound);
                disclosed(
                    caller,
                    READ,
                    "timesheet-export",
                    1,
                    "DIVALHR-TIMESHEET-EXPORT-READ\nversion=1\nexport=" + id + "\n");
                Map<UUID, UnitView> resolved =
                    directory.resolveIds(
                        tenant,
                        Set.of(header.scope().legalEntityId(), header.scope().period().siteId()));
                return Summary.of(
                    header,
                    kind(resolved.get(header.scope().legalEntityId()), UnitKind.LEGAL_ENTITY),
                    kind(resolved.get(header.scope().period().siteId()), UnitKind.SITE));
              });
        });
  }

  /**
   * The export's CSV, byte-identical on every download (its SHA-256 is checked against the stored
   * one). The download audit commits before the bytes are returned.
   *
   * @param caller verified tenant administrator
   * @param exportId path value
   * @return the CSV
   */
  public Download csv(PeopleCaller caller, String exportId) {
    return downloaded(
        caller,
        exportId,
        "csv",
        (tenant, header) -> {
          byte[] bytes =
              TimesheetExport.csv(header.id(), header.scope(), exports.lines(tenant, header.id()));
          if (!TimesheetExport.sha256(bytes).equals(header.csvSha256())) {
            // Never hand out bytes that differ from what was exported.
            throw new IllegalStateException("export digest mismatch");
          }
          return new Download(bytes, filename(header, "csv"));
        });
  }

  /**
   * The export's manifest (canonical JSON).
   *
   * @param caller verified tenant administrator
   * @param exportId path value
   * @return the manifest
   */
  public Download manifest(PeopleCaller caller, String exportId) {
    return downloaded(
        caller,
        exportId,
        "manifest",
        (tenant, header) ->
            new Download(TimesheetExport.manifest(header), filename(header, "manifest.json")));
  }

  /** Renders one download of a stored export. */
  @FunctionalInterface
  private interface Renderer {
    Download render(TenantId tenant, Header header);
  }

  private Download downloaded(
      PeopleCaller caller, String exportId, String format, Renderer renderer) {
    return observed(
        DOWNLOAD,
        () -> {
          UUID id = TimesheetCommand.id(exportId);
          return read(
              () -> {
                TenantId tenant = caller.tenant();
                Header header = exports.export(tenant, id).orElseThrow(TimesheetCommand::notFound);
                Download download = renderer.render(tenant, header);
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("schemaVersion", WorkScheduleService.SCHEMA_VERSION);
                metadata.put("format", format);
                metadata.put("rowCount", header.totals().rows());
                audit.record(
                    new AuditEvent(
                        UUID.randomUUID(),
                        now(),
                        caller.subject(),
                        DOWNLOAD,
                        RESOURCE,
                        header.id(),
                        tenant.value(),
                        "SUCCESS",
                        caller.correlationId(),
                        metadata,
                        Fingerprints.sha256(
                            "DIVALHR-TIMESHEET-EXPORT-DOWNLOAD\nversion=1\nexport="
                                + header.id()
                                + "\nformat="
                                + format
                                + "\nsha256="
                                + TimesheetExport.sha256(download.bytes())
                                + "\nby="
                                + caller.subject()
                                + "\n")));
                LOG.atInfo()
                    .addKeyValue("operation", DOWNLOAD)
                    .addKeyValue("outcome", "downloaded")
                    .addKeyValue("format", format)
                    .log("timesheet_export_downloaded");
                return download;
              });
        });
  }

  // ------------------------------------------------------------------------------------------
  // Evaluation
  // ------------------------------------------------------------------------------------------

  /** The verified legal entity and site of the request. */
  private record Units(UnitView legalEntity, UnitView site) {}

  /**
   * The scope's eligible lines now.
   *
   * @param scope scope and generation version
   * @param lines ordered eligible lines
   * @param totals their totals
   * @param evidence their evidence digest
   * @param states the generation's timesheets by state
   */
  private record Evaluation(
      Scope scope, List<Line> lines, Totals totals, String evidence, Map<String, Integer> states) {}

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

  /** The generated week's eligible lines, or the one safe 404 when the week is not generated. */
  private Evaluation evaluate(TenantId tenant, Target target, Units units) {
    Generation generation =
        timesheets
            .generationFor(tenant, target.siteId(), target.weekStart())
            .orElseThrow(TimesheetCommand::notFound);
    Scope scope =
        new Scope(
            target.legalEntityId(), generation.period(), generation.id(), generation.version());
    List<Line> lines = TimesheetExport.ordered(exports.eligible(tenant, generation.id()));
    return new Evaluation(
        scope,
        lines,
        Totals.of(lines),
        TimesheetExport.evidenceDigest(scope, lines),
        exports.states(tenant, generation.id()));
  }

  /** The end of the window after the current one (15 to 30 minutes from {@code at}). */
  static Instant expiry(Instant at) {
    long window = WINDOW.toMillis();
    long start = Math.floorDiv(at.toEpochMilli(), window) * window;
    return Instant.ofEpochMilli(start + 2 * window);
  }

  /** The preview digest: the evidence, the generation version (a refresh makes it stale). */
  private static String previewDigest(TenantId tenant, Evaluation evaluation, Instant expiresAt) {
    return Fingerprints.sha256(
        "DIVALHR-TIMESHEET-EXPORT-PREVIEW\nversion=1\ntenant="
            + tenant
            + "\nevidence="
            + evaluation.evidence()
            + "\ngenerationVersion="
            + evaluation.scope().generationVersion()
            + "\nexpiresAt="
            + expiresAt
            + "\n");
  }

  /**
   * Whether a preview digest matches the evidence now and has not expired: its expiry is the end of
   * the current or the next window.
   */
  private static boolean fresh(TenantId tenant, Evaluation evaluation, String digest, Instant now) {
    Instant next = expiry(now).minus(WINDOW);
    for (Instant expiresAt : List.of(next, next.plus(WINDOW))) {
      if (expiresAt.isAfter(now) && previewDigest(tenant, evaluation, expiresAt).equals(digest)) {
        return true;
      }
    }
    return false;
  }

  // ------------------------------------------------------------------------------------------
  // Audit and outbox
  // ------------------------------------------------------------------------------------------

  /** The creation audit: bounded metadata; identifiers, digests and the creator in the digest. */
  private void recorded(PeopleCaller caller, Header header, Instant now) {
    Totals totals = header.totals();
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", WorkScheduleService.SCHEMA_VERSION);
    metadata.put("exportSchemaVersion", TimesheetExport.SCHEMA_VERSION);
    metadata.put("week", header.scope().period().weekStart().toString());
    metadata.put("generationVersion", header.scope().generationVersion());
    metadata.put("timesheetCount", totals.timesheets());
    metadata.put("employeeCount", totals.employees());
    metadata.put("rowCount", totals.rows());
    metadata.put("workedMinuteBand", TimesheetExport.minuteBand(totals.workedMinutes()));
    Map<String, Object> state = new TreeMap<>();
    state.put("tenantId", caller.tenant().toString());
    state.put("exportId", header.id().toString());
    state.put("legalEntityId", header.scope().legalEntityId().toString());
    state.put("siteId", header.scope().period().siteId().toString());
    state.put("generationId", header.scope().generationId().toString());
    state.put("periodStartAt", header.scope().period().startAt().toString());
    state.put("periodEndAt", header.scope().period().endAt().toString());
    state.put("evidenceDigest", header.evidenceDigest());
    state.put("csvSha256", header.csvSha256());
    state.put("workedMinutes", totals.workedMinutes());
    state.put("createdAt", now.toString());
    state.put("by", caller.subject());
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            now,
            caller.subject(),
            CREATE,
            RESOURCE,
            header.id(),
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(json.writeValueAsString(state))));
  }

  /** Stable IDs, the scope, the schema version, counts, the minute band and the CSV digest. */
  private void event(PeopleCaller caller, Header header, Instant now) {
    Totals totals = header.totals();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("exportId", header.id().toString());
    data.put("exportSchemaVersion", TimesheetExport.SCHEMA_VERSION);
    data.put("legalEntityId", header.scope().legalEntityId().toString());
    data.put("siteId", header.scope().period().siteId().toString());
    data.put("generationId", header.scope().generationId().toString());
    data.put("generationVersion", header.scope().generationVersion());
    data.put("weekStart", header.scope().period().weekStart().toString());
    data.put("periodStartAt", header.scope().period().startAt().toString());
    data.put("periodEndAt", header.scope().period().endAt().toString());
    data.put("timesheetCount", totals.timesheets());
    data.put("employeeCount", totals.employees());
    data.put("rowCount", totals.rows());
    data.put("workedMinuteBand", TimesheetExport.minuteBand(totals.workedMinutes()));
    data.put("csvSha256", header.csvSha256());
    data.put("status", "READY");
    data.put("occurredAt", now.toString());
    outbox.append(
        new EventEnvelope(
            UUID.randomUUID().toString(),
            CREATED_EVENT,
            WorkScheduleService.SCHEMA_VERSION,
            caller.tenant().toString(),
            SOURCE,
            header.id().toString(),
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
            Fingerprints.sha256(text + "by=" + caller.subject() + "\n")));
  }

  // ------------------------------------------------------------------------------------------
  // Shared
  // ------------------------------------------------------------------------------------------

  private static UnitView kind(UnitView view, UnitKind kind) {
    return view != null && view.kind() == kind ? view : null;
  }

  /** A safe attachment name: the week, the export ID and the extension only. */
  private static String filename(Header header, String extension) {
    return "divalhr-time-export-"
        + header.scope().period().weekStart()
        + "-"
        + header.id()
        + "."
        + extension;
  }

  /** The creation instant as epoch microseconds (a cursor code is {@code [A-Z0-9_-]}). */
  private static long micros(Instant instant) {
    return ChronoUnit.MICROS.between(Instant.EPOCH, instant);
  }

  private static Instant createdAt(String code) {
    if (code == null || !code.matches("^C[0-9]{1,18}$")) {
      throw WorkScheduleService.invalidCursor();
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
    return calendar.now().truncatedTo(ChronoUnit.MICROS);
  }

  private static ApiException stale() {
    return new ApiException(ErrorCode.TIMESHEET_EXPORT_PREVIEW_STALE, Map.of());
  }

  /** The stable conflict of a constraint the locks should have made unreachable; no DB text. */
  private static ApiException mapped(DataIntegrityViolationException violated) {
    String constraint = ScheduleConstraintViolations.constraint(violated).orElse("");
    return switch (constraint) {
      case ScheduleConstraintViolations.TIMESHEET_EXPORT_EVIDENCE,
          ScheduleConstraintViolations.TIMESHEET_EXPORT_BIND,
          ScheduleConstraintViolations.TIMESHEET_EXPORT_ROW_BINDING,
          ScheduleConstraintViolations.TIMESHEET_EXPORT_EXACT ->
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
          .log("timesheet_export_command_rejected");
      throw refused;
    }
  }

  /** Records a read, preview or download outcome; rejections log only their code. */
  private <T> T observed(String operation, Supplier<T> work) {
    try {
      T result = work.get();
      metrics.record(operation, Outcome.LISTED);
      LOG.atInfo()
          .addKeyValue("operation", operation)
          .addKeyValue("outcome", "listed")
          .log("timesheet_export_read");
      return result;
    } catch (ApiException rejected) {
      Outcome outcome =
          switch (rejected.code()) {
            case TIMESHEET_TARGET_NOT_FOUND -> Outcome.NOT_FOUND;
            case VALIDATION_FAILED, CURSOR_INVALID -> Outcome.VALIDATION_FAILED;
            default -> Outcome.STATE_CONFLICT;
          };
      metrics.record(operation, outcome);
      LOG.atInfo()
          .addKeyValue("operation", operation)
          .addKeyValue("outcome", outcome.name().toLowerCase(Locale.ROOT))
          .addKeyValue("code", rejected.code().name())
          .log("timesheet_export_read_rejected");
      throw rejected;
    } catch (RuntimeException failure) {
      metrics.record(operation, Outcome.FAILURE);
      LOG.atWarn()
          .addKeyValue("operation", operation)
          .addKeyValue("outcome", "failure")
          .log("timesheet_export_read_failed");
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
