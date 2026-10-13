package com.divalhr.core.people.schedule.application;

import com.divalhr.core.people.application.BusinessCalendar;
import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.schedule.api.AttendanceExceptionResponses.Item;
import com.divalhr.core.people.schedule.api.AttendanceExceptionResponses.Page;
import com.divalhr.core.people.schedule.api.AttendanceExceptionResponses.TeamItem;
import com.divalhr.core.people.schedule.api.AttendanceExceptionResponses.TeamPage;
import com.divalhr.core.people.schedule.domain.AttendanceExceptions;
import com.divalhr.core.people.schedule.domain.AttendanceExceptions.Filter;
import com.divalhr.core.people.schedule.domain.AttendanceExceptions.Position;
import com.divalhr.core.people.schedule.domain.AttendanceExceptions.Row;
import com.divalhr.core.people.schedule.domain.AttendanceExceptions.Scope;
import com.divalhr.core.people.schedule.domain.AttendanceExceptions.Self;
import com.divalhr.core.people.schedule.domain.AttendanceExceptions.Team;
import com.divalhr.core.people.schedule.domain.AttendanceExceptions.Tenant;
import com.divalhr.core.people.schedule.domain.AttendanceExceptions.Type;
import com.divalhr.core.people.schedule.internal.JdbcAttendanceExceptionRepository;
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
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MVP-060C (Issue #119, C60C-1 to C60C-6): the read-only review of factual attendance exceptions,
 * derived at one server-owned instant ({@code asOfInstant}, pinned in the signed cursor) by {@link
 * JdbcAttendanceExceptionRepository}; nothing is stored and nothing changes.
 *
 * <ul>
 *   <li>the employee: only their own (the caller's current active employee-access link);
 *   <li>the effective manager: the employees whose qualifying employment (a record's employment)
 *       names the caller's linked employee on the shift's site-local start date, re-evaluated on
 *       every page; never the caller's own; a caller who manages nobody gets an empty page;
 *   <li>the tenant administrator (exact MFA, checked by the interceptor on every request): the
 *       tenant, optionally one of its sites, active or historical ({@code 404
 *       ATTENDANCE_EXCEPTION_SCOPE_NOT_FOUND} otherwise, the same for unknown, foreign, malformed
 *       and non-site IDs).
 * </ul>
 *
 * <p>Every page is a fail-closed disclosure: its audit record (view, page kind, result count, the
 * type filter and whether a site filter exists) commits before the response; the scope, the instant
 * and the returned IDs and types only in its digest. A cursor binds the operation, tenant, a
 * subject digest, the link and employee (self, manager) or the membership (tenant administrator),
 * the filters, the page size and the instant; its position (a record, or the slot of a missing
 * check-in) is reconstructed inside the same scope, so a position no longer in scope is {@code 400
 * CURSOR_INVALID} and reveals nothing. Logs and metrics carry the operation, a bounded outcome, the
 * page kind and the result count only.
 */
@Service
public class AttendanceExceptionService {

  /** The employee's own exceptions (interceptor operation and disclosure audit action). */
  public static final String SELF_LIST = "attendance.exception-self-list";

  /** The manager's direct reports' exceptions. */
  public static final String TEAM_LIST = "attendance.exception-team-list";

  /** The tenant administrator's review. */
  public static final String ADMIN_LIST = "attendance.exception-admin-list";

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.schedule");
  private static final Duration TRANSACTION_TIMEOUT = Duration.ofSeconds(15);
  private static final String PREFIX = "AE";
  private static final Pattern ANCHORED = Pattern.compile("^AE([0-9]{1,16})-([0-3])$");

  private final JdbcAttendanceExceptionRepository exceptions;
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
   * @param exceptions the derived exceptions
   * @param links employee access links (identity port)
   * @param directory organization units (sites)
   * @param calendar the platform clock
   * @param cursors signed cursors
   * @param audit audit recorder
   * @param metrics operation metrics
   * @param meters meter registry
   * @param transactionManager transactions
   */
  public AttendanceExceptionService(
      JdbcAttendanceExceptionRepository exceptions,
      EmployeeAccessLinks links,
      OrganizationPlacementDirectory directory,
      BusinessCalendar calendar,
      CursorCodec cursors,
      AuditRecorder audit,
      OperationMetrics metrics,
      MeterRegistry meters,
      PlatformTransactionManager transactionManager) {
    this.exceptions = exceptions;
    this.links = links;
    this.directory = directory;
    this.calendar = calendar;
    this.cursors = cursors;
    this.audit = audit;
    this.metrics = metrics;
    this.meters = meters;
    this.transactionManager = transactionManager;
  }

  /** Raw query values of a list request. */
  private record Query(String type, String from, String to, String cursor, String limit) {}

  /** A page before its view: the rows, the next cursor and the instant. */
  private record Listed(List<Row> rows, String next, Instant asOf, Map<UUID, UnitView> sites) {}

  /**
   * One page of the caller's own exceptions.
   *
   * @param caller verified employee
   * @param type type filter or {@code null}
   * @param from first site-local date or {@code null}
   * @param to last site-local date or {@code null}
   * @param cursor opaque cursor or {@code null}
   * @param limit page size (1 to 100, default 20)
   * @return the page
   */
  public Page self(
      PeopleCaller caller, String type, String from, String to, String cursor, String limit) {
    Query query = new Query(blank(type), blank(from), blank(to), cursor, limit);
    return observed(
        SELF_LIST,
        query,
        (filter, size) -> {
          Listed listed =
              read(
                  () -> {
                    SelfLink link = link(caller);
                    Map<String, String> binding = new HashMap<>();
                    binding.put("link", link.linkId().toString());
                    binding.put("employee", link.employeeId().toString());
                    return listed(
                        caller,
                        SELF_LIST,
                        "my-attendance-exceptions",
                        new Self(link.employeeId()),
                        binding,
                        filter,
                        size,
                        cursor,
                        false);
                  });
          return new Page(
              listed.rows().stream()
                  .map(row -> Item.of(row, listed.sites().get(row.shift().siteId())))
                  .toList(),
              listed.next(),
              listed.asOf());
        },
        Page::items);
  }

  /**
   * One page of the caller's direct reports' exceptions.
   *
   * @param caller verified employee
   * @param type type filter or {@code null}
   * @param from first site-local date or {@code null}
   * @param to last site-local date or {@code null}
   * @param cursor opaque cursor or {@code null}
   * @param limit page size (1 to 100, default 20)
   * @return the page
   */
  public TeamPage team(
      PeopleCaller caller, String type, String from, String to, String cursor, String limit) {
    Query query = new Query(blank(type), blank(from), blank(to), cursor, limit);
    return observed(
        TEAM_LIST,
        query,
        (filter, size) -> {
          Listed listed =
              read(
                  () -> {
                    SelfLink link = link(caller);
                    Map<String, String> binding = new HashMap<>();
                    binding.put("link", link.linkId().toString());
                    binding.put("manager", link.employeeId().toString());
                    return listed(
                        caller,
                        TEAM_LIST,
                        "team-attendance-exceptions",
                        new Team(link.employeeId()),
                        binding,
                        filter,
                        size,
                        cursor,
                        false);
                  });
          return teamPage(listed);
        },
        TeamPage::items);
  }

  /**
   * One page of the tenant's exceptions, optionally of one site.
   *
   * @param caller verified tenant administrator (exact MFA)
   * @param siteId site filter or {@code null}
   * @param type type filter or {@code null}
   * @param from first site-local date or {@code null}
   * @param to last site-local date or {@code null}
   * @param cursor opaque cursor or {@code null}
   * @param limit page size (1 to 100, default 20)
   * @return the page
   */
  public TeamPage tenant(
      PeopleCaller caller,
      String siteId,
      String type,
      String from,
      String to,
      String cursor,
      String limit) {
    Query query = new Query(blank(type), blank(from), blank(to), cursor, limit);
    return observed(
        ADMIN_LIST,
        query,
        (filter, size) -> {
          Listed listed =
              read(
                  () -> {
                    UUID site = site(caller.tenant(), siteId);
                    Map<String, String> binding = new HashMap<>();
                    binding.put(
                        "membership",
                        Fingerprints.sha256(
                            "DIVALHR-ATTENDANCE-EXCEPTIONS\nmembership="
                                + caller.tenant()
                                + "|tenant-admin|"
                                + caller.subject()));
                    binding.put("siteId", site == null ? "-" : site.toString());
                    return listed(
                        caller,
                        ADMIN_LIST,
                        "attendance-review",
                        new Tenant(site),
                        binding,
                        filter,
                        size,
                        cursor,
                        site != null);
                  });
          return teamPage(listed);
        },
        TeamPage::items);
  }

  private static TeamPage teamPage(Listed listed) {
    return new TeamPage(
        listed.rows().stream()
            .map(row -> TeamItem.of(row, listed.sites().get(row.shift().siteId())))
            .toList(),
        listed.next(),
        listed.asOf());
  }

  /** The site filter: one of the tenant's sites, or the same 404 for anything else. */
  private UUID site(TenantId tenant, String raw) {
    if (raw == null || raw.isEmpty()) {
      return null;
    }
    UUID id;
    try {
      id = UUID.fromString(raw);
    } catch (IllegalArgumentException malformed) {
      throw scopeNotFound();
    }
    if (!id.toString().equals(raw.toLowerCase(Locale.ROOT))) {
      throw scopeNotFound();
    }
    UnitView unit = directory.resolveIds(tenant, Set.of(id)).get(id);
    if (unit == null || unit.kind() != OrganizationPlacementDirectory.UnitKind.SITE) {
      throw scopeNotFound();
    }
    return id;
  }

  /** One page in a scope: cursor, rows, sites and the fail-closed disclosure. */
  private Listed listed(
      PeopleCaller caller,
      String operation,
      String view,
      Scope scope,
      Map<String, String> binding,
      Filter filter,
      int size,
      String cursor,
      boolean siteFilter) {
    TenantId tenant = caller.tenant();
    Map<String, String> filters = new HashMap<>(binding);
    filters.put(
        "subject",
        Fingerprints.sha256("DIVALHR-ATTENDANCE-EXCEPTIONS\nsubject=" + caller.subject()));
    filters.put("type", filter.type() == null ? "-" : filter.type().name());
    filters.put("from", filter.from() == null ? "-" : filter.from().toString());
    filters.put("to", filter.to() == null ? "-" : filter.to().toString());
    filters.put("limit", Integer.toString(size));
    CursorScope bound = new CursorScope(operation, tenant, filters);
    Instant asOf;
    Position after = null;
    if (cursor != null) {
      KeysetPosition position = cursors.decode(cursor, bound);
      Matcher anchored = ANCHORED.matcher(position.code());
      if (!anchored.matches()) {
        throw WorkScheduleService.invalidCursor();
      }
      asOf = Instant.EPOCH.plus(Long.parseLong(anchored.group(1)), ChronoUnit.MICROS);
      Type type = Type.ofRank(Integer.parseInt(anchored.group(2)));
      after =
          exceptions
              .position(tenant, scope, filter, asOf, type, position.id())
              .orElseThrow(WorkScheduleService::invalidCursor);
    } else {
      asOf = calendar.now().truncatedTo(ChronoUnit.MICROS);
    }
    List<Row> rows = exceptions.page(tenant, scope, filter, asOf, after, size + 1);
    List<Row> shown = rows.subList(0, Math.min(rows.size(), size));
    String next = null;
    if (rows.size() > size) {
      Row last = shown.get(shown.size() - 1);
      next =
          cursors.encode(
              bound,
              new KeysetPosition(
                  PREFIX
                      + ChronoUnit.MICROS.between(Instant.EPOCH, asOf)
                      + "-"
                      + last.type().rank(),
                  last.type() == Type.MISSING_CHECK_IN ? last.shift().slotId() : last.recordId()));
    }
    Set<UUID> siteIds = new LinkedHashSet<>();
    shown.forEach(row -> siteIds.add(row.shift().siteId()));
    Map<UUID, UnitView> sites =
        siteIds.isEmpty() ? Map.of() : directory.resolveIds(tenant, siteIds);
    disclosed(caller, operation, view, scope, filter, siteFilter, cursor, asOf, shown);
    return new Listed(List.copyOf(shown), next, asOf, sites);
  }

  /**
   * The fail-closed disclosure audit (C60C-5): the view, page kind, count, the type filter and
   * whether a site filter exists; the scope, the instant and the returned IDs and types only in the
   * digest. Never a name, number, label, date or site.
   */
  private void disclosed(
      PeopleCaller caller,
      String operation,
      String view,
      Scope scope,
      Filter filter,
      boolean siteFilter,
      String cursor,
      Instant asOf,
      List<Row> rows) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", WorkScheduleService.SCHEMA_VERSION);
    metadata.put("view", view);
    metadata.put("page", cursor == null ? "first" : "next");
    metadata.put("resultCount", rows.size());
    if (filter.type() != null) {
      metadata.put("type", filter.type().name());
    }
    metadata.put("siteFilter", siteFilter);
    StringBuilder text =
        new StringBuilder("DIVALHR-ATTENDANCE-EXCEPTION-DISCLOSURE\nversion=1\nview=")
            .append(view)
            .append("\nscope=")
            .append(scopeText(scope))
            .append("\nasOf=")
            .append(asOf)
            .append('\n');
    rows.forEach(
        row ->
            text.append("slot=")
                .append(row.shift().slotId())
                .append(";employee=")
                .append(row.employeeId())
                .append(";record=")
                .append(row.recordId())
                .append(";type=")
                .append(row.type())
                .append('\n'));
    String resource;
    UUID resourceId;
    switch (scope) {
      case Self self -> {
        resource = "employee";
        resourceId = self.employeeId();
      }
      case Team team -> {
        resource = "employee";
        resourceId = team.managerId();
      }
      case Tenant tenant -> {
        resource = "organization";
        resourceId = caller.tenant().value();
      }
    }
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            calendar.now().truncatedTo(ChronoUnit.MICROS),
            caller.subject(),
            operation,
            resource,
            resourceId,
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(text.toString())));
  }

  private static String scopeText(Scope scope) {
    return switch (scope) {
      case Self self -> "self:" + self.employeeId();
      case Team team -> "team:" + team.managerId();
      case Tenant tenant -> "tenant:" + (tenant.siteId() == null ? "-" : tenant.siteId());
    };
  }

  /** A page function of the validated filter and size. */
  private interface Lister<T> {
    T list(Filter filter, int size);
  }

  /**
   * Validates the query, lists, then logs and counts the outcome (operation, bounded outcome, page
   * kind and result count only).
   */
  private <T> T observed(
      String operation, Query query, Lister<T> lister, Function<T, List<?>> items) {
    try {
      FieldErrors errors = new FieldErrors();
      Type type = type(query.type(), errors);
      LocalDate from = WorkScheduleCommand.optionalDate(errors, "from", query.from());
      LocalDate to = WorkScheduleCommand.optionalDate(errors, "to", query.to());
      if (query.from() != null && query.to() == null) {
        errors.add("to", Constraint.REQUIRED);
      } else if (query.from() == null && query.to() != null) {
        errors.add("from", Constraint.REQUIRED);
      } else if (from != null
          && to != null
          && (to.isBefore(from)
              || ChronoUnit.DAYS.between(from, to) >= AttendanceExceptions.MAX_RANGE_DAYS)) {
        errors.add("to", Constraint.RANGE);
      }
      int size = AttendanceService.limit(query.limit(), errors);
      errors.throwIfAny();
      T page = counted(operation, () -> lister.list(new Filter(type, from, to), size));
      metrics.record(operation, Outcome.LISTED);
      LOG.atInfo()
          .addKeyValue("operation", operation)
          .addKeyValue("page", query.cursor() == null ? "first" : "next")
          .addKeyValue("resultCount", items.apply(page).size())
          .addKeyValue("outcome", "listed")
          .log("attendance_exceptions_listed");
      return page;
    } catch (ApiException rejected) {
      Outcome outcome =
          switch (rejected.code()) {
            case EMPLOYEE_LINK_REQUIRED -> Outcome.DENIED;
            case ATTENDANCE_EXCEPTION_SCOPE_NOT_FOUND -> Outcome.NOT_FOUND;
            default -> Outcome.VALIDATION_FAILED;
          };
      metrics.record(operation, outcome);
      LOG.atInfo()
          .addKeyValue("operation", operation)
          .addKeyValue("outcome", outcome.name().toLowerCase(Locale.ROOT))
          .addKeyValue("code", rejected.code().name())
          .log("attendance_exceptions_rejected");
      throw rejected;
    } catch (RuntimeException failure) {
      metrics.record(operation, Outcome.FAILURE);
      throw failure;
    }
  }

  private static String blank(String raw) {
    return raw == null || raw.isEmpty() ? null : raw;
  }

  private static Type type(String raw, FieldErrors errors) {
    if (raw == null || raw.isEmpty()) {
      return null;
    }
    for (Type type : Type.values()) {
      if (type.name().equals(raw)) {
        return type;
      }
    }
    errors.add("type", Constraint.FORMAT);
    return null;
  }

  /** The caller's own active link, or {@code 403 EMPLOYEE_LINK_REQUIRED}. */
  private SelfLink link(PeopleCaller caller) {
    return links
        .linkedEmployee(caller.tenant(), caller.subject())
        .orElseThrow(() -> new ApiException(ErrorCode.EMPLOYEE_LINK_REQUIRED, Map.of()));
  }

  private static ApiException scopeNotFound() {
    return new ApiException(ErrorCode.ATTENDANCE_EXCEPTION_SCOPE_NOT_FOUND, Map.of());
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

  /** Counts link denials and undisclosable scopes on the bounded self-service counter only. */
  private <T> T counted(String operation, Supplier<T> work) {
    try {
      return work.get();
    } catch (ApiException denied) {
      if (denied.code() == ErrorCode.EMPLOYEE_LINK_REQUIRED) {
        ScopeAuthorizationInterceptor.selfServiceDenied(meters, operation, "link_required");
      }
      throw denied;
    }
  }
}
