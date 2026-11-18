package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.api.SalaryAdvanceHandoffBodies.ReconcileHandoff;
import com.divalhr.core.payroll.api.SalaryAdvanceHandoffResponses.Page;
import com.divalhr.core.payroll.api.SalaryAdvanceHandoffResponses.View;
import com.divalhr.core.payroll.application.PayrollCaller;
import com.divalhr.core.payroll.application.SalaryAdvanceHandoffReviewService;
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
 * MVP-120B: the tenant administrator's view of controlled partner handoffs and the manual synthetic
 * reconcile or retry, for tenant administrators with privileged MFA (ADR 0042). The tenant comes
 * from the verified JWT; every response is {@code Cache-Control: private, no-store}. The
 * administrator can never create or alter employee consent.
 */
@RestController
public class SalaryAdvanceHandoffController {

  /** Base path of the tenant's handoffs. */
  public static final String PATH = "/api/v1/salary-advance-handoffs";

  private static final String CACHE_CONTROL = "private, no-store";

  private final SalaryAdvanceHandoffReviewService reviews;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param reviews administrator handoff service
   * @param tenants verified tenant context
   */
  public SalaryAdvanceHandoffController(
      SalaryAdvanceHandoffReviewService reviews, TenantContextResolver tenants) {
    this.reviews = reviews;
    this.tenants = tenants;
  }

  /**
   * The tenant's handoffs, newest first.
   *
   * @param state optional delivery-state filter
   * @param reviewState optional partner-review status filter
   * @param cursor opaque cursor, or {@code null}
   * @param limit page size, or {@code null}
   * @param authentication verified tenant administrator
   * @param request current request
   * @return a page of handoffs
   */
  @Operation(operationId = "listSalaryAdvanceHandoffs")
  @GetMapping(PATH)
  @TenantAdminOperation(operation = SalaryAdvanceHandoffReviewService.LIST)
  @SubjectRateLimited(bucket = SalaryAdvanceHandoffReviewService.SUBJECT_READ_BUCKET)
  public ResponseEntity<Page> list(
      @RequestParam(name = "state", required = false) String state,
      @RequestParam(name = "reviewState", required = false) String reviewState,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(reviews.list(caller(authentication, request), state, reviewState, cursor, limit));
  }

  /**
   * One handoff with its safe delivery diagnostics.
   *
   * @param handoffId handoff
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the handoff
   */
  @Operation(operationId = "getSalaryAdvanceHandoff")
  @GetMapping(PATH + "/{handoffId}")
  @TenantAdminOperation(operation = SalaryAdvanceHandoffReviewService.READ)
  @SubjectRateLimited(bucket = SalaryAdvanceHandoffReviewService.SUBJECT_READ_BUCKET)
  public ResponseEntity<View> get(
      @PathVariable("handoffId") String handoffId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(reviews.get(caller(authentication, request), handoffId));
  }

  /**
   * Reconciles an ambiguous delivery or retries a failed one.
   *
   * @param handoffId handoff
   * @param idempotencyKey required idempotency key
   * @param body the handoff version and the confirmation
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the handoff after the action; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "reconcileSalaryAdvanceHandoff")
  @PostMapping(path = PATH + "/{handoffId}/reconcile", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = SalaryAdvanceHandoffReviewService.RECONCILE)
  @SubjectRateLimited(bucket = SalaryAdvanceHandoffReviewService.SUBJECT_RECONCILE_BUCKET)
  @TenantRateLimited(bucket = SalaryAdvanceHandoffReviewService.TENANT_RECONCILE_BUCKET)
  public ResponseEntity<View> reconcile(
      @PathVariable("handoffId") String handoffId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody ReconcileHandoff body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    IdempotentOperation.Result<View> result =
        reviews.reconcile(caller(authentication, request), handoffId, idempotencyKey, body);
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
