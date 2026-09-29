package com.divalhr.core.tenant.application;

import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.idempotency.IdempotentCreate;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import com.divalhr.core.platform.tenancy.TenantId;
import com.divalhr.core.tenant.api.SiteUnitRequest;
import com.divalhr.core.tenant.domain.Site;
import com.divalhr.core.tenant.domain.SiteUnit;
import com.divalhr.core.tenant.domain.SiteUnitKind;
import com.divalhr.core.tenant.internal.JdbcOrganizationRepository;
import com.divalhr.core.tenant.internal.JdbcSiteRepository;
import com.divalhr.core.tenant.internal.JdbcSiteUnitRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * MVP-002 Increment 2 use case: creates a department or cost center beneath a site of the caller's
 * verified tenant. Idempotency record, entity, audit event and outbox event commit together or not
 * at all.
 *
 * <p>Inside the transaction: the organization must exist; the site is read with the tenant
 * predicate and locked {@code FOR SHARE} (missing and foreign sites give the same {@code
 * SITE_NOT_FOUND}); the period must lie within the site's. The database trigger repeats the
 * containment check.
 */
@Service
public class CreateSiteUnitService {

  private final HierarchyValidator validator;
  private final IdempotentCreate creates;
  private final JdbcOrganizationRepository organizations;
  private final JdbcSiteRepository sites;
  private final JdbcSiteUnitRepository units;
  private final AuditRecorder audit;
  private final OutboxWriter outbox;
  private final JsonMapper json;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param validator request validator
   * @param creates shared idempotent-create flow
   * @param organizations organization repository (tenant existence)
   * @param sites site repository (parent lookup)
   * @param units department and cost-center repository
   * @param audit audit recorder
   * @param outbox outbox writer
   * @param json JSON mapper
   */
  public CreateSiteUnitService(
      HierarchyValidator validator,
      IdempotentCreate creates,
      JdbcOrganizationRepository organizations,
      JdbcSiteRepository sites,
      JdbcSiteUnitRepository units,
      AuditRecorder audit,
      OutboxWriter outbox,
      JsonMapper json) {
    this.validator = validator;
    this.creates = creates;
    this.organizations = organizations;
    this.sites = sites;
    this.units = units;
    this.audit = audit;
    this.outbox = outbox;
    this.json = json;
    this.clock = Clock.systemUTC();
  }

  /**
   * Creates a department or cost center, or replays an earlier identical request.
   *
   * @param kind which aggregate
   * @param tenant verified tenant from the access token
   * @param actorSubject verified JWT {@code sub}
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param request body
   * @param correlationId request correlation ID
   * @param responseType response body type (for replay)
   * @param toResponse maps the created aggregate to its response
   * @param <R> response type
   * @return created or replayed response
   */
  public <R> IdempotentCreate.Result<R> create(
      SiteUnitKind kind,
      TenantId tenant,
      String actorSubject,
      String idempotencyKey,
      SiteUnitRequest request,
      String correlationId,
      Class<R> responseType,
      Function<SiteUnit, R> toResponse) {
    IdempotentCreate.Operation spec =
        new IdempotentCreate.Operation(kind.createOperation(), kind.resource().replace('-', '_'));
    SiteUnitCommand command =
        creates.validated(spec, () -> validator.siteUnit(idempotencyKey, request));
    return creates.execute(
        spec,
        actorSubject,
        idempotencyKey,
        command.canonical(tenant),
        responseType,
        () -> {
          SiteUnit unit = createInTransaction(kind, tenant, actorSubject, command, correlationId);
          return new IdempotentCreate.Created<>(toResponse.apply(unit), unit.id());
        });
  }

  private SiteUnit createInTransaction(
      SiteUnitKind kind,
      TenantId tenant,
      String actorSubject,
      SiteUnitCommand command,
      String correlationId) {
    if (!organizations.exists(tenant)) {
      throw new ApiException(ErrorCode.TENANT_CONTEXT_MISSING, Map.of());
    }
    Site site =
        sites
            .findForShare(tenant, command.siteId())
            .orElseThrow(CreateSiteUnitService::siteNotFound);
    if (!site.period().contains(command.period())) {
      String field =
          command.period().from().isBefore(site.period().from()) ? "effectiveFrom" : "effectiveTo";
      throw new ApiException(periodOutsideSite(kind), Map.of("field", field));
    }

    Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    SiteUnit unit =
        SiteUnit.of(
            kind,
            UUID.randomUUID(),
            tenant,
            site.id(),
            command.code(),
            command.name(),
            command.period(),
            now,
            actorSubject);
    units.insert(tenant, unit);

    Map<String, Object> safe = safeAttributes(unit);
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            now,
            actorSubject,
            kind.createOperation(),
            kind.resource(),
            unit.id(),
            tenant.value(),
            "SUCCESS",
            correlationId,
            safe,
            Fingerprints.sha256(json.writeValueAsString(afterState(unit)))));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put(kind.idField(), unit.id().toString());
    data.putAll(safe);
    data.put("createdAt", unit.createdAt().toString());
    outbox.append(
        new EventEnvelope(
            UUID.randomUUID().toString(),
            kind.createdEventType(),
            1,
            tenant.toString(),
            "core-api/tenant",
            unit.id().toString(),
            now.toString(),
            correlationId,
            null,
            data));
    return unit;
  }

  /**
   * The response for a missing or foreign site. Identical in both cases; never echoes the id.
   *
   * @return the exception
   */
  static ApiException siteNotFound() {
    return new ApiException(ErrorCode.SITE_NOT_FOUND, Map.of("field", "siteId"));
  }

  private static ErrorCode periodOutsideSite(SiteUnitKind kind) {
    return switch (kind) {
      case DEPARTMENT -> ErrorCode.DEPARTMENT_PERIOD_OUTSIDE_SITE;
      case COST_CENTER -> ErrorCode.COST_CENTER_PERIOD_OUTSIDE_SITE;
    };
  }

  /**
   * Attributes safe for audit metadata and event data: no name. An open-ended period omits {@code
   * effectiveTo} (approved convention from PR #16).
   */
  private static Map<String, Object> safeAttributes(SiteUnit unit) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("siteId", unit.siteId().toString());
    values.put("code", unit.code());
    values.put("effectiveFrom", unit.period().from().toString());
    if (unit.period().to() != null) {
      values.put("effectiveTo", unit.period().to().toString());
    }
    return values;
  }

  private static Map<String, Object> afterState(SiteUnit unit) {
    Map<String, Object> state = new TreeMap<>(safeAttributes(unit));
    state.put("id", unit.id().toString());
    state.put("tenantId", unit.tenantId().toString());
    state.put("name", unit.name());
    state.put("createdAt", unit.createdAt().toString());
    state.put("createdBy", unit.createdBy());
    return state;
  }
}
