package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.api.PaymentResponses.MyDestination;
import com.divalhr.core.payroll.application.PaymentDestinationService;
import com.divalhr.core.platform.ratelimit.SubjectRateLimited;
import com.divalhr.core.platform.security.EmployeeSelfOperation;
import com.divalhr.core.platform.tenancy.TenantContextResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MVP-095A: an employee's own masked salary payment destination, read-only (ADR 0036). Subject,
 * role {@code employee}, tenant and an active membership are checked, then the request limit,
 * before any argument is read; the service binds the read to the caller's own active
 * employee-access link. No history, holder name, full account or phone number, employer total or
 * other employee can be reached, and the employee cannot change it here. Responses are {@code
 * Cache-Control: private, no-store}.
 */
@RestController
public class MyPaymentDestinationController {

  /** Path (server base {@code /api/v1}). */
  public static final String PATH = "/api/v1/me/payment-destination";

  private static final String CACHE_CONTROL = "private, no-store";

  private final PaymentDestinationService destinations;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param destinations destination service
   * @param tenants verified tenant resolver
   */
  public MyPaymentDestinationController(
      PaymentDestinationService destinations, TenantContextResolver tenants) {
    this.destinations = destinations;
    this.tenants = tenants;
  }

  /**
   * The caller's own current masked destination.
   *
   * @param authentication verified employee
   * @param request current request
   * @return the masked destination, or {@code NOT_RECORDED}
   */
  @Operation(operationId = "getMyPaymentDestination")
  @GetMapping(PATH)
  @EmployeeSelfOperation(operation = PaymentDestinationService.SELF_READ)
  @SubjectRateLimited(bucket = PaymentDestinationService.SUBJECT_READ_BUCKET)
  public ResponseEntity<MyDestination> get(
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(destinations.mine(Caller.of(tenants, authentication, request)));
  }
}
