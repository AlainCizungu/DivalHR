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
import com.divalhr.core.tenant.api.CreateRegionRequest;
import com.divalhr.core.tenant.api.RegionResponse;
import com.divalhr.core.tenant.domain.LegalEntity;
import com.divalhr.core.tenant.domain.Region;
import com.divalhr.core.tenant.internal.JdbcLegalEntityRepository;
import com.divalhr.core.tenant.internal.JdbcOrganizationRepository;
import com.divalhr.core.tenant.internal.JdbcRegionRepository;
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
 * MVP-002 Increment 3A use case: creates a region beneath a legal entity of the caller's verified
 * tenant.
 *
 * <p>Inside the transaction: the parent is read with the tenant predicate and locked {@code FOR
 * SHARE} (missing and foreign parents give the same {@code LEGAL_ENTITY_NOT_FOUND}), then the
 * period is checked against the parent's period. The database trigger repeats the containment check
 * and the unique index decides duplicate codes.
 */
@Service
public class CreateRegionService {

  /** Operation name used for idempotency scope, audit, logs and metrics. */
  public static final String OPERATION = "region.create";

  /** Event type (packages/shared-contracts/schemas/event-envelope.schema.json). */
  public static final String EVENT_TYPE = "tenant.region-created.v1";

  private static final IdempotentCreate.Operation SPEC =
      new IdempotentCreate.Operation(OPERATION, "region");

  private final HierarchyValidator validator;
  private final IdempotentCreate creates;
  private final JdbcOrganizationRepository organizations;
  private final JdbcLegalEntityRepository legalEntities;
  private final JdbcRegionRepository regions;
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
   * @param regions region repository
   * @param audit audit recorder
   * @param outbox outbox writer
   * @param json JSON mapper
   */
  public CreateRegionService(
      HierarchyValidator validator,
      IdempotentCreate creates,
      JdbcOrganizationRepository organizations,
      JdbcLegalEntityRepository legalEntities,
      JdbcRegionRepository regions,
      AuditRecorder audit,
      OutboxWriter outbox,
      JsonMapper json) {
    this.validator = validator;
    this.creates = creates;
    this.organizations = organizations;
    this.legalEntities = legalEntities;
    this.regions = regions;
    this.audit = audit;
    this.outbox = outbox;
    this.json = json;
    this.clock = Clock.systemUTC();
  }

  /**
   * Creates a region, or replays an earlier identical request.
   *
   * @param tenant verified tenant from the access token
   * @param actorSubject verified JWT {@code sub}
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param request body
   * @param correlationId request correlation ID
   * @return created or replayed region
   */
  public IdempotentCreate.Result<RegionResponse> create(
      TenantId tenant,
      String actorSubject,
      String idempotencyKey,
      CreateRegionRequest request,
      String correlationId) {
    RegionCommand command =
        creates.validated(SPEC, () -> validator.region(idempotencyKey, request));
    return creates.execute(
        SPEC,
        actorSubject,
        idempotencyKey,
        command.canonical(tenant),
        RegionResponse.class,
        () -> createInTransaction(tenant, actorSubject, command, correlationId));
  }

  private IdempotentCreate.Created<RegionResponse> createInTransaction(
      TenantId tenant, String actorSubject, RegionCommand command, String correlationId) {
    if (!organizations.exists(tenant)) {
      throw new ApiException(ErrorCode.TENANT_CONTEXT_MISSING, Map.of());
    }
    LegalEntity parent =
        legalEntities
            .findForShare(tenant, command.legalEntityId())
            .orElseThrow(CreateSiteService::parentNotFound);
    if (!parent.period().contains(command.period())) {
      String field =
          command.period().from().isBefore(parent.period().from())
              ? "effectiveFrom"
              : "effectiveTo";
      throw new ApiException(ErrorCode.REGION_PERIOD_OUTSIDE_LEGAL_ENTITY, Map.of("field", field));
    }

    Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    Region region =
        new Region(
            UUID.randomUUID(),
            tenant,
            parent.id(),
            command.code(),
            command.name(),
            command.period(),
            now,
            actorSubject);
    regions.insert(tenant, region);

    Map<String, Object> safe = safeAttributes(region);
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            now,
            actorSubject,
            OPERATION,
            "region",
            region.id(),
            tenant.value(),
            "SUCCESS",
            correlationId,
            safe,
            Fingerprints.sha256(json.writeValueAsString(afterState(region)))));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("regionId", region.id().toString());
    data.putAll(safe);
    data.put("createdAt", region.createdAt().toString());
    outbox.append(
        new EventEnvelope(
            UUID.randomUUID().toString(),
            EVENT_TYPE,
            1,
            tenant.toString(),
            "core-api/tenant",
            region.id().toString(),
            now.toString(),
            correlationId,
            null,
            data));

    return new IdempotentCreate.Created<>(RegionResponse.from(region), region.id());
  }

  /**
   * The response for a missing or foreign region. Identical in both cases; never echoes the id.
   *
   * @return the exception
   */
  static ApiException regionNotFound() {
    return new ApiException(ErrorCode.REGION_NOT_FOUND, Map.of("field", "regionId"));
  }

  /** Attributes safe for audit metadata and event data: no name. */
  private static Map<String, Object> safeAttributes(Region region) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("legalEntityId", region.legalEntityId().toString());
    values.put("code", region.code());
    values.put("effectiveFrom", region.period().from().toString());
    if (region.period().to() != null) {
      values.put("effectiveTo", region.period().to().toString());
    }
    return values;
  }

  private static Map<String, Object> afterState(Region region) {
    Map<String, Object> state = new TreeMap<>(safeAttributes(region));
    state.put("id", region.id().toString());
    state.put("tenantId", region.tenantId().toString());
    state.put("name", region.name());
    state.put("createdAt", region.createdAt().toString());
    state.put("createdBy", region.createdBy());
    return state;
  }
}
