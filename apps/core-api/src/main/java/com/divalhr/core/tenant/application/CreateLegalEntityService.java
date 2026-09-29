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
import com.divalhr.core.tenant.api.CreateLegalEntityRequest;
import com.divalhr.core.tenant.api.LegalEntityResponse;
import com.divalhr.core.tenant.domain.LegalEntity;
import com.divalhr.core.tenant.internal.JdbcLegalEntityRepository;
import com.divalhr.core.tenant.internal.JdbcOrganizationRepository;
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
 * MVP-002 use case: creates a legal entity in the caller's verified tenant. Idempotency record,
 * legal entity, audit event and outbox event commit together or not at all.
 */
@Service
public class CreateLegalEntityService {

  /** Operation name used for idempotency scope, audit, logs and metrics. */
  public static final String OPERATION = "legal-entity.create";

  /** Event type (packages/shared-contracts/schemas/event-envelope.schema.json). */
  public static final String EVENT_TYPE = "tenant.legal-entity-created.v1";

  private static final IdempotentCreate.Operation SPEC =
      new IdempotentCreate.Operation(OPERATION, "legal_entity");

  private final HierarchyValidator validator;
  private final IdempotentCreate creates;
  private final JdbcOrganizationRepository organizations;
  private final JdbcLegalEntityRepository legalEntities;
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
   * @param legalEntities legal-entity repository
   * @param audit audit recorder
   * @param outbox outbox writer
   * @param json JSON mapper
   */
  public CreateLegalEntityService(
      HierarchyValidator validator,
      IdempotentCreate creates,
      JdbcOrganizationRepository organizations,
      JdbcLegalEntityRepository legalEntities,
      AuditRecorder audit,
      OutboxWriter outbox,
      JsonMapper json) {
    this.validator = validator;
    this.creates = creates;
    this.organizations = organizations;
    this.legalEntities = legalEntities;
    this.audit = audit;
    this.outbox = outbox;
    this.json = json;
    this.clock = Clock.systemUTC();
  }

  /**
   * Creates a legal entity, or replays an earlier identical request.
   *
   * @param tenant verified tenant from the access token
   * @param actorSubject verified JWT {@code sub}
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param request body
   * @param correlationId request correlation ID
   * @return created or replayed legal entity
   */
  public IdempotentCreate.Result<LegalEntityResponse> create(
      TenantId tenant,
      String actorSubject,
      String idempotencyKey,
      CreateLegalEntityRequest request,
      String correlationId) {
    LegalEntityCommand command =
        creates.validated(SPEC, () -> validator.legalEntity(idempotencyKey, request));
    return creates.execute(
        SPEC,
        actorSubject,
        idempotencyKey,
        command.canonical(tenant),
        LegalEntityResponse.class,
        () -> createInTransaction(tenant, actorSubject, command, correlationId));
  }

  private IdempotentCreate.Created<LegalEntityResponse> createInTransaction(
      TenantId tenant, String actorSubject, LegalEntityCommand command, String correlationId) {
    if (!organizations.exists(tenant)) {
      // A token whose tenant has no organization carries no usable tenant context.
      throw new ApiException(ErrorCode.TENANT_CONTEXT_MISSING, Map.of());
    }
    Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    LegalEntity entity =
        new LegalEntity(
            UUID.randomUUID(),
            tenant,
            command.code(),
            command.name(),
            command.countryCode(),
            command.period(),
            now,
            actorSubject);
    legalEntities.insert(tenant, entity);

    Map<String, Object> safe = safeAttributes(entity);
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            now,
            actorSubject,
            OPERATION,
            "legal-entity",
            entity.id(),
            tenant.value(),
            "SUCCESS",
            correlationId,
            safe,
            Fingerprints.sha256(json.writeValueAsString(afterState(entity)))));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("legalEntityId", entity.id().toString());
    data.putAll(safe);
    data.put("createdAt", entity.createdAt().toString());
    outbox.append(
        new EventEnvelope(
            UUID.randomUUID().toString(),
            EVENT_TYPE,
            1,
            tenant.toString(),
            "core-api/tenant",
            entity.id().toString(),
            now.toString(),
            correlationId,
            null,
            data));

    return new IdempotentCreate.Created<>(LegalEntityResponse.from(entity), entity.id());
  }

  /**
   * Attributes safe for audit metadata and event data: no name. An open-ended period omits {@code
   * effectiveTo}.
   */
  private static Map<String, Object> safeAttributes(LegalEntity entity) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("code", entity.code());
    values.put("countryCode", entity.countryCode());
    values.put("effectiveFrom", entity.period().from().toString());
    if (entity.period().to() != null) {
      values.put("effectiveTo", entity.period().to().toString());
    }
    return values;
  }

  private static Map<String, Object> afterState(LegalEntity entity) {
    Map<String, Object> state = new TreeMap<>(safeAttributes(entity));
    state.put("id", entity.id().toString());
    state.put("tenantId", entity.tenantId().toString());
    state.put("name", entity.name());
    state.put("createdAt", entity.createdAt().toString());
    state.put("createdBy", entity.createdBy());
    return state;
  }
}
