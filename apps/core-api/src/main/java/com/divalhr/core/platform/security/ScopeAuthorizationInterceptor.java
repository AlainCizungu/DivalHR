package com.divalhr.core.platform.security;

import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.tenancy.TenantContextResolver;
import com.divalhr.core.platform.web.CorrelationId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces {@link PlatformScoped} and {@link TenantScoped} (including {@link TenantAdminOperation})
 * before the request body or query parameters are read: the required role must be held explicitly,
 * and tenant-scoped calls must carry a verified tenant. Denials write the safe structured security
 * log and a {@code denied} metric. Method security stays in force behind this interceptor.
 */
@Component
public class ScopeAuthorizationInterceptor implements HandlerInterceptor {

  private static final Logger SECURITY_LOG = LoggerFactory.getLogger("divalhr.security");

  private final OperationMetrics metrics;
  private final TenantContextResolver tenants;

  /**
   * Creates the interceptor.
   *
   * @param metrics operation metrics
   * @param tenants verified tenant resolver
   */
  public ScopeAuthorizationInterceptor(OperationMetrics metrics, TenantContextResolver tenants) {
    this.metrics = metrics;
    this.tenants = tenants;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod method)) {
      return true;
    }
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    PlatformScoped platform = method.getMethodAnnotation(PlatformScoped.class);
    if (platform != null) {
      requireRole(authentication, PlatformScoped.ROLE, platform.operation(), request);
      return true;
    }
    TenantScoped tenant =
        AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), TenantScoped.class);
    if (tenant != null) {
      if (!tenant.role().isEmpty()) {
        requireRole(authentication, tenant.role(), tenant.operation(), request);
      }
      // Throws TENANT_CONTEXT_MISSING when the verified token carries no valid tenant.
      tenants.current();
    }
    return true;
  }

  private void requireRole(
      Authentication authentication, String role, String operation, HttpServletRequest request) {
    String authority = "ROLE_" + role;
    boolean allowed =
        authentication != null
            && authentication.getAuthorities().stream()
                .anyMatch(granted -> authority.equals(granted.getAuthority()));
    if (allowed) {
      return;
    }
    if (!operation.isEmpty()) {
      metrics.record(operation, OperationMetrics.Outcome.DENIED);
    }
    SECURITY_LOG
        .atWarn()
        .addKeyValue("event", "authorization_denied")
        .addKeyValue("operation", operation)
        .addKeyValue("requiredRole", role)
        .addKeyValue("result", "DENIED")
        .addKeyValue("actorSubject", subject(authentication))
        .addKeyValue("correlationId", request.getAttribute(CorrelationId.REQUEST_ATTRIBUTE))
        .log("authorization_denied");
    throw new AccessDeniedException(role + " required");
  }

  private static String subject(Authentication authentication) {
    if (authentication instanceof JwtAuthenticationToken token) {
      return token.getToken().getSubject();
    }
    return "unknown";
  }
}
