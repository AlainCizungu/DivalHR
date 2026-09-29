package com.divalhr.core.tenant.application;

import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.idempotency.IdempotentCreate;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import com.divalhr.core.tenant.api.CreateOrganizationRequest;
import com.divalhr.core.tenant.api.OrganizationResponse;
import com.divalhr.core.tenant.domain.Organization;
import com.divalhr.core.tenant.domain.OrganizationStatus;
import com.divalhr.core.tenant.internal.JdbcOrganizationRepository;
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
 * MVP-001 use case. Idempotency record, organization, audit event and outbox event are written in
 * one transaction: all commit or none do.
 */
@Service
public class CreateOrganizationService {

  /** Operation name used for idempotency scope, audit, logs and metrics. */
  public static final String OPERATION = "organization.create";

  /** Event type (packages/shared-contracts/schemas/event-envelope.schema.json). */
  public static final String EVENT_TYPE = "tenant.organization-created.v1";

  private static final IdempotentCreate.Operation SPEC =
      new IdempotentCreate.Operation(OPERATION, "organization");

  private final OrganizationValidator validator;
  private final IdempotentCreate creates;
  private final JdbcOrganizationRepository organizations;
  private final AuditRecorder audit;
  private final OutboxWriter outbox;
  private final JsonMapper json;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param validator request validator
   * @param creates shared idempotent-create flow
   * @param organizations repository
   * @param audit audit recorder
   * @param outbox outbox writer
   * @param json JSON mapper
   */
  public CreateOrganizationService(
      OrganizationValidator validator,
      IdempotentCreate creates,
      JdbcOrganizationRepository organizations,
      AuditRecorder audit,
      OutboxWriter outbox,
      JsonMapper json) {
    this.validator = validator;
    this.creates = creates;
    this.organizations = organizations;
    this.audit = audit;
    this.outbox = outbox;
    this.json = json;
    this.clock = Clock.systemUTC();
  }

  /**
   * Result of a create call.
   *
   * @param organization response body
   * @param replayed whether an earlier response was replayed
   */
  public record Result(OrganizationResponse organization, boolean replayed) {}

  /**
   * Creates an organization, or replays an earlier identical request.
   *
   * @param actorSubject verified JWT {@code sub} of a platform administrator
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param request request body
   * @param correlationId request correlation ID
   * @return created or replayed organization
   */
  public Result create(
      String actorSubject,
      String idempotencyKey,
      CreateOrganizationRequest request,
      String correlationId) {
    CreateOrganizationCommand command =
        creates.validated(SPEC, () -> validator.validate(idempotencyKey, request));
    IdempotentCreate.Result<OrganizationResponse> result =
        creates.execute(
            SPEC,
            actorSubject,
            idempotencyKey,
            command.canonical(),
            OrganizationResponse.class,
            () -> createInTransaction(actorSubject, command, correlationId));
    return new Result(result.body(), result.replayed());
  }

  private IdempotentCreate.Created<OrganizationResponse> createInTransaction(
      String actorSubject, CreateOrganizationCommand command, String correlationId) {
    // Microsecond precision matches PostgreSQL timestamptz, so replayed bodies are identical.
    Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    Organization organization =
        new Organization(
            UUID.randomUUID(),
            command.name(),
            command.countryCode(),
            command.defaultLocale(),
            command.timezone(),
            command.currencies(),
            OrganizationStatus.ACTIVE,
            now,
            actorSubject);
    organizations.insert(organization);

    Map<String, Object> safeConfiguration = safeConfiguration(organization);
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            now,
            actorSubject,
            OPERATION,
            "organization",
            organization.id(),
            organization.id(),
            "SUCCESS",
            correlationId,
            safeConfiguration,
            Fingerprints.sha256(json.writeValueAsString(afterState(organization)))));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("organizationId", organization.id().toString());
    data.putAll(safeConfiguration);
    data.put("status", organization.status().name());
    data.put("createdAt", organization.createdAt().toString());
    outbox.append(
        new EventEnvelope(
            UUID.randomUUID().toString(),
            EVENT_TYPE,
            1,
            organization.id().toString(),
            "core-api/tenant",
            organization.id().toString(),
            now.toString(),
            correlationId,
            null,
            data));

    return new IdempotentCreate.Created<>(
        OrganizationResponse.from(organization), organization.id());
  }

  /** Configuration values that are safe for audit metadata and event data (no name). */
  private static Map<String, Object> safeConfiguration(Organization organization) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("countryCode", organization.countryCode());
    values.put("defaultLocale", organization.defaultLocale());
    values.put("timezone", organization.timezone());
    values.put("currencies", List.copyOf(organization.currencies()));
    return values;
  }

  private static Map<String, Object> afterState(Organization organization) {
    Map<String, Object> state = new TreeMap<>(safeConfiguration(organization));
    state.put("id", organization.id().toString());
    state.put("name", organization.name());
    state.put("status", organization.status().name());
    state.put("createdAt", organization.createdAt().toString());
    state.put("createdBy", organization.createdBy());
    return state;
  }
}
