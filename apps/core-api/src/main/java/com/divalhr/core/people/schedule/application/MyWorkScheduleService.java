package com.divalhr.core.people.schedule.application;

import com.divalhr.core.people.application.BusinessCalendar;
import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.schedule.api.WorkScheduleResponses.Shift;
import com.divalhr.core.people.schedule.api.WorkScheduleResponses.ShiftPage;
import com.divalhr.core.people.schedule.domain.OwnShift;
import com.divalhr.core.people.schedule.internal.JdbcWorkScheduleRepository;
import com.divalhr.core.platform.access.EmployeeAccessLinks;
import com.divalhr.core.platform.access.EmployeeAccessLinks.SelfLink;
import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.security.ScopeAuthorizationInterceptor;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.UnitView;
import com.divalhr.core.platform.tenancy.TenantId;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The employee's own published shifts (MVP-050A, D50A-9). Authorization (role {@code employee},
 * verified tenant, active membership) has run before this; the caller's own employee is then
 * resolved through the identity port's current active link ({@code FOR SHARE}); no link is {@code
 * 403 EMPLOYEE_LINK_REQUIRED}, counted on the bounded self-service counter, never durable
 * privileged-denial evidence. Only the caller's own assigned slots are read: never another
 * employee, an uncovered slot, the publisher or audit data.
 *
 * <p>Two views (R100-2). Without {@code from}, the default view holds the shifts not yet ended at
 * one server-owned instant taken from the platform clock ({@code end_at > asOfInstant}: shifts in
 * progress and to come, never ended ones). No single calendar date means "now" across schedules of
 * sites in different time zones, so the organization's date plays no part. The instant is pinned in
 * the signed cursor and reused by every later page, so the clock moving between pages neither adds
 * nor drops a shift. With {@code from} (and optionally {@code to}, at most 366 days later), the
 * view holds the shifts whose local start date, in their own schedule's site time zone, is within
 * the dates. Every shift is returned in its own stored site time zone, never converted.
 *
 * <p>A fail-closed disclosure: the page is returned only after its audit record committed with the
 * read. The cursor binds the tenant, the subject (a digest), the view's mode and dates and the page
 * size; a cursor of one mode is refused in the other.
 */
@Service
public class MyWorkScheduleService {

  /** Self view (interceptor operation and disclosure audit action). */
  public static final String SELF_LIST = "work-schedule.self-list";

  /** Per-subject self-service read bucket (60 per minute). */
  public static final String SUBJECT_READ_BUCKET = "work-schedule-self-read";

  /** Longest self view: 366 days. */
  static final int MAX_DAYS = 366;

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.schedule");

  /** Keyset code of an explicit-dates page. */
  private static final String EXPLICIT = "MS";

  /** Keyset code prefix of a default (current) page: the anchor's epoch microseconds follow. */
  private static final String DEFAULT = "MD";

  private static final Pattern ANCHOR = Pattern.compile("^MD[0-9]{1,18}$");
  private static final Duration TRANSACTION_TIMEOUT = Duration.ofSeconds(15);

  private final JdbcWorkScheduleRepository schedules;
  private final EmployeeAccessLinks links;
  private final OrganizationPlacementDirectory directory;
  private final BusinessCalendar calendar;
  private final CursorCodec cursors;
  private final AuditRecorder audit;
  private final OperationMetrics metrics;
  private final MeterRegistry meters;
  private final PlatformTransactionManager transactionManager;

  /**
   * Creates the service.
   *
   * @param schedules schedule storage
   * @param links the identity port (joins this module's transactions)
   * @param directory the tenant module's unit port (site codes and names)
   * @param calendar the platform clock (the default view's anchor instant)
   * @param cursors signed cursors
   * @param audit audit recorder
   * @param metrics operation metrics
   * @param meters meter registry (self-service denial counter)
   * @param transactionManager transaction manager
   */
  public MyWorkScheduleService(
      JdbcWorkScheduleRepository schedules,
      EmployeeAccessLinks links,
      OrganizationPlacementDirectory directory,
      BusinessCalendar calendar,
      CursorCodec cursors,
      AuditRecorder audit,
      OperationMetrics metrics,
      MeterRegistry meters,
      PlatformTransactionManager transactionManager) {
    this.schedules = schedules;
    this.links = links;
    this.directory = directory;
    this.calendar = calendar;
    this.cursors = cursors;
    this.audit = audit;
    this.metrics = metrics;
    this.meters = meters;
    this.transactionManager = transactionManager;
  }

