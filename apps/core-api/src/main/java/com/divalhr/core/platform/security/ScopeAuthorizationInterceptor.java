package com.divalhr.core.platform.security;

import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.tenancy.TenantContextResolver;
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
 * before the request body or query parameters are read: the caller must be a JWT with a non-blank
 * {@code sub}, the required role must be held explicitly, and tenant-scoped calls must carry a
 * verified tenant. Denials write the safe structured security log and a {@code denied} metric.
 * Method security stays in force behind this interceptor.
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
    // Scope detection comes first: public handlers are never affected by the checks below.
    PlatformScoped platform = method.getMethodAnnotation(PlatformScoped.class);
    TenantScoped tenant =
        platform != null
            ? null
            : AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), TenantScoped.class);
    if (platform == null && tenant == null) {
      return true;
    }
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (platform != null) {
      requireSubject(authentication, "platform", PlatformScoped.ROLE, platform.operation());
      requireRole(authentication, PlatformScoped.ROLE, platform.operation());
      return true;
    }
    requireSubject(authentication, "tenant", tenant.role(), tenant.operation());
    if (!tenant.role().isEmpty()) {
      requireRole(authentication, tenant.role(), tenant.operation());
    }
    // Throws TENANT_CONTEXT_MISSING when the verified token carries no valid tenant.
    tenants.current();
    return true;
  }

  /**
   * Scoped operations are keyed (idempotency, audit) on the verified JWT subject, so a caller that
   * is not a JWT, or whose {@code sub} is missing or blank, is denied before role, tenant, query,
   * argument or body processing. The subject value itself is never logged.
   */
  private void requireSubject(
      Authentication authentication, String scope, String role, String operation) {
    if (authentication instanceof JwtAuthenticationToken token) {
      String subject = token.getToken().getSubject();
      if (subject != null && !subject.isBlank()) {
        return;
      }
    }
    if (!operation.isEmpty()) {
      metrics.record(operation, OperationMetrics.Outcome.DENIED);
    }
    var event =
        SECURITY_LOG
            .atWarn()
            .addKeyValue("event", "authorization_denied")
            .addKeyValue("reason", "subject_missing")
            .addKeyValue("operation", operation)
            .addKeyValue("scope", scope);
    if (!role.isEmpty()) {
      event = event.addKeyValue("requiredRole", role);
    }
    event.addKeyValue("result", "DENIED").log("authorization_denied");
    throw new AccessDeniedException("verified subject required");
  }

  private void requireRole(Authentication authentication, String role, String operation) {
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
