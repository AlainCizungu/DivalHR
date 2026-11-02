package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.application.PayrollCaller;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.tenancy.TenantContextResolver;
import com.divalhr.core.platform.web.CorrelationId;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Builds the verified caller of a payment request: the tenant from the verified JWT, the subject
 * and the correlation ID. Nothing of it comes from a path, a query or a body.
 */
final class Caller {

  private Caller() {}

  static PayrollCaller of(
      TenantContextResolver tenants,
      JwtAuthenticationToken authentication,
      HttpServletRequest request) {
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
