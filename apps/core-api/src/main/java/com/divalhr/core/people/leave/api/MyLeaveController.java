package com.divalhr.core.people.leave.api;

import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.leave.api.MyLeaveResponses.CancellationReceipt;
import com.divalhr.core.people.leave.api.MyLeaveResponses.PolicyPage;
import com.divalhr.core.people.leave.api.MyLeaveResponses.Request;
import com.divalhr.core.people.leave.api.MyLeaveResponses.RequestPage;
import com.divalhr.core.people.leave.application.MyLeaveService;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.ratelimit.SubjectRateLimited;
import com.divalhr.core.platform.ratelimit.TenantRateLimited;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Employee self-service leave endpoints (MVP-041A, Issue #87; cancellation MVP-041C, Issue #91).
 * Every handler is an employee self-service operation ({@code EmployeeSelfOperation}): subject,
 * role {@code employee}, tenant and an active membership are checked, then the request limits,
 * before any argument or body is read; the service binds every query to the caller's own active
 * employee-access link. Responses are {@code private, no-store}.
 */
@RestController
@RequestMapping(MyLeaveController.PATH)
public class MyLeaveController {

  /** Full public path prefix (server base {@code /api/v1}). */
  public static final String PATH = "/api/v1/me";

  /** Every response holds Restricted HR or Confidential organization data. */
  static final String CACHE_CONTROL = "private, no-store";

  private final MyLeaveService leave;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param leave self-service leave service
   * @param tenants verified tenant resolver
   */
  public MyLeaveController(MyLeaveService leave, TenantContextResolver tenants) {
    this.leave = leave;
    this.tenants = tenants;
  }

  /**
   * The leave policies the caller may request (planned or active), by code.
   *
   * @param cursor opaque cursor
   * @param limit page size
   * @param authentication verified employee
   * @param request current request
   * @return the page
   */
  @Operation(operationId = "listMyLeavePolicies")
  @GetMapping("/leave-policies")
  @EmployeeSelfOperation(operation = MyLeaveService.POLICIES)
  @SubjectRateLimited(bucket = MyLeaveService.SUBJECT_READ_BUCKET)
  public ResponseEntity<PolicyPage> policies(
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(leave.policies(caller(authentication, request), cursor, limit));
  }

  /**
   * Submits a pending leave request for the caller.
   *
   * @param idempotencyKey required idempotency key
   * @param body policy, dates and amount
   * @param authentication verified employee
   * @param request current request
   * @return 201 with the request; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "createMyLeaveRequest")
  @PostMapping(path = "/leave-requests", consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @EmployeeSelfOperation(operation = MyLeaveService.CREATE)
  @SubjectRateLimited(bucket = MyLeaveService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = MyLeaveService.TENANT_WRITE_BUCKET)
  public ResponseEntity<Request> create(
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CreateMyLeaveRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    IdempotentOperation.Result<Request> result =
        leave.create(caller(authentication, request), idempotencyKey, body);
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    if (result.replayed()) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response.body(result.body());
  }

  /**
   * Cancels one of the caller's own pending leave requests (MVP-041C).
   *
   * @param requestId request
   * @param idempotencyKey required idempotency key
   * @param body reason and its language
   * @param authentication verified employee
   * @param request current request
   * @return 200 with the minimal receipt; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "cancelMyLeaveRequest")
  @PostMapping(
      path = "/leave-requests/{requestId}/cancellation",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  @EmployeeSelfOperation(operation = MyLeaveService.CANCEL)
  @SubjectRateLimited(bucket = MyLeaveService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = MyLeaveService.TENANT_WRITE_BUCKET)
  public ResponseEntity<CancellationReceipt> cancel(
      @PathVariable("requestId") String requestId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CancelLeaveRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    IdempotentOperation.Result<CancellationReceipt> result =
        leave.cancel(caller(authentication, request), requestId, idempotencyKey, body);
    ResponseEntity.BodyBuilder response =
        ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    if (result.replayed()) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response.body(result.body());
  }

  /**
   * The caller's own leave requests, newest first.
   *
   * @param cursor opaque cursor
   * @param limit page size
   * @param authentication verified employee
   * @param request current request
   * @return the page, after its disclosure audit committed
   */
  @Operation(operationId = "listMyLeaveRequests")
  @GetMapping("/leave-requests")
  @EmployeeSelfOperation(operation = MyLeaveService.REQUESTS)
  @SubjectRateLimited(bucket = MyLeaveService.SUBJECT_READ_BUCKET)
  public ResponseEntity<RequestPage> requests(
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(leave.requests(caller(authentication, request), cursor, limit));
  }

  private PeopleCaller caller(JwtAuthenticationToken authentication, HttpServletRequest request) {
    String subject = authentication.getToken().getSubject();
    if (subject == null || subject.isBlank()) {
      throw new ApiException(ErrorCode.ACCESS_DENIED, Map.of());
    }
    return new PeopleCaller(
        tenants.current().tenantId(),
        subject,
        String.valueOf(request.getAttribute(CorrelationId.REQUEST_ATTRIBUTE)));
  }
}
