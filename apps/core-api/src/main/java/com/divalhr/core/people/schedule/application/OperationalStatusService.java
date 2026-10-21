package com.divalhr.core.people.schedule.application;

import com.divalhr.core.people.application.BusinessCalendar;
import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.schedule.domain.OperationalStatus.Detail;
import com.divalhr.core.people.schedule.domain.OperationalStatus.NotCheckedIn;
import com.divalhr.core.people.schedule.domain.OperationalStatus.Organization;
import com.divalhr.core.people.schedule.domain.OperationalStatus.OverdueLeave;
import com.divalhr.core.people.schedule.domain.OperationalStatus.OverdueTimesheet;
import com.divalhr.core.people.schedule.domain.OperationalStatus.Scope;
import com.divalhr.core.people.schedule.domain.OperationalStatus.Shifts;
import com.divalhr.core.people.schedule.domain.OperationalStatus.Status;
import com.divalhr.core.people.schedule.domain.OperationalStatus.Team;
import com.divalhr.core.people.schedule.domain.OperationalStatus.Waiting;
import com.divalhr.core.people.schedule.domain.Substitution.Shift;
import com.divalhr.core.people.schedule.internal.JdbcOperationalStatusRepository;
import com.divalhr.core.platform.access.EmployeeAccessLinks;
import com.divalhr.core.platform.access.EmployeeAccessLinks.SelfLink;
import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.security.ScopeAuthorizationInterceptor;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.UnitKind;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.UnitView;
import com.divalhr.core.platform.tenancy.TenantId;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MVP-100 (Issue #154, ADR 0026): the read-only operational status of today, derived at one
 * server-owned instant by {@link JdbcOperationalStatusRepository}; nothing is stored and nothing
 * changes.
 *
 * <ul>
 *   <li>the manager (employee role, current active employee link): the employees they manage on the
 *       reference dates, never themselves; a caller who manages nobody receives zeros;
 *   <li>the tenant administrator (exact MFA, checked by the interceptor on every request): the
 *       organization, optionally one site and/or one legal entity ({@code 404
 *       OPERATIONAL_STATUS_SCOPE_NOT_FOUND} for a malformed, unknown, foreign or wrong-kind
 *       identifier, the same answer for each).
 * </ul>
 *
 * <p>Every response is a fail-closed disclosure: its audit record (view, counts, whether each
 * filter exists) commits before the response; the scope, the instant and the returned identifiers
 * only in its digest. Logs and metrics carry the operation, a bounded outcome and counts only;
 * never a name, label, identifier, filter value or instant.
 */
@Service
public class OperationalStatusService {

  /** The manager's status (interceptor operation and disclosure audit action). */
  public static final String TEAM_VIEW = "operations.status-team-view";

  /** The tenant administrator's status. */
  public static final String ADMIN_VIEW = "operations.status-admin-view";

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.schedule");
  private static final Duration TRANSACTION_TIMEOUT = Duration.ofSeconds(15);

  private final JdbcOperationalStatusRepository status;
  private final EmployeeAccessLinks links;
  private final OrganizationPlacementDirectory directory;
  private final BusinessCalendar calendar;
  private final AuditRecorder audit;
  private final OperationMetrics metrics;
  private final MeterRegistry meters;
  private final PlatformTransactionManager transactionManager;

  /**
   * Creates the service.
   *
   * @param status the derived figures
   * @param links employee access links (identity port)
   * @param directory organization units (sites, legal entities)
   * @param calendar the platform clock
   * @param audit audit recorder
   * @param metrics operation metrics
   * @param meters meter registry
   * @param transactionManager transactions
   */
  public OperationalStatusService(
      JdbcOperationalStatusRepository status,
      EmployeeAccessLinks links,
      OrganizationPlacementDirectory directory,
      BusinessCalendar calendar,
      AuditRecorder audit,
      OperationMetrics metrics,
      MeterRegistry meters,
      PlatformTransactionManager transactionManager) {
    this.status = status;
    this.links = links;
    this.directory = directory;
    this.calendar = calendar;
    this.audit = audit;
    this.metrics = metrics;
    this.meters = meters;
    this.transactionManager = transactionManager;
  }

  /** The status together with the site units its shifts name. */
  public record View(Status status, Map<UUID, UnitView> sites) {}

  /**
   * The status of the caller's team.
   *
   * @param caller verified employee
   * @return the status
   */
  public View team(PeopleCaller caller) {
    return observed(
        TEAM_VIEW,
        () ->
            read(
                () -> {
                  SelfLink link = link(caller);
                  Team scope = new Team(link.employeeId());
                  Instant asOf = calendar.now().truncatedTo(ChronoUnit.MICROS);
                  Status result = compute(caller.tenant(), scope, asOf);
                  View view = new View(result, sites(caller.tenant(), result));
                  disclosed(
                      caller, TEAM_VIEW, "team-operational-status", scope, false, false, view);
                  return view;
                }));
  }

  /**
   * The status of the organization, optionally of one site and/or one legal entity.
   *
   * @param caller verified tenant administrator (exact MFA)
   * @param siteId site filter or {@code null}
   * @param legalEntityId legal-entity filter or {@code null}
   * @return the status
   */
  public View tenant(PeopleCaller caller, String siteId, String legalEntityId) {
    return observed(
        ADMIN_VIEW,
        () ->
            read(
                () -> {
                  UUID site = unit(caller.tenant(), siteId, UnitKind.SITE);
                  UUID entity = unit(caller.tenant(), legalEntityId, UnitKind.LEGAL_ENTITY);
                  Set<UUID> entitySites =
                      entity == null
                          ? Set.of()
                          : Set.copyOf(
                              directory.sitesOf(caller.tenant(), entity).stream()
                                  .map(UnitView::id)
                                  .toList());
                  Organization scope = new Organization(site, entity, entitySites);
                  Instant asOf = calendar.now().truncatedTo(ChronoUnit.MICROS);
                  Status result = compute(caller.tenant(), scope, asOf);
                  View view = new View(result, sites(caller.tenant(), result));
                  disclosed(
                      caller,
                      ADMIN_VIEW,
                      "organization-operational-status",
                      scope,
                      site != null,
                      entity != null,
                      view);
                  return view;
                }));
  }

  /** Every figure and detail list of the scope at the one instant. */
  private Status compute(TenantId tenant, Scope scope, Instant asOf) {
    Shifts shifts = status.shifts(tenant, scope, asOf);
    Waiting leave = status.leave(tenant, scope, asOf);
    Waiting timesheets = status.timesheets(tenant, scope, asOf);
    Detail<NotCheckedIn> notChecked =
        shifts.notCheckedIn() == 0
            ? Detail.of(List.of())
            : Detail.of(status.notCheckedIn(tenant, scope, asOf));
    return switch (scope) {
      case Organization org -> {
        int uncovered = status.uncovered(tenant, org, asOf);
        Detail<Shift> uncoveredShifts =
            uncovered == 0
                ? Detail.of(List.of())
                : Detail.of(status.uncoveredShifts(tenant, org, asOf));
        yield new Status(
            asOf, shifts, uncovered, leave, timesheets, notChecked, uncoveredShifts, null, null);
      }
      case Team team -> {
        Detail<OverdueLeave> overdueLeave =
            leave.overdue() == 0
                ? Detail.of(List.of())
                : Detail.of(status.overdueLeave(tenant, team, asOf));
        Detail<OverdueTimesheet> overdueTimesheets =
            timesheets.overdue() == 0
                ? Detail.of(List.of())
                : Detail.of(status.overdueTimesheets(tenant, team, asOf));
        yield new Status(
            asOf,
            shifts,
            null,
            leave,
            timesheets,
            notChecked,
            null,
            overdueLeave,
            overdueTimesheets);
      }
    };
  }

  private Map<UUID, UnitView> sites(TenantId tenant, Status result) {
    Set<UUID> ids = new LinkedHashSet<>();
    result.notCheckedIn().items().forEach(row -> ids.add(row.shift().siteId()));
    if (result.uncoveredShifts() != null) {
      result.uncoveredShifts().items().forEach(shift -> ids.add(shift.siteId()));
    }
    if (result.overdueTimesheets() != null) {
      result.overdueTimesheets().items().forEach(row -> ids.add(row.siteId()));
    }
    return ids.isEmpty() ? Map.of() : directory.resolveIds(tenant, ids);
  }

  /** A filter: one of the tenant's units of the kind, or the same 404 for anything else. */
  private UUID unit(TenantId tenant, String raw, UnitKind kind) {
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
    if (unit == null || unit.kind() != kind) {
      throw scopeNotFound();
    }
    return id;
  }

  /**
   * The fail-closed disclosure audit: the view, the counts, whether each filter exists; the scope,
   * the instant and the returned identifiers only in the digest. Never a name, number, label, date
   * or site.
   */
  private void disclosed(
      PeopleCaller caller,
      String operation,
      String view,
      Scope scope,
      boolean siteFilter,
      boolean legalEntityFilter,
      View result) {
    Status s = result.status();
    int details =
        s.notCheckedIn().items().size()
            + (s.uncoveredShifts() == null ? 0 : s.uncoveredShifts().items().size())
            + (s.overdueLeave() == null ? 0 : s.overdueLeave().items().size())
            + (s.overdueTimesheets() == null ? 0 : s.overdueTimesheets().items().size());
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", WorkScheduleService.SCHEMA_VERSION);
    metadata.put("view", view);
    metadata.put("scheduled", s.shifts().scheduled());
    metadata.put("resultCount", details);
    metadata.put("siteFilter", siteFilter);
    metadata.put("legalEntityFilter", legalEntityFilter);
    StringBuilder text =
        new StringBuilder("DIVALHR-OPERATIONAL-STATUS-DISCLOSURE\nversion=1\nview=")
            .append(view)
            .append("\nscope=")
            .append(scopeText(scope))
            .append("\nasOf=")
            .append(s.asOf())
            .append('\n');
    s.notCheckedIn()
        .items()
        .forEach(
            row ->
                text.append("notCheckedIn;slot=")
                    .append(row.shift().slotId())
                    .append(";employee=")
                    .append(row.person().id())
                    .append('\n'));
    if (s.uncoveredShifts() != null) {
      s.uncoveredShifts()
          .items()
          .forEach(shift -> text.append("uncovered;slot=").append(shift.slotId()).append('\n'));
    }
    if (s.overdueLeave() != null) {
      s.overdueLeave()
          .items()
          .forEach(
              row ->
                  text.append("leave;request=")
                      .append(row.requestId())
                      .append(";employee=")
                      .append(row.person().id())
                      .append('\n'));
    }
    if (s.overdueTimesheets() != null) {
      s.overdueTimesheets()
          .items()
          .forEach(
              row ->
                  text.append("timesheet;id=")
                      .append(row.timesheetId())
                      .append(";employee=")
                      .append(row.person().id())
                      .append('\n'));
    }
    String resource;
    UUID resourceId;
    switch (scope) {
      case Team team -> {
        resource = "employee";
        resourceId = team.managerId();
      }
      case Organization org -> {
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
      case Team team -> "team:" + team.managerId();
      case Organization org ->
          "organization:"
              + (org.siteId() == null ? "-" : org.siteId())
              + ":"
              + (org.legalEntityId() == null ? "-" : org.legalEntityId());
    };
  }

  /** Reads, then logs and counts the outcome (operation, bounded outcome and counts only). */
  private View observed(String operation, Supplier<View> work) {
    try {
      View view = counted(operation, work);
      metrics.record(operation, Outcome.LISTED);
      Status s = view.status();
      LOG.atInfo()
          .addKeyValue("operation", operation)
          .addKeyValue("scheduled", s.shifts().scheduled())
          .addKeyValue("notCheckedIn", s.shifts().notCheckedIn())
          .addKeyValue("outcome", "listed")
          .log("operational_status_read");
      return view;
    } catch (ApiException rejected) {
      Outcome outcome =
          switch (rejected.code()) {
            case EMPLOYEE_LINK_REQUIRED -> Outcome.DENIED;
            case OPERATIONAL_STATUS_SCOPE_NOT_FOUND -> Outcome.NOT_FOUND;
            default -> Outcome.VALIDATION_FAILED;
          };
      metrics.record(operation, outcome);
      LOG.atInfo()
          .addKeyValue("operation", operation)
          .addKeyValue("outcome", outcome.name().toLowerCase(Locale.ROOT))
          .addKeyValue("code", rejected.code().name())
          .log("operational_status_rejected");
      throw rejected;
    } catch (RuntimeException failure) {
      metrics.record(operation, Outcome.FAILURE);
      throw failure;
    }
  }

  /** The caller's own active link, or {@code 403 EMPLOYEE_LINK_REQUIRED}. */
  private SelfLink link(PeopleCaller caller) {
    return links
        .linkedEmployee(caller.tenant(), caller.subject())
        .orElseThrow(() -> new ApiException(ErrorCode.EMPLOYEE_LINK_REQUIRED, Map.of()));
  }

  private static ApiException scopeNotFound() {
    return new ApiException(ErrorCode.OPERATIONAL_STATUS_SCOPE_NOT_FOUND, Map.of());
  }

  /** A bounded read transaction; the result is returned only after it committed. */
  private <T> T read(Supplier<T> work) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setTimeout((int) TRANSACTION_TIMEOUT.toSeconds());
    T result = template.execute(tx -> work.get());
    if (result == null) {
      throw new IllegalStateException("read transaction returned nothing");
    }
    return result;
  }

  /** Counts link denials on the bounded self-service counter only. */
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
