package com.divalhr.core.platform.security;

import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.web.CorrelationId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Rejects non-platform-administrators before the request body is read, and writes the safe
 * structured security log line and denial metric for privileged operations. Method security
 * ({@link PlatformScoped}'s {@code @PreAuthorize}) remains in force behind it.
 */
@Component
public class PlatformScopeInterceptor implements HandlerInterceptor {

  private static final Logger SECURITY_LOG = LoggerFactory.getLogger("divalhr.security");
  private static final String REQUIRED_AUTHORITY = "ROLE_" + PlatformScoped.ROLE;

  private final OperationMetrics metrics;

  /**
   * Creates the interceptor.
   *
   * @param metrics operation metrics
   */
  public PlatformScopeInterceptor(OperationMetrics metrics) {
    this.metrics = metrics;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod method)) {
      return true;
    }
    PlatformScoped scope = method.getMethodAnnotation(PlatformScoped.class);
    if (scope == null) {
      return true;
    }
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    boolean allowed =
        authentication != null
            && authentication.getAuthorities().stream()
                .anyMatch(authority -> REQUIRED_AUTHORITY.equals(authority.getAuthority()));
    if (allowed) {
      return true;
    }
    metrics.record(scope.operation(), OperationMetrics.Outcome.DENIED);
    SECURITY_LOG
        .atWarn()
        .addKeyValue("event", "authorization_denied")
        .addKeyValue("operation", scope.operation())
        .addKeyValue("result", "DENIED")
        .addKeyValue("actorSubject", subject(authentication))
        .addKeyValue("correlationId", request.getAttribute(CorrelationId.REQUEST_ATTRIBUTE))
        .log("authorization_denied");
    throw new AccessDeniedException("platform-admin required");
  }

  private static String subject(Authentication authentication) {
    if (authentication instanceof JwtAuthenticationToken token) {
      return token.getToken().getSubject();
    }
    return "unknown";
  }
}
