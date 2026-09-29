package com.divalhr.core.tenant.api;

import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.idempotency.IdempotentCreate;
import com.divalhr.core.platform.security.TenantAdminOperation;
import com.divalhr.core.platform.tenancy.TenantContextResolver;
import com.divalhr.core.tenant.application.CreateSiteService;
import com.divalhr.core.tenant.application.HierarchyQueryService;
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
 * Tenant-admin site endpoints (MVP-002). The tenant comes only from the verified token's {@code
 * tenant_id}; no tenant identifier is read from headers, query parameters or bodies.
 */
@RestController
@RequestMapping("/api/v1/sites")
public class SiteController {

  private final CreateSiteService creates;
  private final HierarchyQueryService queries;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param creates create use case
   * @param queries list queries
   * @param tenants verified tenant resolver
   */
  public SiteController(
      CreateSiteService creates, HierarchyQueryService queries, TenantContextResolver tenants) {
    this.creates = creates;
    this.queries = queries;
    this.tenants = tenants;
  }

  /**
   * Lists the sites of one of the caller's legal entities.
   *
   * @param legalEntityId required parent (validated by the service for stable codes)
   * @param cursor opaque cursor
   * @param limit page size (validated by the service for stable codes)
   * @return one page
   */
  @Operation(operationId = "listSites")
  @GetMapping
  @TenantAdminOperation(operation = HierarchyQueryService.LIST_SITES)
  public SitePage list(
      @RequestParam(name = "legalEntityId", required = false) String legalEntityId,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit) {
    return queries.sites(tenants.current().tenantId(), legalEntityId, cursor, limit);
  }

  /**
   * Creates a site beneath one of the caller's legal entities.
   *
   * @param idempotencyKey required idempotency key (validated by the service for stable codes)
   * @param request body
   * @param authentication verified tenant administrator
   * @param servletRequest current request (correlation ID)
   * @return 201 with the site; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "createSite")
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @TenantAdminOperation(operation = CreateSiteService.OPERATION)
  public ResponseEntity<SiteResponse> create(
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CreateSiteRequest request,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest servletRequest) {
    IdempotentCreate.Result<SiteResponse> result =
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
