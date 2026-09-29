package com.divalhr.core.tenant.api;

import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.web.CorrelationId;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Request-derived values shared by the tenant controllers. */
final class AuthenticatedCaller {

  private AuthenticatedCaller() {}

  /**
   * The verified, non-blank JWT subject. Idempotency and audit are keyed on it, so tokens without
   * one are refused.
   *
   * @param authentication verified token
   * @return subject
   */
  static String subject(JwtAuthenticationToken authentication) {
    String subject = authentication.getToken().getSubject();
    if (subject == null || subject.isBlank()) {
      throw new ApiException(ErrorCode.ACCESS_DENIED, Map.of());
    }
    return subject;
  }

  /**
   * The request correlation ID set by the correlation filter.
   *
   * @param request current request
   * @return correlation ID
   */
  static String correlationId(HttpServletRequest request) {
    return String.valueOf(request.getAttribute(CorrelationId.REQUEST_ATTRIBUTE));
  }
}
