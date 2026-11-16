package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.api.SalaryAdvanceBodies.CommitRequest;
import com.divalhr.core.payroll.api.SalaryAdvanceBodies.RequestCommand;
import com.divalhr.core.payroll.api.SalaryAdvanceBodies.VersionedCommand;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses.EligibilityView;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses.RequestPage;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses.RequestPreview;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses.RequestView;
import com.divalhr.core.payroll.application.PayrollCaller;
import com.divalhr.core.payroll.application.SalaryAdvanceRequestService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * MVP-120A: an employee's own salary-advance requests (ADR 0041). Subject, role {@code employee},
 * tenant and an active membership are checked, then the request limit, before any argument is
 * read; the service binds every query to the caller's own active employee-access link. Managers
 * and platform administrators have no route here. Every response is {@code Cache-Control: private,
 * no-store}. A request is a request to the employer: DivalHR is not the lender, employer
 * eligibility is not credit approval and no funds are sent in this step.
 */
@RestController
public class MySalaryAdvanceController {

  /** Eligibility path. */
  public static final String ELIGIBILITY_PATH = "/api/v1/me/salary-advance-eligibility";

  /** Requests path. */
  public static final String PATH = "/api/v1/me/salary-advance-requests";

  private static final String CACHE_CONTROL = "private, no-store";

  private final SalaryAdvanceRequestService requests;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param requests employee request service
   * @param tenants verified tenant context
   */
  public MySalaryAdvanceController(
      SalaryAdvanceRequestService requests, TenantContextResolver tenants) {
    this.requests = requests;
    this.tenants = tenants;
  }

  /**
   * The caller's eligibility under the employer policy.
   *
   * @param authentication verified employee
   * @param request current request
   * @return the eligibility in each currency
   */
  @Operation(operationId = "getMySalaryAdvanceEligibility")
  @GetMapping(ELIGIBILITY_PATH)
  @EmployeeSelfOperation(operation = SalaryAdvanceRequestService.SELF_ELIGIBILITY)
  @SubjectRateLimited(bucket = SalaryAdvanceRequestService.SUBJECT_READ_BUCKET)
  public ResponseEntity<EligibilityView> eligibility(
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(requests.eligibility(caller(authentication, request)));
  }

  /**
   * Evaluates a requested amount without writing anything.
   *
   * @param body the currency and amount
   * @param authentication verified employee
   * @param request current request
   * @return the preview
   */
  @Operation(operationId = "previewMySalaryAdvanceRequest")
  @PostMapping(path = PATH + "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
  @EmployeeSelfOperation(operation = SalaryAdvanceRequestService.SELF_PREVIEW)
  @SubjectRateLimited(bucket = SalaryAdvanceRequestService.SUBJECT_PREVIEW_BUCKET)
  @TenantRateLimited(bucket = SalaryAdvanceRequestService.TENANT_PREVIEW_BUCKET)
  public ResponseEntity<RequestPreview> preview(
      @RequestBody RequestCommand body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(requests.preview(caller(authentication, request), body));
  }

  /**
   * The caller's own requests, newest first.
   *
   * @param status optional status filter
   * @param cursor opaque cursor, or {@code null}
   * @param limit page size, or {@code null}
   * @param authentication verified employee
   * @param request current request
   * @return a page of requests
   */
  @Operation(operationId = "listMySalaryAdvanceRequests")
  @GetMapping(PATH)
  @EmployeeSelfOperation(operation = SalaryAdvanceRequestService.SELF_LIST)
  @SubjectRateLimited(bucket = SalaryAdvanceRequestService.SUBJECT_READ_BUCKET)
  public ResponseEntity<RequestPage> list(
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(requests.list(caller(authentication, request), status, cursor, limit));
  }

  /**
   * Submits a previewed request with the employee's explicit consent.
   *
   * @param idempotencyKey required idempotency key
   * @param body the previewed amount, its digest and the consent affirmation
   * @param authentication verified employee
   * @param request current request
   * @return 201 with the request; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "submitMySalaryAdvanceRequest")
  @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
  @EmployeeSelfOperation(operation = SalaryAdvanceRequestService.SELF_SUBMIT)
  @SubjectRateLimited(bucket = SalaryAdvanceRequestService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = SalaryAdvanceRequestService.TENANT_WRITE_BUCKET)
  public ResponseEntity<RequestView> submit(
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CommitRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    IdempotentOperation.Result<RequestView> result =
        requests.submit(caller(authentication, request), idempotencyKey, body);
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    if (result.replayed()) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response.body(result.body());
  }

  /**
   * One of the caller's own requests.
   *
   * @param requestId request
   * @param authentication verified employee
   * @param request current request
   * @return the request
   */
  @Operation(operationId = "getMySalaryAdvanceRequest")
  @GetMapping(PATH + "/{requestId}")
  @EmployeeSelfOperation(operation = SalaryAdvanceRequestService.SELF_READ)
  @SubjectRateLimited(bucket = SalaryAdvanceRequestService.SUBJECT_READ_BUCKET)
  public ResponseEntity<RequestView> get(
      @PathVariable("requestId") String requestId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(requests.get(caller(authentication, request), requestId));
  }

  /**
   * Withdraws one of the caller's own requests before any external handoff (none exists yet).
   *
   * @param requestId request
   * @param idempotencyKey required idempotency key
   * @param body the request version and the confirmation
   * @param authentication verified employee
   * @param request current request
   * @return the withdrawn request; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "withdrawMySalaryAdvanceRequest")
  @PostMapping(path = PATH + "/{requestId}/withdraw", consumes = MediaType.APPLICATION_JSON_VALUE)
  @EmployeeSelfOperation(operation = SalaryAdvanceRequestService.SELF_WITHDRAW)
  @SubjectRateLimited(bucket = SalaryAdvanceRequestService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = SalaryAdvanceRequestService.TENANT_WRITE_BUCKET)
  public ResponseEntity<RequestView> withdraw(
      @PathVariable("requestId") String requestId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody VersionedCommand body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    IdempotentOperation.Result<RequestView> result =
        requests.withdraw(caller(authentication, request), requestId, idempotencyKey, body);
    ResponseEntity.BodyBuilder response =
        ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    if (result.replayed()) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response.body(result.body());
  }

  private static <T> ResponseEntity<T> ok(T body) {
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(body);
  }

  private PayrollCaller caller(JwtAuthenticationToken authentication, HttpServletRequest request) {
    String subject = authentication.getToken().getSubject();
    if (subject == null || subject.isBlank()) {
      throw new ApiException(ErrorCode.ACCESS_DENIED, Map.of());
    }
    return new PayrollCaller(
        tenants.current().tenantId(),
        subject,
        String.valueOf(request.getAttribute(CorrelationId.REQUEST_ATTRIBUTE)));
  }
}