  /**
   * One page of the caller's own published shifts in start order: without {@code from}, those not
   * yet ended at the pinned server instant; with {@code from}, those starting on the local dates
   * {@code from} to {@code to} (optional, at most 366 days later; {@code to} alone is refused).
   *
   * @param caller verified employee
   * @param from first start date or {@code null}
   * @param to last start date or {@code null}
   * @param cursor opaque cursor or {@code null}
   * @param limit page size (1 to 50, default 25)
   * @return the page
   */
  public ShiftPage list(PeopleCaller caller, String from, String to, String cursor, String limit) {
    try {
      ShiftPage page = counted(() -> listed(caller, from, to, cursor, limit));
      metrics.record(SELF_LIST, Outcome.LISTED);
      LOG.atInfo()
          .addKeyValue("operation", SELF_LIST)
          .addKeyValue("page", cursor == null ? "first" : "next")
          .addKeyValue("resultCount", page.items().size())
          .addKeyValue("outcome", "listed")
          .log("work_schedule_self_listed");
      return page;
    } catch (ApiException rejected) {
      Outcome outcome =
          rejected.code() == ErrorCode.EMPLOYEE_LINK_REQUIRED
              ? Outcome.DENIED
              : Outcome.VALIDATION_FAILED;
      metrics.record(SELF_LIST, outcome);
      LOG.atInfo()
          .addKeyValue("operation", SELF_LIST)
          .addKeyValue("outcome", outcome.name().toLowerCase(Locale.ROOT))
          .addKeyValue("code", rejected.code().name())
          .log("work_schedule_self_list_rejected");
      throw rejected;
    } catch (RuntimeException failure) {
      metrics.record(SELF_LIST, Outcome.FAILURE);
      throw failure;
    }
  }

  private ShiftPage listed(
      PeopleCaller caller, String from, String to, String cursor, String limit) {
    FieldErrors errors = new FieldErrors();
    LocalDate fromDate = WorkScheduleCommand.optionalDate(errors, "from", from);
    LocalDate toDate = WorkScheduleCommand.optionalDate(errors, "to", to);
    int size = WorkScheduleService.limit(limit, errors);
    if (from == null && to != null) {
      // An end date only bounds an explicit view; the default view is instant-based.
      errors.add("from", Constraint.REQUIRED);
    }
    if (fromDate != null
        && toDate != null
        && (toDate.isBefore(fromDate) || ChronoUnit.DAYS.between(fromDate, toDate) >= MAX_DAYS)) {
      errors.add("to", Constraint.RANGE);
    }
    errors.throwIfAny();
    boolean explicit = fromDate != null;
    TenantId tenant = caller.tenant();
    return read(
        () -> {
          SelfLink link =
              links
                  .linkedEmployee(tenant, caller.subject())
                  .orElseThrow(() -> new ApiException(ErrorCode.EMPLOYEE_LINK_REQUIRED, Map.of()));
          CursorScope scope = scope(caller, explicit, fromDate, toDate, size);
          Instant anchor = null;
          Instant afterAt = null;
          UUID afterId = null;
          if (cursor != null) {
            KeysetPosition position = cursors.decode(cursor, scope);
            if (explicit) {
              requireExplicit(position);
            } else {
              anchor = anchorOf(position);
            }
            afterAt =
                schedules
                    .ownStart(tenant, link.employeeId(), position.id())
                    .orElseThrow(WorkScheduleService::invalidCursor);
            afterId = position.id();
          } else if (!explicit) {
            anchor = calendar.now().truncatedTo(ChronoUnit.MICROS);
          }
          List<OwnShift> rows =
              schedules.ownShifts(
                  tenant, link.employeeId(), anchor, fromDate, toDate, afterAt, afterId, size + 1);
          List<OwnShift> shown = rows.subList(0, Math.min(rows.size(), size));
          String code = explicit ? EXPLICIT : anchorCode(anchor);
          String next =
              rows.size() > size
                  ? cursors.encode(
                      scope, new KeysetPosition(code, shown.get(shown.size() - 1).slotId()))
                  : null;
          Set<UUID> siteIds = new LinkedHashSet<>();
          shown.forEach(shift -> siteIds.add(shift.siteId()));
          Map<UUID, UnitView> sites =
              siteIds.isEmpty() ? Map.of() : directory.resolveIds(tenant, siteIds);
          disclosed(
              caller,
              link.employeeId(),
              cursor == null ? "first" : "next",
              anchor,
              fromDate,
              toDate,
              shown);
          // MVP-060A: the attendance actions are evaluated at the view's instant (the default
          // view's anchor, or the server's current instant for explicit dates).
          Instant at = anchor != null ? anchor : calendar.now().truncatedTo(ChronoUnit.MICROS);
          return new ShiftPage(
              shown.stream().map(shift -> Shift.of(shift, sites.get(shift.siteId()), at)).toList(),
              next,
              anchor,
              fromDate,
              toDate);
        });
  }

