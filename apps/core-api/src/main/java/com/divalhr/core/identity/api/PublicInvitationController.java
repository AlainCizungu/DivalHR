package com.divalhr.core.identity.api;

import com.divalhr.core.identity.application.InvitationAcceptance;
import com.divalhr.core.identity.application.PublicInvitationService;
import com.divalhr.core.platform.ratelimit.ClientAddressResolver;
import com.divalhr.core.platform.security.PublicOperation;
import com.divalhr.core.platform.web.CorrelationId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Anonymous invitation endpoints ({@code x-divalhr-scope: public}). Served by a security chain that
 * neither requires nor reads access tokens. The token travels only in the request body; responses
 * are {@code Cache-Control: no-store}.
 */
@RestController
@RequestMapping(PublicInvitationController.PATH)
public class PublicInvitationController {

  /** Full public path (server base {@code /api/v1}). */
  public static final String PATH = "/api/v1/public/invitations";

  /** Cache policy of every anonymous invitation response. */
  public static final String CACHE_CONTROL = "no-store";

  private final PublicInvitationService invitations;
  private final ClientAddressResolver clients;

  /**
   * Creates the controller.
   *
   * @param invitations anonymous flow
   * @param clients client address resolver (rate-limit key only)
   */
  public PublicInvitationController(
      PublicInvitationService invitations, ClientAddressResolver clients) {
    this.invitations = invitations;
    this.clients = clients;
  }

  /**
   * Inspects an invitation by its token.
   *
   * @param request body with the token
   * @param servletRequest current request
   * @return role, locale and expiry
   */
  @Operation(operationId = "inspectInvitation")
  @SecurityRequirements
  @PostMapping(path = "/inspect", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PublicOperation(operation = PublicInvitationService.INSPECT)
  public ResponseEntity<InvitationPreviewResponse> inspect(
      @RequestBody InvitationTokenRequest request,
      @Parameter(hidden = true) HttpServletRequest servletRequest) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(invitations.inspect(request, clients.resolve(servletRequest)));
  }

  /**
   * Accepts an invitation by its token.
   *
   * @param request body with the token
   * @param servletRequest current request
   * @return acceptance result
   */
  @Operation(operationId = "acceptInvitation")
  @SecurityRequirements
  @PostMapping(path = "/accept", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PublicOperation(operation = InvitationAcceptance.OPERATION)
  public ResponseEntity<InvitationAcceptanceResponse> accept(
      @RequestBody InvitationTokenRequest request,
      @Parameter(hidden = true) HttpServletRequest servletRequest) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(
            invitations.accept(
                request,
                clients.resolve(servletRequest),
                String.valueOf(servletRequest.getAttribute(CorrelationId.REQUEST_ATTRIBUTE))));
  }
}
