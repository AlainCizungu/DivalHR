package com.divalhr.core.tenant.api;

import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.idempotency.IdempotentCreate;
import com.divalhr.core.platform.security.TenantAdminOperation;
import com.divalhr.core.platform.tenancy.TenantContextResolver;
import com.divalhr.core.tenant.application.CreateSiteUnitService;
import com.divalhr.core.tenant.application.SiteUnitQueryService;
import com.divalhr.core.tenant.domain.SiteUnitKind;
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
 * Tenant-admin department endpoints (MVP-002 Increment 2). The tenant comes only from the verified
 * token's {@code tenant_id}; no tenant identifier is read from headers, query parameters or bodies.
 */
@RestController
@RequestMapping(DepartmentController.PATH)
public class DepartmentController {

  /**
   * Complete public path (docs/API-SPEC.yaml: server base {@code /api/v1} + {@code /departments}).
   */
  public static final String PATH = "/api/v1/departments";

  /** Create operation (idempotency, audit, metrics). */
  public static final String CREATE = "department.create";

  /** List operation (metrics, cursor binding). */
  public static final String LIST = "department.list";

  private static final SiteUnitKind KIND = SiteUnitKind.DEPARTMENT;

  private final CreateSiteUnitService creates;
  private final SiteUnitQueryService queries;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param creates create use case
   * @param queries list queries
   * @param tenants verified tenant resolver
   */
  public DepartmentController(
      CreateSiteUnitService creates, SiteUnitQueryService queries, TenantContextResolver tenants) {
    this.creates = creates;
    this.queries = queries;
    this.tenants = tenants;
  }

  /**
   * Lists the departments of one of the caller's sites.
   *
   * @param siteId required parent (validated by the service for stable codes)
   * @param cursor opaque cursor
   * @param limit page size (validated by the service for stable codes)
   * @return one page
   */
  @Operation(operationId = "listDepartments")
  @GetMapping
  @TenantAdminOperation(operation = LIST)
  public DepartmentPage list(
      @RequestParam(name = "siteId", required = false) String siteId,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit) {
    SiteUnitQueryService.Page page =
        queries.list(KIND, tenants.current().tenantId(), siteId, cursor, limit);
    return new DepartmentPage(
        page.rows().stream().map(DepartmentResponse::from).toList(), page.nextCursor());
  }

  /**
   * Creates a department beneath one of the caller's sites.
   *
   * @param idempotencyKey required idempotency key (validated by the service for stable codes)
   * @param request body
   * @param authentication verified tenant administrator
   * @param servletRequest current request (correlation ID)
   * @return 201 with the department; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "createDepartment")
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @TenantAdminOperation(operation = CREATE)
  public ResponseEntity<DepartmentResponse> create(
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CreateDepartmentRequest request,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest servletRequest) {
    IdempotentCreate.Result<DepartmentResponse> result =
        creates.create(
            KIND,
            tenants.current().tenantId(),
            AuthenticatedCaller.subject(authentication),
            idempotencyKey,
            request,
            AuthenticatedCaller.correlationId(servletRequest),
            DepartmentResponse.class,
            DepartmentResponse::from);
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
    if (result.replayed()) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response.body(result.body());
  }
}