  /**
   * The keyset code of a default-view page: {@code "MD"} and the anchor instant in epoch
   * microseconds. It travels inside the signed cursor payload, so later pages reuse exactly the
   * server's anchor; it cannot be chosen or altered by the client.
   */
  static String anchorCode(Instant anchor) {
    long micros = ChronoUnit.MICROS.between(Instant.EPOCH, anchor);
    return DEFAULT + micros;
  }

  /** The anchor pinned in a default-view cursor; anything else is {@code CURSOR_INVALID}. */
  static Instant anchorOf(KeysetPosition position) {
    String code = position.code();
    if (!ANCHOR.matcher(code).matches()) {
      throw WorkScheduleService.invalidCursor();
    }
    return Instant.EPOCH.plus(Long.parseLong(code.substring(DEFAULT.length())), ChronoUnit.MICROS);
  }

  private static void requireExplicit(KeysetPosition position) {
    if (!EXPLICIT.equals(position.code())) {
      throw WorkScheduleService.invalidCursor();
    }
  }

  /**
   * Bound to the operation, tenant, subject (a digest), the view's mode, its explicit dates and the
   * page size. The default view's anchor is pinned in the signed position.
   */
  private static CursorScope scope(
      PeopleCaller caller, boolean explicit, LocalDate from, LocalDate to, int size) {
    Map<String, String> filters = new HashMap<>();
    filters.put(
        "subject", Fingerprints.sha256("DIVALHR-MY-WORK-SCHEDULE\nsubject=" + caller.subject()));
    filters.put("mode", explicit ? "dates" : "current");
    filters.put("from", from == null ? "-" : from.toString());
    filters.put("to", to == null ? "-" : to.toString());
    filters.put("limit", Integer.toString(size));
    return new CursorScope(SELF_LIST, caller.tenant(), filters);
  }

  /** The disclosure audit: the page and the count; the slot IDs only in the digest. */
  private void disclosed(
      PeopleCaller caller,
      UUID employeeId,
      String page,
      Instant anchor,
      LocalDate from,
      LocalDate to,
      List<OwnShift> shown) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", WorkScheduleService.SCHEMA_VERSION);
    metadata.put("view", "my-work-schedule");
    metadata.put("page", page);
    metadata.put("resultCount", shown.size());
    StringBuilder text =
        new StringBuilder("DIVALHR-MY-WORK-SCHEDULE-DISCLOSURE\nversion=3\nemployee=")
            .append(employeeId)
            .append('\n');
    if (anchor != null) {
      text.append("mode=current\nanchor=").append(anchor).append('\n');
    } else {
      text.append("mode=dates\nfrom=")
          .append(from)
          .append("\nto=")
          .append(to == null ? "-" : to)
          .append('\n');
    }
    shown.forEach(
        shift ->
            text.append("slot=")
                .append(shift.slotId())
                .append(";attendance=")
                .append(shift.attendance().status())
                .append('\n'));
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            calendar.now().truncatedTo(ChronoUnit.MICROS),
            caller.subject(),
            SELF_LIST,
            "employee",
            employeeId,
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(text.toString())));
  }

  /** Counts link denials on the self-service counter. */
  private <T> T counted(Supplier<T> work) {
    try {
      return work.get();
    } catch (ApiException denied) {
      if (denied.code() == ErrorCode.EMPLOYEE_LINK_REQUIRED) {
        ScopeAuthorizationInterceptor.selfServiceDenied(meters, SELF_LIST, "link_required");
      }
      throw denied;
    }
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
