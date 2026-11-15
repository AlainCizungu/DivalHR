package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.api.StatutoryPackResponses.Applicability;
import com.divalhr.core.payroll.application.PayrollCaller;
import com.divalhr.core.payroll.application.StatutoryPackApplicabilityService;
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
 * MVP-097A: the read-only statutory pack that applies to a legal entity and a payroll period, for
 * tenant administrators with privileged MFA. The pack is selected by the entity's country and the
 * period, never by a client-supplied pack identifier. Public provenance only. Every response is
 * {@code Cache-Control: private, no-store}.
 */
@RestController
public class StatutoryPackApplicabilityController {

  /** Path of the applicability read. */
  public static final String PATH =
      "/api/v1/legal-entities/{legalEntityId}/statutory-pack-applicability";

  private static final String CACHE_CONTROL = "private, no-store";

  private final StatutoryPackApplicabilityService applicability;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param applicability applicability service
   * @param tenants verified tenant context
   */
  public StatutoryPackApplicabilityController(
      StatutoryPackApplicabilityService applicability, TenantContextResolver tenants) {
    this.applicability = applicability;
    this.tenants = tenants;
  }

  /**
   * The applicable pack of a legal entity and a payroll period.
   *
   * @param legalEntityId legal entity
   * @param payrollPeriod {@code YYYY-MM}
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the applicability
   */
  @Operation(operationId = "getStatutoryPackApplicability")
  @GetMapping(PATH)
  @TenantAdminOperation(operation = StatutoryPackApplicabilityService.READ)
  @SubjectRateLimited(bucket = StatutoryPackGovernanceController.READ_BUCKET)
  public ResponseEntity<Applicability> get(
      @PathVariable("legalEntityId") String legalEntityId,
      @RequestParam(name = "payrollPeriod", required = false) String payrollPeriod,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    String subject = authentication.getToken().getSubject();
    if (subject == null || subject.isBlank()) {
      throw new ApiException(ErrorCode.ACCESS_DENIED, Map.of());
    }
    PayrollCaller caller =
        new PayrollCaller(
            tenants.current().tenantId(),
            subject,
            String.valueOf(request.getAttribute(CorrelationId.REQUEST_ATTRIBUTE)));
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(applicability.read(caller, legalEntityId, payrollPeriod));
  }
}
