package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.api.SalaryAdvanceBodies.RefuseCommand;
import com.divalhr.core.payroll.api.SalaryAdvanceBodies.VersionedCommand;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses.RequestPage;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses.RequestView;
import com.divalhr.core.payroll.application.PayrollCaller;
import com.divalhr.core.payroll.application.SalaryAdvanceReviewService;
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
 * MVP-120A: the employer review of salary-advance requests, for tenant administrators with
 * privileged MFA (ADR 0041). The tenant comes from the verified JWT; every response is {@code
 * Cache-Control: private, no-store}. Confirmation and refusal are employer participation outcomes,
 * never credit decisions: DivalHR is not the lender and no funds are sent in this step.
 */
@RestController
public class SalaryAdvanceReviewController {

  /** Base path of the tenant's salary-advance requests. */
  public static final String PATH = "/api/v1/salary-advance-requests";

  private static final String CACHE_CONTROL = "private, no-store";

  private final SalaryAdvanceReviewService reviews;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param reviews employer review service
   * @param tenants verified tenant context
   */
  public SalaryAdvanceReviewController(
      SalaryAdvanceReviewService reviews, TenantContextResolver tenants) {
    this.reviews = reviews;
    this.tenants = tenants;
  }

  /**
   * The tenant's requests, newest first.
   *
   * @param status optional status filter
   * @param cursor opaque cursor, or {@code null}
   * @param limit page size, or {@code null}
   * @param authentication verified tenant administrator
   * @param request current request
   * @return a page of requests
   */
  @Operation(operationId = "listSalaryAdvanceRequests")
  @GetMapping(PATH)
  @TenantAdminOperation(operation = SalaryAdvanceReviewService.LIST)
  @SubjectRateLimited(bucket = SalaryAdvanceReviewService.SUBJECT_READ_BUCKET)
  public ResponseEntity<RequestPage> list(
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(reviews.list(caller(authentication, request), status, cursor, limit));
  }

  /**
   * One request with its frozen evidence and history.
   *
   * @param requestId request
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the request
   */
  @Operation(operationId = "getSalaryAdvanceRequest")
  @GetMapping(PATH + "/{requestId}")
  @TenantAdminOperation(operation = SalaryAdvanceReviewService.READ)
  @SubjectRateLimited(bucket = SalaryAdvanceReviewService.SUBJECT_READ_BUCKET)
  public ResponseEntity<RequestView> get(
      @PathVariable("requestId") String requestId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(reviews.get(caller(authentication, request), requestId));
  }

  /**
   * Records that the employer is prepared to support a later deduction if a regulated partner
   * approves. This is not a credit approval.
   *
   * @param requestId request
   * @param idempotencyKey required idempotency key
   * @param body the request version and the confirmation
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the confirmed request; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "confirmSalaryAdvanceEmployer")
  @PostMapping(
      path = PATH + "/{requestId}/confirm-employer",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = SalaryAdvanceReviewService.CONFIRM)
  @SubjectRateLimited(bucket = SalaryAdvanceReviewService.SUBJECT_DECISION_BUCKET)
  @TenantRateLimited(bucket = SalaryAdvanceReviewService.TENANT_DECISION_BUCKET)
  public ResponseEntity<RequestView> confirm(
      @PathVariable("requestId") String requestId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody VersionedCommand body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return replayable(
        reviews.confirm(caller(authentication, request), requestId, idempotencyKey, body));
  }

  /**
   * Records a bounded employer-policy refusal. This is not a credit decision.
   *
   * @param requestId request
   * @param idempotencyKey required idempotency key
   * @param body the request version, the closed reason and the optional internal note
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the refused request; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "refuseSalaryAdvanceEmployer")
  @PostMapping(
      path = PATH + "/{requestId}/refuse-employer",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = SalaryAdvanceReviewService.REFUSE)
  @SubjectRateLimited(bucket = SalaryAdvanceReviewService.SUBJECT_DECISION_BUCKET)
  @TenantRateLimited(bucket = SalaryAdvanceReviewService.TENANT_DECISION_BUCKET)
  public ResponseEntity<RequestView> refuse(
      @PathVariable("requestId") String requestId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody RefuseCommand body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return replayable(
        reviews.refuse(caller(authentication, request), requestId, idempotencyKey, body));
  }

  private static ResponseEntity<RequestView> replayable(
      IdempotentOperation.Result<RequestView> result) {
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
