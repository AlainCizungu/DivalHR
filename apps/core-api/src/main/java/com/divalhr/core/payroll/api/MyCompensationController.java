package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.api.CompensationResponses.Mine;
import com.divalhr.core.payroll.application.MyCompensationService;
import com.divalhr.core.payroll.application.PayrollCaller;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.ratelimit.SubjectRateLimited;
import com.divalhr.core.platform.security.EmployeeSelfOperation;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * MVP-093A: an employee's own monthly compensation (ADR 0032). Subject, role {@code employee},
 * tenant and an active membership are checked, then the request limit, before any argument is
 * read; the service binds every query to the caller's own active employee-access link. Responses
 * are {@code Cache-Control: private, no-store}.
 */
@RestController
public class MyCompensationController {

  /** Full public path (server base {@code /api/v1}). */
  public static final String PATH = "/api/v1/me/compensation";

  private final MyCompensationService compensation;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param compensation self-service compensation service
   * @param tenants verified tenant resolver
   */
  public MyCompensationController(
      MyCompensationService compensation, TenantContextResolver tenants) {
    this.compensation = compensation;
    this.tenants = tenants;
  }

  /**
   * The caller's own compensation and a page of its history.
   *
   * @param cursor opaque cursor, or {@code null}
   * @param limit page size, or {@code null}
   * @param authentication verified employee
   * @param request current request
   * @return the record, after its disclosure audit committed
   */
  @Operation(operationId = "getMyCompensation")
  @GetMapping(PATH)
  @EmployeeSelfOperation(operation = MyCompensationService.READ)
  @SubjectRateLimited(bucket = MyCompensationService.SUBJECT_READ_BUCKET)
  public ResponseEntity<Mine> read(
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .body(compensation.read(caller(authentication, request), cursor, limit));
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
