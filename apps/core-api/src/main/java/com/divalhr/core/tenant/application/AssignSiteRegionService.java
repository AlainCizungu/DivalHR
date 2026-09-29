package com.divalhr.core.tenant.application;

import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import com.divalhr.core.platform.tenancy.TenantId;
import com.divalhr.core.tenant.api.AssignSiteRegionRequest;
import com.divalhr.core.tenant.api.SiteResponse;
import com.divalhr.core.tenant.domain.Region;
import com.divalhr.core.tenant.domain.Site;
import com.divalhr.core.tenant.internal.JdbcOrganizationRepository;
import com.divalhr.core.tenant.internal.JdbcRegionRepository;
import com.divalhr.core.tenant.internal.JdbcSiteRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * MVP-002 Increment 3A use case: assigns a region to a site of the caller's verified tenant that
 * has none (first assignment only).
 *
 * <p>Inside the transaction, in the global parent-before-child lock order: the site is read without
 * a lock ({@code SITE_NOT_FOUND}), the region is read and locked {@code FOR SHARE} ({@code
 * REGION_NOT_FOUND}), then the site is re-read and locked {@code FOR UPDATE}. On the locked rows:
 *
 * <ul>
 *   <li>the site already has this region: {@code 200} with the current site; nothing is written,
 *       audited or published (outcome {@code unchanged});
 *   <li>the site has another region: {@code 409 SITE_REGION_ALREADY_ASSIGNED};
 *   <li>the region belongs to another legal entity: {@code SITE_REGION_LEGAL_ENTITY_MISMATCH};
 *   <li>the site's period is not within the region's: {@code SITE_PERIOD_OUTSIDE_REGION};
 *   <li>otherwise the site is assigned, audited ({@code site.region.assign}) and {@code
 *       tenant.site-region-assigned.v1} is written to the outbox, in the same transaction.
 * </ul>
 *
 * <p>The tenant and the legal entity are never taken from the request: the legal entity is always
 * the site's stored one.
 */
@Service
public class AssignSiteRegionService {

  /** Operation name used for idempotency scope, audit, logs and metrics. */
  public static final String OPERATION = "site.region.assign";

  /** Event type (packages/shared-contracts/schemas/event-envelope.schema.json). */
  public static final String EVENT_TYPE = "tenant.site-region-assigned.v1";

  private static final IdempotentOperation.Spec SPEC =
      new IdempotentOperation.Spec(OPERATION, "site_region", "assign", "assigned", 200);

  private final HierarchyValidator validator;
  private final IdempotentOperation operations;
  private final JdbcOrganizationRepository organizations;
  private final JdbcRegionRepository regions;
  private final JdbcSiteRepository sites;
  private final AuditRecorder audit;
  private final OutboxWriter outbox;
  private final JsonMapper json;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param validator request validator
   * @param operations shared idempotent command flow
   * @param organizations organization repository (tenant existence)
   * @param regions region repository
   * @param sites site repository
   * @param audit audit recorder
   * @param outbox outbox writer
   * @param json JSON mapper
   */
  public AssignSiteRegionService(
      HierarchyValidator validator,
      IdempotentOperation operations,
      JdbcOrganizationRepository organizations,
      JdbcRegionRepository regions,
      JdbcSiteRepository sites,
      AuditRecorder audit,
      OutboxWriter outbox,
      JsonMapper json) {
    this.validator = validator;
    this.operations = operations;
    this.organizations = organizations;
    this.regions = regions;
    this.sites = sites;
    this.audit = audit;
    this.outbox = outbox;
    this.json = json;
    this.clock = Clock.systemUTC();
  }

