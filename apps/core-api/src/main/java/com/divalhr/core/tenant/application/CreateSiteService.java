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
import com.divalhr.core.tenant.api.CreateSiteRequest;
import com.divalhr.core.tenant.api.SiteResponse;
import com.divalhr.core.tenant.domain.LegalEntity;
import com.divalhr.core.tenant.domain.Site;
import com.divalhr.core.tenant.domain.SupportedConfiguration;
import com.divalhr.core.tenant.domain.SupportedConfiguration.CountryRules;
import com.divalhr.core.tenant.internal.JdbcLegalEntityRepository;
import com.divalhr.core.tenant.internal.JdbcOrganizationRepository;
import com.divalhr.core.tenant.internal.JdbcSiteRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * MVP-002 use case: creates a site beneath a legal entity of the caller's verified tenant.
 *
 * <p>Inside the transaction: the parent is read with the tenant predicate and locked {@code FOR
 * SHARE} (missing and foreign parents give the same {@code LEGAL_ENTITY_NOT_FOUND}), then the time
 * zone is checked against the parent's country and the period against the parent's period. The
 * database trigger repeats the containment check.
 */
@Service
public class CreateSiteService {

  /** Operation name used for idempotency scope, audit, logs and metrics. */
  public static final String OPERATION = "site.create";

  /** Event type (packages/shared-contracts/schemas/event-envelope.schema.json). */
  public static final String EVENT_TYPE = "tenant.site-created.v1";

  private static final IdempotentCreate.Operation SPEC =
      new IdempotentCreate.Operation(OPERATION, "site");

  private final HierarchyValidator validator;
  private final IdempotentCreate creates;
  private final JdbcOrganizationRepository organizations;
  private final JdbcLegalEntityRepository legalEntities;
  private final JdbcSiteRepository sites;
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
   * @param legalEntities legal-entity repository (parent lookup)
   * @param sites site repository
   * @param audit audit recorder
   * @param outbox outbox writer
   * @param json JSON mapper
   */
  public CreateSiteService(
      HierarchyValidator validator,
      IdempotentCreate creates,
      JdbcOrganizationRepository organizations,
      JdbcLegalEntityRepository legalEntities,
      JdbcSiteRepository sites,
      AuditRecorder audit,
      OutboxWriter outbox,
      JsonMapper json) {
    this.validator = validator;
    this.creates = creates;
    this.organizations = organizations;
    this.legalEntities = legalEntities;
    this.sites = sites;
    this.audit = audit;
    this.outbox = outbox;
    this.json = json;
    this.clock = Clock.systemUTC();
  }

  /**
   * Creates a site, or replays an earlier identical request.
   *
   * @param tenant verified tenant from the access token
   * @param actorSubject verified JWT {@code sub}
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param request body
   * @param correlationId request correlation ID
   * @return created or replayed site
   */
  public IdempotentCreate.Result<SiteResponse> create(
      TenantId tenant,
      String actorSubject,
      String idempotencyKey,
      CreateSiteRequest request,
      String correlationId) {
    SiteCommand command = creates.validated(SPEC, () -> validator.site(idempotencyKey, request));
    return creates.execute(
        SPEC,
        actorSubject,
        idempotencyKey,
        command.canonical(tenant),
        SiteResponse.class,
        () -> createInTransaction(tenant, actorSubject, command, correlationId));
  }

  private IdempotentCreate.Created<SiteResponse> createInTransaction(
      TenantId tenant, String actorSubject, SiteCommand command, String correlationId) {
    if (!organizations.exists(tenant)) {
      throw new ApiException(ErrorCode.TENANT_CONTEXT_MISSING, Map.of());
    }
    LegalEntity parent =
        legalEntities
            .findForShare(tenant, command.legalEntityId())
            .orElseThrow(CreateSiteService::parentNotFound);
    List<String> zones =
        SupportedConfiguration.country(parent.countryCode())
            .map(CountryRules::timezones)
            .orElse(List.of());
    if (!zones.contains(command.timezone())) {
      throw new ApiException(
          ErrorCode.TIMEZONE_NOT_SUPPORTED,
          Map.of("field", "timezone", "supported", List.copyOf(zones)));
    }
    if (!parent.period().contains(command.period())) {
      String field =
          command.period().from().isBefore(parent.period().from())
              ? "effectiveFrom"
              : "effectiveTo";
      throw new ApiException(ErrorCode.SITE_PERIOD_OUTSIDE_LEGAL_ENTITY, Map.of("field", field));
    }

    Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    Site site =
        new Site(
            UUID.randomUUID(),
            tenant,
            parent.id(),
            command.code(),
            command.name(),
            command.timezone(),
            command.period(),
            now,
            actorSubject);
    sites.insert(tenant, site);

    Map<String, Object> safe = safeAttributes(site);
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            now,
            actorSubject,
            OPERATION,
            "site",
            site.id(),
            tenant.value(),
            "SUCCESS",
            correlationId,
            safe,
            Fingerprints.sha256(json.writeValueAsString(afterState(site)))));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("siteId", site.id().toString());
    data.putAll(safe);
    data.put("createdAt", site.createdAt().toString());
    outbox.append(
        new EventEnvelope(
            UUID.randomUUID().toString(),
            EVENT_TYPE,
            1,
            tenant.toString(),
            "core-api/tenant",
            site.id().toString(),
            now.toString(),
            correlationId,
            null,
            data));

    return new IdempotentCreate.Created<>(SiteResponse.from(site), site.id());
  }

  /**
   * The response for a missing or foreign parent. Identical in both cases; never echoes the id.
   *
   * @return the exception
   */
  static ApiException parentNotFound() {
    return new ApiException(ErrorCode.LEGAL_ENTITY_NOT_FOUND, Map.of("field", "legalEntityId"));
  }

  /** Attributes safe for audit metadata and event data: no name. */
  private static Map<String, Object> safeAttributes(Site site) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("legalEntityId", site.legalEntityId().toString());
    values.put("code", site.code());
    values.put("timezone", site.timezone());
    values.put("effectiveFrom", site.period().from().toString());
    if (site.period().to() != null) {
      values.put("effectiveTo", site.period().to().toString());
    }
    return values;
  }

  private static Map<String, Object> afterState(Site site) {
    Map<String, Object> state = new TreeMap<>(safeAttributes(site));
    state.put("id", site.id().toString());
    state.put("tenantId", site.tenantId().toString());
    state.put("name", site.name());
    state.put("createdAt", site.createdAt().toString());
    state.put("createdBy", site.createdBy());
    return state;
  }
}
