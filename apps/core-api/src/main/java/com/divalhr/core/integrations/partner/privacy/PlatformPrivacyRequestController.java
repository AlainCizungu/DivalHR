package com.divalhr.core.integrations.partner.privacy;

import com.divalhr.core.integrations.partner.privacy.PlatformPrivacyRequestService.Caller;
import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.PlatformRequest;
import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.PlatformRequestPage;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.ratelimit.SubjectRateLimited;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Platform privacy operations on data-rights requests (LAUNCH-001, L7), exact MFA only. Tenant
 * administrators, managers and employees are refused before any argument is read. Every response is
 * {@code Cache-Control: private, no-store}.
 */
@RestController
public class PlatformPrivacyRequestController {

  /** Base path. */
  public static final String PATH = "/api/v1/platform/privacy/requests";

  static final int MAX_BODY_BYTES = 4096;
  private static final String CACHE_CONTROL = "private, no-store";

  private final PlatformPrivacyRequestService requests;
  private final JsonMapper json;

  /**
   * Creates the controller.
   *
   * @param requests platform request service
   * @param json JSON mapper
   */
  public PlatformPrivacyRequestController(PlatformPrivacyRequestService requests, JsonMapper json) {
    this.requests = requests;
    this.json = json;
  }

  /**
   * The platform queue.
   *
   * @param state optional state
   * @param type optional type
   * @param cursor optional cursor
   * @param limit optional page size
   * @param authentication verified platform administrator
   * @return the page
   */
  @Operation(operationId = "listPlatformPrivacyRequests")
  @GetMapping(PATH)
  @PlatformScoped(operation = PlatformPrivacyRequestService.LIST)
  @SubjectRateLimited(bucket = PlatformPrivacyPolicyController.READ_BUCKET)
  public ResponseEntity<PlatformRequestPage> list(
      @RequestParam(name = "state", required = false) String state,
      @RequestParam(name = "type", required = false) String type,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication) {
    return ok(requests.list(subject(authentication), state, type, cursor, limit));
  }

  /**
   * One request.
   *
   * @param requestId opaque request reference
   * @param authentication verified platform administrator
   * @param request current request
   * @return the request
   */
  @Operation(operationId = "getPlatformPrivacyRequest")
  @GetMapping(PATH + "/{requestId}")
  @PlatformScoped(operation = PlatformPrivacyRequestService.GET)
  @SubjectRateLimited(bucket = PlatformPrivacyPolicyController.READ_BUCKET)
  public ResponseEntity<PlatformRequest> get(
      @PathVariable("requestId") String requestId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(requests.get(caller(authentication, request), requestId));
  }

  /**
   * Decides or advances a request.
   *
   * @param requestId opaque request reference
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param authentication verified platform administrator
   * @param request current request
   * @return the request
   */
  @Operation(
      operationId = "transitionPlatformPrivacyRequest",
      requestBody =
          @io.swagger.v3.oas.annotations.parameters.RequestBody(
              required = true,
              content =
                  @io.swagger.v3.oas.annotations.media.Content(
                      mediaType = "application/json",
                      schema =
                          @io.swagger.v3.oas.annotations.media.Schema(
                              implementation = PrivacyCommandBodies.TransitionRequest.class))))
  @PostMapping(path = PATH + "/{requestId}/transition", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PlatformScoped(operation = PlatformPrivacyRequestService.TRANSITION)
  @SubjectRateLimited(bucket = PlatformPrivacyPolicyController.WRITE_BUCKET)
  public ResponseEntity<PlatformRequest> transition(
      @PathVariable("requestId") String requestId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    JsonNode body = PrivacyCommands.read(request, json, MAX_BODY_BYTES);
    IdempotentOperation.Result<PlatformRequest> result =
        requests.transition(caller(authentication, request), requestId, idempotencyKey, body);
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(HttpStatus.OK).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    if (result.replayed()) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response.body(result.body());
  }

  private static <T> ResponseEntity<T> ok(T body) {
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(body);
  }

  private static Caller caller(JwtAuthenticationToken authentication, HttpServletRequest request) {
    return new Caller(
        subject(authentication),
        String.valueOf(request.getAttribute(CorrelationId.REQUEST_ATTRIBUTE)));
  }

  private static String subject(JwtAuthenticationToken authentication) {
    String subject = authentication.getToken().getSubject();
    if (subject == null || subject.isBlank()) {
      throw new ApiException(ErrorCode.ACCESS_DENIED, Map.of());
    }
    return subject;
  }
}
