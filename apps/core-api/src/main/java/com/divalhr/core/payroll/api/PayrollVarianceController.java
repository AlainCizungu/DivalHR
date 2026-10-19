package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.api.PayrollVarianceResponses.Candidates;
import com.divalhr.core.payroll.api.PayrollVarianceResponses.Review;
import com.divalhr.core.payroll.application.PayrollCaller;
import com.divalhr.core.payroll.application.PayrollInputService;
import com.divalhr.core.payroll.application.PayrollVarianceService;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.ratelimit.SubjectRateLimited;
import com.divalhr.core.platform.security.TenantAdminOperation;
import com.divalhr.core.platform.tenancy.TenantContextResolver;
import com.divalhr.core.platform.web.CorrelationId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * MVP-091: read-only variance review of generated payroll-input runs for tenant administrators with
 * privileged MFA. The tenant comes from the verified JWT (never the body, path or query); every
 * response is {@code Cache-Control: private, no-store}.
 */
@RestController
public class PayrollVarianceController {

  private static final String CACHE_CONTROL = "private, no-store";

  private final PayrollVarianceService variance;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param variance variance review
   * @param tenants verified tenant context
   */
  public PayrollVarianceController(PayrollVarianceService variance, TenantContextResolver tenants) {
    this.variance = variance;
    this.tenants = tenants;
  }

  /**
   * The runs a current run can be compared with, newest period first.
   *
   * @param runId the current run
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the candidates
   */
  @Operation(operationId = "listPayrollVarianceCandidates")
  @GetMapping(PayrollInputController.PATH + "/{runId}/variance-candidates")
  @TenantAdminOperation(operation = PayrollVarianceService.CANDIDATES)
  @SubjectRateLimited(bucket = PayrollInputService.SUBJECT_READ_BUCKET)
  public ResponseEntity<Candidates> candidates(
      @PathVariable("runId") String runId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(variance.candidates(caller(authentication, request), runId));
  }

  /**
   * One page of the worked-time variance between a current run and a comparison run.
   *
   * @param runId the current run
   * @param comparisonRunId the comparison run, or {@code null} for the newest eligible one
   * @param absoluteMinuteThreshold 0 to 10,080 (default 120)
   * @param percentageThreshold 0 to 100 (default 20)
   * @param view ALL, MATERIAL, NEW, MISSING, CHANGED or UNCHANGED (default ALL)
   * @param cursor opaque cursor, or {@code null}
   * @param limit page size (1 to 100, default 25)
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the page
   */
  @Operation(operationId = "reviewPayrollVariance")
  @GetMapping(PayrollInputController.PATH + "/{runId}/variance")
  @TenantAdminOperation(operation = PayrollVarianceService.REVIEW)
  @SubjectRateLimited(bucket = PayrollInputService.SUBJECT_READ_BUCKET)
  public ResponseEntity<Review> review(
      @PathVariable("runId") String runId,
      @RequestParam(name = "comparisonRunId", required = false) String comparisonRunId,
      @RequestParam(name = "absoluteMinuteThreshold", required = false)
          String absoluteMinuteThreshold,
      @RequestParam(name = "percentageThreshold", required = false) String percentageThreshold,
      @RequestParam(name = "view", required = false) String view,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(
        variance.review(
            caller(authentication, request),
            runId,
            comparisonRunId,
            absoluteMinuteThreshold,
            percentageThreshold,
            view,
            cursor,
            limit));
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
