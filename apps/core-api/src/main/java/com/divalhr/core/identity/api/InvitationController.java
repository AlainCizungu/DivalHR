package com.divalhr.core.identity.api;

import com.divalhr.core.identity.application.CreateInvitationService;
import com.divalhr.core.identity.application.InvitationQueryService;
import com.divalhr.core.identity.application.ResendInvitationService;
import com.divalhr.core.identity.application.RevokeInvitationService;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.idempotency.IdempotentCreate;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.security.TenantAdminOperation;
import com.divalhr.core.platform.tenancy.TenantContextResolver;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant-admin invitation endpoints (MVP-010). The tenant comes only from the verified token's
 * {@code tenant_id}. Responses may contain email addresses and are {@code Cache-Control: private,
 * no-store}.
 */
@RestController
@RequestMapping(InvitationController.PATH)
public class InvitationController {

  /** Full public path (server base {@code /api/v1}). */
  public static final String PATH = "/api/v1/invitations";

  /** Cache policy of every administrative invitation response. */
  public static final String CACHE_CONTROL = "private, no-store";

  private final CreateInvitationService creates;
  private final ResendInvitationService resends;
  private final RevokeInvitationService revokes;
  private final InvitationQueryService queries;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param creates create use case
   * @param resends resend use case
   * @param revokes revoke use case
   * @param queries list query
   * @param tenants verified tenant resolver
   */
  public InvitationController(
      CreateInvitationService creates,
      ResendInvitationService resends,
      RevokeInvitationService revokes,
      InvitationQueryService queries,
      TenantContextResolver tenants) {
    this.creates = creates;
    this.resends = resends;
    this.revokes = revokes;
    this.queries = queries;
    this.tenants = tenants;
  }

  /**
   * Lists the caller's tenant's invitations.
   *
   * @param status optional status filter (validated by the service)
   * @param cursor opaque cursor
   * @param limit page size (validated by the service)
   * @return one page
   */
  @Operation(operationId = "listInvitations")
  @GetMapping
  @TenantAdminOperation(operation = InvitationQueryService.LIST_INVITATIONS)
  public ResponseEntity<InvitationPageResponse> list(
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(queries.list(tenants.current().tenantId(), status, cursor, limit));
  }

  /**
   * Invites a person by email with one tenant role.
   *
   * @param idempotencyKey required idempotency key (validated by the service)
   * @param request body
   * @param authentication verified tenant administrator
   * @param servletRequest current request (correlation ID)
   * @return 201 with the receipt; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "createInvitation")
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @TenantAdminOperation(operation = CreateInvitationService.OPERATION)
  public ResponseEntity<InvitationReceiptResponse> create(
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CreateInvitationRequest request,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest servletRequest) {
    IdempotentCreate.Result<InvitationReceiptResponse> result =
        creates.create(
            tenants.current().tenantId(),
            subject(authentication),
            idempotencyKey,
            request,
            correlationId(servletRequest));
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    if (result.replayed()) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response.body(result.body());
  }

  /**
   * Revokes a pending invitation.
   *
   * @param invitationId path value (validated by the service)
   * @param authentication verified tenant administrator
   * @param servletRequest current request
   * @return the invitation
   */
  @Operation(operationId = "revokeInvitation")
  @PostMapping("/{invitationId}/revoke")
  @TenantAdminOperation(operation = RevokeInvitationService.OPERATION)
  public ResponseEntity<InvitationResponse> revoke(
      @PathVariable("invitationId") String invitationId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest servletRequest) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(
            revokes.revoke(
                tenants.current().tenantId(),
                subject(authentication),
                invitationId,
                correlationId(servletRequest)));
  }

  /**
   * Reissues a pending invitation with a new link.
   *
   * @param invitationId path value (validated by the service)
   * @param idempotencyKey required idempotency key
   * @param authentication verified tenant administrator
   * @param servletRequest current request
   * @return the receipt; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "resendInvitation")
  @PostMapping("/{invitationId}/resend")
  @TenantAdminOperation(operation = ResendInvitationService.OPERATION)
  public ResponseEntity<InvitationReceiptResponse> resend(
      @PathVariable("invitationId") String invitationId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest servletRequest) {
    IdempotentOperation.Result<InvitationReceiptResponse> result =
        resends.resend(
            tenants.current().tenantId(),
            subject(authentication),
            idempotencyKey,
            invitationId,
            correlationId(servletRequest));
    ResponseEntity.BodyBuilder response =
        ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
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
