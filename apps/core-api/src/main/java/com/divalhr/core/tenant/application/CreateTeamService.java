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
import com.divalhr.core.tenant.api.CreateTeamRequest;
import com.divalhr.core.tenant.api.TeamResponse;
import com.divalhr.core.tenant.domain.Team;
import com.divalhr.core.tenant.domain.TeamParent;
import com.divalhr.core.tenant.domain.TeamParentKind;
import com.divalhr.core.tenant.internal.JdbcOrganizationRepository;
import com.divalhr.core.tenant.internal.JdbcTeamRepository;
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
 * MVP-002 Increment 3B use case: creates a team beneath exactly one department or cost center of
 * the caller's verified tenant.
 *
 * <p>Inside the transaction: the parent is read with the tenant predicate and locked {@code FOR
 * SHARE} (missing and foreign parents give the same {@code DEPARTMENT_NOT_FOUND} or {@code
 * COST_CENTER_NOT_FOUND}); the team's site is taken from that locked row, never from the request;
 * then the period is checked against the parent's. The database repeats the containment check and
 * proves tenant, site and parent belong together.
 */
@Service
public class CreateTeamService {

  /** Operation name used for idempotency scope, audit, logs and metrics. */
  public static final String OPERATION = "team.create";

  /** Event type (packages/shared-contracts/schemas/event-envelope.schema.json). */
  public static final String EVENT_TYPE = "tenant.team-created.v1";

  private static final IdempotentCreate.Operation SPEC =
      new IdempotentCreate.Operation(OPERATION, "team");

  private final HierarchyValidator validator;
  private final IdempotentCreate creates;
  private final JdbcOrganizationRepository organizations;
  private final JdbcTeamRepository teams;
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
   * @param teams team repository (parent lookup and insert)
   * @param audit audit recorder
   * @param outbox outbox writer
   * @param json JSON mapper
   */
  public CreateTeamService(
      HierarchyValidator validator,
      IdempotentCreate creates,
      JdbcOrganizationRepository organizations,
      JdbcTeamRepository teams,
      AuditRecorder audit,
      OutboxWriter outbox,
      JsonMapper json) {
    this.validator = validator;
    this.creates = creates;
    this.organizations = organizations;
    this.teams = teams;
    this.audit = audit;
    this.outbox = outbox;
    this.json = json;
    this.clock = Clock.systemUTC();
  }

  /**
   * Creates a team, or replays an earlier identical request.
   *
   * @param tenant verified tenant from the access token
   * @param actorSubject verified JWT {@code sub}
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param request body
   * @param correlationId request correlation ID
   * @return created or replayed team
   */
  public IdempotentCreate.Result<TeamResponse> create(
      TenantId tenant,
      String actorSubject,
      String idempotencyKey,
      CreateTeamRequest request,
      String correlationId) {
    TeamCommand command = creates.validated(SPEC, () -> validator.team(idempotencyKey, request));
    return creates.execute(
        SPEC,
        actorSubject,
        idempotencyKey,
        command.canonical(tenant),
        TeamResponse.class,
        () -> createInTransaction(tenant, actorSubject, command, correlationId));
  }

  private IdempotentCreate.Created<TeamResponse> createInTransaction(
      TenantId tenant, String actorSubject, TeamCommand command, String correlationId) {
    if (!organizations.exists(tenant)) {
      throw new ApiException(ErrorCode.TENANT_CONTEXT_MISSING, Map.of());
    }
    TeamParent parent =
        teams
            .findParentForShare(tenant, command.parentKind(), command.parentId())
            .orElseThrow(() -> parentNotFound(command.parentKind()));
    if (!parent.period().contains(command.period())) {
      String field =
          command.period().from().isBefore(parent.period().from())
              ? "effectiveFrom"
              : "effectiveTo";
      throw new ApiException(periodOutside(parent.kind()), Map.of("field", field));
    }

    Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    Team team =
        new Team(
            UUID.randomUUID(),
            tenant,
            parent.siteId(),
            parent.kind(),
            parent.id(),
            command.code(),
            command.name(),
            command.period(),
            now,
            actorSubject);
    teams.insert(tenant, team);

    Map<String, Object> safe = safeAttributes(team);
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            now,
            actorSubject,
            OPERATION,
            "team",
            team.id(),
            tenant.value(),
            "SUCCESS",
            correlationId,
            safe,
            Fingerprints.sha256(json.writeValueAsString(afterState(team)))));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("teamId", team.id().toString());
    data.putAll(safe);
    data.put("createdAt", team.createdAt().toString());
    outbox.append(
        new EventEnvelope(
            UUID.randomUUID().toString(),
            EVENT_TYPE,
            1,
            tenant.toString(),
            "core-api/tenant",
            team.id().toString(),
            now.toString(),
            correlationId,
            null,
            data));

    return new IdempotentCreate.Created<>(TeamResponse.from(team), team.id());
  }

  /**
   * The response for a missing or foreign parent. Identical in both cases; never echoes the id.
   *
   * @param kind parent type
   * @return the exception
   */
  static ApiException parentNotFound(TeamParentKind kind) {
    return switch (kind) {
      case DEPARTMENT ->
          new ApiException(ErrorCode.DEPARTMENT_NOT_FOUND, Map.of("field", "departmentId"));
      case COST_CENTER ->
          new ApiException(ErrorCode.COST_CENTER_NOT_FOUND, Map.of("field", "costCenterId"));
    };
  }

  private static ErrorCode periodOutside(TeamParentKind kind) {
    return switch (kind) {
      case DEPARTMENT -> ErrorCode.TEAM_PERIOD_OUTSIDE_DEPARTMENT;
      case COST_CENTER -> ErrorCode.TEAM_PERIOD_OUTSIDE_COST_CENTER;
    };
  }

  /** The API/event field that names the selected parent. */
  private static String parentField(TeamParentKind kind) {
    return switch (kind) {
      case DEPARTMENT -> "departmentId";
      case COST_CENTER -> "costCenterId";
    };
  }

  /** Attributes safe for audit metadata and event data: ids, type, code and dates; no name. */
  private static Map<String, Object> safeAttributes(Team team) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("siteId", team.siteId().toString());
    values.put("parentType", team.parentKind().wireName());
    values.put(parentField(team.parentKind()), team.parentId().toString());
    values.put("code", team.code());
    values.put("effectiveFrom", team.period().from().toString());
    if (team.period().to() != null) {
      values.put("effectiveTo", team.period().to().toString());
    }
    return values;
  }

  private static Map<String, Object> afterState(Team team) {
    Map<String, Object> state = new TreeMap<>(safeAttributes(team));
    state.put("id", team.id().toString());
    state.put("tenantId", team.tenantId().toString());
    state.put("name", team.name());
    state.put("createdAt", team.createdAt().toString());
    state.put("createdBy", team.createdBy());
    return state;
  }
}
