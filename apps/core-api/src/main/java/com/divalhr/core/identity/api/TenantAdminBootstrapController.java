package com.divalhr.core.identity.api;

import com.divalhr.core.identity.application.TenantAdminBootstrapService;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.idempotency.IdempotentCreate;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.security.PlatformScoped;
import com.divalhr.core.platform.web.CorrelationId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * MVP-014: a platform administrator invites an organization's first tenant administrator.
 * Platform-scoped and MFA-only ({@link PlatformScoped}): subject, platform role and exact assurance
 * are checked before the path or body is bound. The target is the path's organization; the caller's
 * {@code tenant_id} claim is never read. Every response is {@code Cache-Control: private,
 * no-store}.
 */
@RestController
@RequestMapping(TenantAdminBootstrapController.PATH)
public class TenantAdminBootstrapController {

  /** Base path. */
  public static final String PATH = "/api/v1/organizations/{organizationId}/tenant-admin-bootstrap";

  private final TenantAdminBootstrapService service;

  /**
   * Creates the controller.
   *
   * @param service use case
   */
  public TenantAdminBootstrapController(TenantAdminBootstrapService service) {
    this.service = service;
  }

  /**
   * Bootstrap availability and the open bootstrap invitation.
   *
   * @param organizationId target organization
   * @return status
   */
  @Operation(operationId = "getTenantAdminBootstrap")
  @GetMapping
  @PlatformScoped(operation = TenantAdminBootstrapService.READ)
  public ResponseEntity<TenantAdminBootstrapResponse> status(
      @PathVariable("organizationId") String organizationId) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, InvitationController.CACHE_CONTROL)
        .body(service.status(organizationId));
  }

  /**
   * Invites the organization's first tenant administrator.
   *
   * @param organizationId target organization
   * @param idempotencyKey required idempotency key
   * @param request body
   * @param authentication verified platform administrator
   * @param servletRequest current request (correlation ID)
   * @return 201 with the receipt; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "createTenantAdminBootstrap")
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @PlatformScoped(operation = TenantAdminBootstrapService.CREATE)
  public ResponseEntity<InvitationReceiptResponse> create(
      @PathVariable("organizationId") String organizationId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CreateTenantAdminBootstrapRequest request,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest servletRequest) {
    IdempotentCreate.Result<InvitationReceiptResponse> result =
        service.create(
            subject(authentication),
            organizationId,
            idempotencyKey,
            request,
            correlationId(servletRequest));
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(HttpStatus.CREATED)
            .header(HttpHeaders.CACHE_CONTROL, InvitationController.CACHE_CONTROL);
    if (result.replayed()) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response.body(result.body());
  }

  /**
   * Revokes the open bootstrap invitation (idempotent by state).
   *
   * @param organizationId target organization
   * @param authentication verified platform administrator
   * @param servletRequest current request (correlation ID)
   * @return 204
   */
  @Operation(operationId = "revokeTenantAdminBootstrap")
  @PostMapping("/revoke")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PlatformScoped(operation = TenantAdminBootstrapService.REVOKE)
  public ResponseEntity<Void> revoke(
      @PathVariable("organizationId") String organizationId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest servletRequest) {
    service.revoke(subject(authentication), organizationId, correlationId(servletRequest));
    return ResponseEntity.noContent()
        .header(HttpHeaders.CACHE_CONTROL, InvitationController.CACHE_CONTROL)
        .build();
  }

  /**
   * Reissues the open bootstrap invitation.
   *
   * @param organizationId target organization
   * @param idempotencyKey required idempotency key
   * @param authentication verified platform administrator
   * @param servletRequest current request (correlation ID)
   * @return 200 with the receipt; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "resendTenantAdminBootstrap")
  @PostMapping("/resend")
  @PlatformScoped(operation = TenantAdminBootstrapService.RESEND)
  public ResponseEntity<InvitationReceiptResponse> resend(
      @PathVariable("organizationId") String organizationId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest servletRequest) {
    IdempotentOperation.Result<InvitationReceiptResponse> result =
        service.resend(
            subject(authentication), organizationId, idempotencyKey, correlationId(servletRequest));
    ResponseEntity.BodyBuilder response =
        ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, InvitationController.CACHE_CONTROL);
    if (result.replayed()) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response.body(result.body());
  }

  private static String subject(JwtAuthenticationToken authentication) {
    String subject = authentication.getToken().getSubject();
    if (subject == null || subject.isBlank()) {
      throw new ApiException(ErrorCode.ACCESS_DENIED, Map.of());
    }
    return subject;
  }

  private static String correlationId(HttpServletRequest request) {
    return String.valueOf(request.getAttribute(CorrelationId.REQUEST_ATTRIBUTE));
  }
}
