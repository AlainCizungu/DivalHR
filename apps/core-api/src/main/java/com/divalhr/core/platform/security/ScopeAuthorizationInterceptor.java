package com.divalhr.core.platform.security;

import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.tenancy.MembershipAuthority;
import com.divalhr.core.platform.tenancy.MembershipAuthority.ActiveMembership;
import com.divalhr.core.platform.tenancy.TenantContext;
import com.divalhr.core.platform.tenancy.TenantContextResolver;
import com.divalhr.core.platform.tenancy.TenantId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import java.util.Set;
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
 * before the request body or query parameters are read, in this order: the caller must be a JWT
 * with a non-blank {@code sub}; the required role must be held explicitly; tenant-scoped calls must
 * carry a verified tenant; and operations for a privileged role ({@code platform-admin}, {@code
 * tenant-admin}) require the token to prove multifactor authentication ({@link AssuranceEvidence},
 * MVP-011); finally, tenant-scoped calls require a matching active tenant membership (MVP-012A,
 * architect decision on #37, A1 and M1): effective access is the intersection of the token and the
 * membership. Denials write the safe structured security log and a metric. Method security stays in
 * force behind this interceptor as defense in depth.
 *
 * <p>Platform-scoped and public handlers return before any membership lookup.
 */
@Component
public class ScopeAuthorizationInterceptor implements HandlerInterceptor {

  private static final Logger SECURITY_LOG = LoggerFactory.getLogger("divalhr.security");

  /** Tenant roles a membership can carry; {@code platform-admin} is never one of them. */
  private static final Set<String> TENANT_ROLES = Set.of("tenant-admin", "employee");

  private final OperationMetrics metrics;
  private final TenantContextResolver tenants;
  private final MembershipAuthority memberships;

  /**
   * Creates the interceptor.
   *
   * @param metrics operation metrics
   * @param tenants verified tenant resolver
   * @param memberships the Core-side tenant access authority (MVP-012A)
   */
  public ScopeAuthorizationInterceptor(
      OperationMetrics metrics, TenantContextResolver tenants, MembershipAuthority memberships) {
    this.metrics = metrics;
    this.tenants = tenants;
    this.memberships = memberships;
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
      requireAssurance(authentication, "platform", PlatformScoped.ROLE, platform.operation());
      return true;
    }
    requireSubject(authentication, "tenant", tenant.role(), tenant.operation());
    if (!tenant.role().isEmpty()) {
      requireRole(authentication, tenant.role(), tenant.operation());
    }
    // Throws TENANT_CONTEXT_MISSING when the verified token carries no valid tenant.
    TenantContext context = tenants.current();
    if (AssuranceEvidence.requiredFor(tenant.role())) {
      requireAssurance(authentication, "tenant", tenant.role(), tenant.operation());
    }
    // Step 5, only after MFA so that a password-level session learns nothing about memberships.
    requireMembership(authentication, context.tenantId(), tenant.role(), tenant.operation());
    return true;
  }

  /**
   * MVP-012A: the caller must hold an active membership in the token's tenant with exactly the
   * required role (M2: no role hierarchy), which the token already proved in step 2. A role-less
   * tenant operation accepts a membership whose role the token also holds. Any mismatch gives the
   * same {@code ACCESS_DENIED} as a missing role, with no membership-specific detail; the log
   * carries only the safe authorization fields. A lookup failure propagates and fails closed (M5).
   */
  private void requireMembership(
      Authentication authentication, TenantId tenant, String role, String operation) {
    String subject = ((JwtAuthenticationToken) authentication).getToken().getSubject();
    Optional<ActiveMembership> membership = memberships.find(subject);
    boolean allowed =
        membership
            .filter(
                found ->
                    role.isEmpty()
                        ? found.tenant().equals(tenant)
                            && TENANT_ROLES.contains(found.role())
                            && hasRole(authentication, found.role())
                        : found.grants(tenant, role))
            .isPresent();
    if (allowed) {
      return;
    }
    if (!operation.isEmpty()) {
      metrics.record(operation, OperationMetrics.Outcome.DENIED);
    }
    var event =
        SECURITY_LOG
            .atWarn()
            .addKeyValue("event", "authorization_denied")
            .addKeyValue("reason", "membership_mismatch")
            .addKeyValue("operation", operation)
            .addKeyValue("scope", "tenant");
    if (!role.isEmpty()) {
      event = event.addKeyValue("requiredRole", role);
    }
    event.addKeyValue("result", "DENIED").log("authorization_denied");
    throw new AccessDeniedException("tenant membership required");
  }

  private static boolean hasRole(Authentication authentication, String role) {
    String authority = "ROLE_" + role;
    return authentication.getAuthorities().stream()
        .anyMatch(granted -> authority.equals(granted.getAuthority()));
  }

  /**
   * Privileged operations need the verified token to prove multifactor authentication for this
   * session. Checked after subject, role and tenant, and before any argument or body binding. The
   * log carries only the safe authorization fields: never the subject, claims or assurance value.
   */
  private void requireAssurance(
      Authentication authentication, String scope, String role, String operation) {
    if (authentication instanceof JwtAuthenticationToken token
        && AssuranceEvidence.provesMfa(token.getToken())) {
      return;
    }
    if (!operation.isEmpty()) {
      metrics.record(operation, OperationMetrics.Outcome.MFA_REQUIRED);
    }
    SECURITY_LOG
        .atWarn()
        .addKeyValue("event", "authorization_denied")
        .addKeyValue("reason", "mfa_required")
        .addKeyValue("operation", operation)
        .addKeyValue("scope", scope)
        .addKeyValue("requiredRole", role)
        .addKeyValue("result", "DENIED")
        .log("authorization_denied");
    throw new MfaRequiredException();
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
