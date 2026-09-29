package com.divalhr.core.tenant.api;

import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.idempotency.IdempotentCreate;
import com.divalhr.core.platform.security.TenantAdminOperation;
import com.divalhr.core.platform.tenancy.TenantContextResolver;
import com.divalhr.core.tenant.application.CreateTeamService;
import com.divalhr.core.tenant.application.TeamQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant-admin team endpoints (MVP-002 Increment 3B). Exactly one of {@code departmentId} or {@code
 * costCenterId} names the parent; the team's site is derived from it. The tenant comes only from
 * the verified token's {@code tenant_id}; no tenant identifier is read from headers, query
 * parameters or bodies.
 */
@RestController
@RequestMapping(TeamController.PATH)
public class TeamController {

  /** Full public path (server base {@code /api/v1}). */
  public static final String PATH = "/api/v1/teams";

  private final CreateTeamService creates;
  private final TeamQueryService queries;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param creates create use case
   * @param queries list queries
   * @param tenants verified tenant resolver
   */
  public TeamController(
      CreateTeamService creates, TeamQueryService queries, TenantContextResolver tenants) {
    this.creates = creates;
    this.queries = queries;
    this.tenants = tenants;
  }

  /**
   * Lists the teams of one of the caller's departments or cost centers.
   *
   * @param departmentId parent department (exactly one parent filter; validated by the service)
   * @param costCenterId parent cost center (exactly one parent filter; validated by the service)
   * @param cursor opaque cursor
   * @param limit page size (validated by the service for stable codes)
   * @return one page
   */
  @Operation(operationId = "listTeams")
  @GetMapping
  @TenantAdminOperation(operation = TeamQueryService.LIST_TEAMS)
  public TeamPage list(
      @RequestParam(name = "departmentId", required = false) String departmentId,
      @RequestParam(name = "costCenterId", required = false) String costCenterId,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit) {
    return queries.teams(tenants.current().tenantId(), departmentId, costCenterId, cursor, limit);
  }

  /**
   * Creates a team beneath exactly one of the caller's departments or cost centers.
   *
   * @param idempotencyKey required idempotency key (validated by the service for stable codes)
   * @param request body
   * @param authentication verified tenant administrator
   * @param servletRequest current request (correlation ID)
   * @return 201 with the team; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "createTeam")
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @TenantAdminOperation(operation = CreateTeamService.OPERATION)
  public ResponseEntity<TeamResponse> create(
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CreateTeamRequest request,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest servletRequest) {
    IdempotentCreate.Result<TeamResponse> result =
        creates.create(
            tenants.current().tenantId(),
            AuthenticatedCaller.subject(authentication),
            idempotencyKey,
            request,
            AuthenticatedCaller.correlationId(servletRequest));
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
    if (result.replayed()) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response.body(result.body());
  }
}
