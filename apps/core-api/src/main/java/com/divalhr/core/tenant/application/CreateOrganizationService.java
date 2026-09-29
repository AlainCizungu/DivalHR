package com.divalhr.core.tenant.application;

import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.idempotency.IdempotencyDecision;
import com.divalhr.core.platform.idempotency.IdempotencyScope;
import com.divalhr.core.platform.idempotency.IdempotencyService;
import com.divalhr.core.platform.idempotency.StoredResponse;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
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

  private static final Logger LOG = LoggerFactory.getLogger(CreateOrganizationService.class);

  private final OrganizationValidator validator;
  private final IdempotencyService idempotency;
  private final JdbcOrganizationRepository organizations;
  private final AuditRecorder audit;
  private final OutboxWriter outbox;
  private final OperationMetrics metrics;
  private final TransactionTemplate transactions;
  private final JsonMapper json;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param validator request validator
   * @param idempotency idempotency service
   * @param organizations repository
   * @param audit audit recorder
   * @param outbox outbox writer
   * @param metrics operation metrics
   * @param transactions transaction template
   * @param json JSON mapper
   */
  public CreateOrganizationService(
      OrganizationValidator validator,
      IdempotencyService idempotency,
      JdbcOrganizationRepository organizations,
      AuditRecorder audit,
      OutboxWriter outbox,
      OperationMetrics metrics,
      TransactionTemplate transactions,
      JsonMapper json) {
    this.validator = validator;
    this.idempotency = idempotency;
    this.organizations = organizations;
    this.audit = audit;
    this.outbox = outbox;
    this.metrics = metrics;
    this.transactions = transactions;
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
    CreateOrganizationCommand command;
    try {
      command = validator.validate(idempotencyKey, request);
    } catch (ApiException invalid) {
      metrics.record(OPERATION, Outcome.VALIDATION_FAILED);
      LOG.atInfo()
          .addKeyValue("operation", OPERATION)
          .addKeyValue("outcome", "validation_failed")
          .addKeyValue("code", invalid.code().name())
          .log("organization_create_rejected");
      throw invalid;
    }
    IdempotencyScope scope = new IdempotencyScope(OPERATION, actorSubject, idempotencyKey);
    String fingerprint = Fingerprints.sha256(json.writeValueAsString(command.canonical()));
    try {
      Result result =
          transactions.execute(
              status -> createInTransaction(scope, fingerprint, command, correlationId));
      if (result == null) {
        throw new IllegalStateException("transaction returned no result");
      }
      Outcome outcome = result.replayed() ? Outcome.REPLAYED : Outcome.CREATED;
      metrics.record(OPERATION, outcome);
      LOG.atInfo()
          .addKeyValue("operation", OPERATION)
          .addKeyValue("outcome", outcome.name().toLowerCase(java.util.Locale.ROOT))
          .addKeyValue("organizationId", result.organization().id())
          .log(result.replayed() ? "organization_create_replayed" : "organization_created");
      return result;
    } catch (ApiException conflict) {
      if (conflict.code() == ErrorCode.IDEMPOTENCY_KEY_REUSED) {
        metrics.record(OPERATION, Outcome.IDEMPOTENCY_CONFLICT);
        LOG.atInfo()
            .addKeyValue("operation", OPERATION)
            .addKeyValue("outcome", "idempotency_conflict")
            .log("organization_create_rejected");
      }
      throw conflict;
    }
  }

  private Result createInTransaction(
      IdempotencyScope scope,
      String fingerprint,
      CreateOrganizationCommand command,
      String correlationId) {
    IdempotencyDecision decision = idempotency.reserve(scope, fingerprint);
    if (decision instanceof IdempotencyDecision.Replay replay) {
      return new Result(json.readValue(replay.response().body(), OrganizationResponse.class), true);
    }
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
            scope.principal());
    organizations.insert(organization);

    Map<String, Object> safeConfiguration = safeConfiguration(organization);
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            now,
            scope.principal(),
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

    OrganizationResponse response = OrganizationResponse.from(organization);
    idempotency.complete(
        scope, new StoredResponse(201, json.writeValueAsString(response), organization.id()));
    return new Result(response, false);
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
