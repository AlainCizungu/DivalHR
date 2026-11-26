package com.divalhr.core.integrations.partner.privacy;

import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.RequestPage;
import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.RequestView;
import com.divalhr.core.integrations.partner.privacy.PrivacyRequestService.Caller;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.ratelimit.SubjectRateLimited;
import com.divalhr.core.platform.security.EmployeeSelfOperation;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The employee's own data-rights requests (LAUNCH-001, L7). Tenant administrators and managers have
 * no route here; another employee's request is simply absent. Every response is {@code
 * Cache-Control: private, no-store}.
 */
@RestController
public class MyPrivacyRequestController {

  /** Base path. */
  public static final String PATH = "/api/v1/me/privacy/requests";

  /** Per-subject read bucket (requests per minute); cancellation shares it. */
  public static final String READ_BUCKET = "privacy-employee-read";

  /** Per-subject creation bucket (requests per minute). */
  public static final String WRITE_BUCKET = "privacy-employee-write";

  static final int MAX_BODY_BYTES = 1024;
  private static final String CACHE_CONTROL = "private, no-store";

  private final PrivacyRequestService requests;
  private final PrivacyExportService exports;
  private final TenantContextResolver tenants;
  private final JsonMapper json;

  /**
   * Creates the controller.
   *
   * @param requests request service
   * @param exports access-package service
   * @param tenants verified tenant of the request
   * @param json JSON mapper
   */
  public MyPrivacyRequestController(
      PrivacyRequestService requests,
      PrivacyExportService exports,
      TenantContextResolver tenants,
      JsonMapper json) {
    this.requests = requests;
    this.exports = exports;
    this.tenants = tenants;
    this.json = json;
  }

  /**
   * Creates a request about the caller's own data.
   *
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param authentication verified employee
   * @param request current request
   * @return the request
   */
  @Operation(
      operationId = "createMyPrivacyRequest",
      requestBody =
          @io.swagger.v3.oas.annotations.parameters.RequestBody(
              required = true,
              content =
                  @io.swagger.v3.oas.annotations.media.Content(
                      mediaType = "application/json",
                      schema =
                          @io.swagger.v3.oas.annotations.media.Schema(
                              implementation = PrivacyCommandBodies.CreateRequest.class))))
  @ResponseStatus(HttpStatus.CREATED)
  @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
  @EmployeeSelfOperation(operation = PrivacyRequestService.CREATE)
  @SubjectRateLimited(bucket = WRITE_BUCKET)
  public ResponseEntity<RequestView> create(
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    JsonNode body = PrivacyCommands.read(request, json, MAX_BODY_BYTES);
    return write(
        HttpStatus.CREATED, requests.create(caller(authentication, request), idempotencyKey, body));
  }

  /**
   * The caller's own requests, newest first.
   *
   * @param state optional state
   * @param cursor optional cursor
   * @param limit optional page size
   * @param authentication verified employee
   * @param request current request
   * @return the page
   */
  @Operation(operationId = "listMyPrivacyRequests")
  @GetMapping(PATH)
  @EmployeeSelfOperation(operation = PrivacyRequestService.LIST)
  @SubjectRateLimited(bucket = READ_BUCKET)
  public ResponseEntity<RequestPage> list(
      @RequestParam(name = "state", required = false) String state,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(requests.list(caller(authentication, request), state, cursor, limit));
  }

  /**
   * One of the caller's own requests.
   *
   * @param requestId opaque request reference
   * @param authentication verified employee
   * @param request current request
   * @return the request
   */
  @Operation(operationId = "getMyPrivacyRequest")
  @GetMapping(PATH + "/{requestId}")
  @EmployeeSelfOperation(operation = PrivacyRequestService.GET)
  @SubjectRateLimited(bucket = READ_BUCKET)
  public ResponseEntity<RequestView> get(
      @PathVariable("requestId") String requestId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(requests.get(caller(authentication, request), requestId));
  }

  /**
   * Cancels the caller's own open request.
   *
   * @param requestId opaque request reference
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param authentication verified employee
   * @param request current request
   * @return the request
   */
  @Operation(
      operationId = "cancelMyPrivacyRequest",
      requestBody =
          @io.swagger.v3.oas.annotations.parameters.RequestBody(
              required = true,
              content =
                  @io.swagger.v3.oas.annotations.media.Content(
                      mediaType = "application/json",
                      schema =
                          @io.swagger.v3.oas.annotations.media.Schema(
                              implementation = PrivacyCommandBodies.CancelRequest.class))))
  @PostMapping(path = PATH + "/{requestId}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
  @EmployeeSelfOperation(operation = PrivacyRequestService.CANCEL)
  @SubjectRateLimited(bucket = READ_BUCKET)
  public ResponseEntity<RequestView> cancel(
      @PathVariable("requestId") String requestId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    JsonNode body = PrivacyCommands.read(request, json, MAX_BODY_BYTES);
    return write(
        HttpStatus.OK,
        requests.cancel(caller(authentication, request), requestId, idempotencyKey, body));
  }

  /**
   * Downloads the access package of the caller's own approved ACCESS request.
   *
   * @param requestId opaque request reference
   * @param authentication verified employee
   * @param request current request
   * @return the package as an attachment
   */
  @Operation(operationId = "downloadMyPrivacyAccessPackage")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "200",
      content =
          @io.swagger.v3.oas.annotations.media.Content(
              mediaType = "application/json",
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(
                      implementation = PrivacyCommandBodies.AccessPackage.class)))
  @GetMapping(PATH + "/{requestId}/export")
  @EmployeeSelfOperation(operation = PrivacyExportService.DOWNLOAD)
  @SubjectRateLimited(bucket = READ_BUCKET)
  public ResponseEntity<byte[]> export(
      @PathVariable("requestId") String requestId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    PrivacyExportService.Download download =
        exports.download(caller(authentication, request), requestId);
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"divalhr-privacy-access-package.json\"")
        .header("X-Content-SHA256", download.sha256())
        .contentType(MediaType.APPLICATION_JSON)
        .body(download.body());
  }

  private static <T> ResponseEntity<T> ok(T body) {
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(body);
  }

  private static <T> ResponseEntity<T> write(
      HttpStatus status, IdempotentOperation.Result<T> result) {
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    if (result.replayed()) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response.body(result.body());
  }

  private Caller caller(JwtAuthenticationToken authentication, HttpServletRequest request) {
    String subject = authentication.getToken().getSubject();
    if (subject == null || subject.isBlank()) {
      throw new ApiException(ErrorCode.ACCESS_DENIED, Map.of());
    }
    return new Caller(
        tenants.current().tenantId(),
        subject,
        String.valueOf(request.getAttribute(CorrelationId.REQUEST_ATTRIBUTE)));
  }
}