  /**
   * Assigns the region, or replays an earlier identical request with the same key.
   *
   * @param tenant verified tenant from the access token
   * @param actorSubject verified JWT {@code sub}
   * @param siteId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param request body
   * @param correlationId request correlation ID
   * @return the site with its region; {@code replayed} only for a replay of this key
   */
  public IdempotentOperation.Result<SiteResponse> assign(
      TenantId tenant,
      String actorSubject,
      String siteId,
      String idempotencyKey,
      AssignSiteRegionRequest request,
      String correlationId) {
    SiteRegionCommand command =
        operations.validated(SPEC, () -> validator.siteRegion(siteId, idempotencyKey, request));
    return operations.execute(
        SPEC,
        actorSubject,
        idempotencyKey,
        command.canonical(tenant),
        SiteResponse.class,
        () -> assignInTransaction(tenant, actorSubject, command, correlationId));
  }

  private IdempotentOperation.Completed<SiteResponse> assignInTransaction(
      TenantId tenant, String actorSubject, SiteRegionCommand command, String correlationId) {
    if (!organizations.exists(tenant)) {
      throw new ApiException(ErrorCode.TENANT_CONTEXT_MISSING, Map.of());
    }
    if (sites.find(tenant, command.siteId()).isEmpty()) {
      throw CreateSiteUnitService.siteNotFound();
    }
    Region region =
        regions
            .findForShare(tenant, command.regionId())
            .orElseThrow(CreateRegionService::regionNotFound);
    Site site =
        sites
            .findForUpdate(tenant, command.siteId())
            .orElseThrow(CreateSiteUnitService::siteNotFound);

    if (region.id().equals(site.regionId())) {
      // The requested state already holds: no write, no audit, no event (approved in Issue #21).
      return new IdempotentOperation.Completed<>(
          SiteResponse.from(site), site.id(), Outcome.UNCHANGED);
    }
    if (site.regionId() != null) {
      throw new ApiException(ErrorCode.SITE_REGION_ALREADY_ASSIGNED, Map.of());
    }
    if (!region.legalEntityId().equals(site.legalEntityId())) {
      throw new ApiException(
          ErrorCode.SITE_REGION_LEGAL_ENTITY_MISMATCH, Map.of("field", "regionId"));
    }
    if (!region.period().contains(site.period())) {
      throw new ApiException(ErrorCode.SITE_PERIOD_OUTSIDE_REGION, Map.of("field", "regionId"));
    }
    if (!sites.assignRegion(tenant, site.id(), region.id())) {
      throw new IllegalStateException("site region changed while the site row was locked");
    }
    Site assigned = site.withRegion(region.id());

    Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    Map<String, Object> safe = safeAttributes(assigned);
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            now,
            actorSubject,
            OPERATION,
            "site",
            assigned.id(),
            tenant.value(),
            "SUCCESS",
            correlationId,
            safe,
            Fingerprints.sha256(json.writeValueAsString(afterState(assigned)))));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("siteId", assigned.id().toString());
    data.putAll(safe);
    data.put("assignedAt", now.toString());
    outbox.append(
        new EventEnvelope(
            UUID.randomUUID().toString(),
            EVENT_TYPE,
            1,
            tenant.toString(),
            "core-api/tenant",
            assigned.id().toString(),
            now.toString(),
            correlationId,
            null,
            data));

    return new IdempotentOperation.Completed<>(
        SiteResponse.from(assigned), assigned.id(), Outcome.UPDATED);
  }

  /** Attributes safe for audit metadata and event data: ids only. */
  private static Map<String, Object> safeAttributes(Site site) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("legalEntityId", site.legalEntityId().toString());
    values.put("regionId", site.regionId().toString());
    return values;
  }

  /** The complete persisted state after the assignment, for the audit integrity hash. */
  private static Map<String, Object> afterState(Site site) {
    Map<String, Object> state = new TreeMap<>(safeAttributes(site));
    state.put("id", site.id().toString());
    state.put("tenantId", site.tenantId().toString());
    state.put("code", site.code());
    state.put("name", site.name());
    state.put("timezone", site.timezone());
    state.put("effectiveFrom", site.period().from().toString());
    if (site.period().to() != null) {
      state.put("effectiveTo", site.period().to().toString());
    }
    state.put("createdAt", site.createdAt().toString());
    state.put("createdBy", site.createdBy());
    return state;
  }
}
