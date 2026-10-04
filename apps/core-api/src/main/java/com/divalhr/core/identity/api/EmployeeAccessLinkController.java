package com.divalhr.core.identity.api;

import com.divalhr.core.identity.application.EmployeeAccessLinkService;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.ratelimit.SubjectRateLimited;
import com.divalhr.core.platform.ratelimit.TenantRateLimited;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Employee access-link endpoints (MVP-022, Issue #49). Owned by identity, under the employee path.
 * Every handler is {@code @TenantAdminOperation}: subject, role, tenant, exact MFA and membership
 * are checked, then the request limits, before any argument or body is read. Responses are {@code
 * private, no-store}.
 */
@RestController
@RequestMapping(EmployeeAccessLinkController.PATH)
public class EmployeeAccessLinkController {

  /** Full public path (server base {@code /api/v1}). */
  public static final String PATH = "/api/v1/employees/{employeeId}/access-link";

  /** Cache policy of every response. */
  public static final String CACHE_CONTROL = "private, no-store";

  private final EmployeeAccessLinkService links;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param links link service
   * @param tenants verified tenant resolver
   */
  public EmployeeAccessLinkController(
      EmployeeAccessLinkService links, TenantContextResolver tenants) {
    this.links = links;
    this.tenants = tenants;
  }

  /**
   * Reads the employee's access link.
   *
   * @param employeeId path value
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the access
   */
  @Operation(operationId = "getEmployeeAccessLink")
  @GetMapping
  @TenantAdminOperation(operation = EmployeeAccessLinkService.READ)
  @SubjectRateLimited(bucket = EmployeeAccessLinkService.SUBJECT_READ_BUCKET)
  public ResponseEntity<EmployeeAccessResponse> read(
      @PathVariable("employeeId") String employeeId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(links.read(caller(authentication, request), employeeId));
  }

  /**
   * Finds the membership of one exact address.
   *
   * @param employeeId path value
   * @param body exact address
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the candidate
   */
  @Operation(operationId = "lookupEmployeeAccessCandidate")
  @PostMapping(path = "/lookup", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = EmployeeAccessLinkService.LOOKUP)
  @SubjectRateLimited(bucket = EmployeeAccessLinkService.SUBJECT_READ_BUCKET)
  public ResponseEntity<AccessLinkCandidateResponse> lookup(
      @PathVariable("employeeId") String employeeId,
      @RequestBody AccessReviewLookupRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(links.lookup(caller(authentication, request), employeeId, body));
  }

  /**
   * Links the employee to a membership.
   *
   * @param employeeId path value
   * @param idempotencyKey required idempotency key
   * @param body membership
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 201 with the access; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "createEmployeeAccessLink")
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @TenantAdminOperation(operation = EmployeeAccessLinkService.CREATE)
  @SubjectRateLimited(bucket = EmployeeAccessLinkService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = EmployeeAccessLinkService.TENANT_WRITE_BUCKET)
  public ResponseEntity<EmployeeAccessResponse> create(
      @PathVariable("employeeId") String employeeId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CreateEmployeeAccessLinkRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return respond(
        HttpStatus.CREATED,
        links.create(caller(authentication, request), employeeId, idempotencyKey, body));
  }

  /**
   * Removes the employee's link.
   *
   * @param employeeId path value
   * @param idempotencyKey required idempotency key
   * @param body link and expected version
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 200 with the access; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "removeEmployeeAccessLink")
  @PostMapping(path = "/remove", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = EmployeeAccessLinkService.REMOVE)
  @SubjectRateLimited(bucket = EmployeeAccessLinkService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = EmployeeAccessLinkService.TENANT_WRITE_BUCKET)
  public ResponseEntity<EmployeeAccessResponse> remove(
      @PathVariable("employeeId") String employeeId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody RemoveEmployeeAccessLinkRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return respond(
        HttpStatus.OK,
        links.remove(caller(authentication, request), employeeId, idempotencyKey, body));
  }

  private static <T> ResponseEntity<T> ok(T body) {
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(body);
  }

  private static <T> ResponseEntity<T> respond(
      HttpStatus status, IdempotentOperation.Result<T> result) {
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    if (result.replayed()) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response.body(result.body());
  }

  private EmployeeAccessLinkService.Caller caller(
      JwtAuthenticationToken authentication, HttpServletRequest request) {
    String subject = authentication.getToken().getSubject();
    if (subject == null || subject.isBlank()) {
      throw new ApiException(ErrorCode.ACCESS_DENIED, Map.of());
    }
    return new EmployeeAccessLinkService.Caller(
        tenants.current().tenantId(),
        subject,
        String.valueOf(request.getAttribute(CorrelationId.REQUEST_ATTRIBUTE)));
  }
}
