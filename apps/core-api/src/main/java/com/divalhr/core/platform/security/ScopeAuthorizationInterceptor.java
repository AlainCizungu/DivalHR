package com.divalhr.core.platform.security;

import com.divalhr.core.platform.audit.AuthorizationDenial.Scope;
import com.divalhr.core.platform.audit.AuthorizationDenial.Stage;
import com.divalhr.core.platform.audit.AuthorizationDenialAudit;
import com.divalhr.core.platform.audit.AuthorizationDenialAudit.Outcome;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
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
 * <p>MVP-013 (Issue #43): every denial from step 2 on, where the token carries a verified subject,
 * is also recorded as append-only evidence through {@link AuthorizationDenialAudit} before the
 * denial is thrown; the public response never changes. The subject appears only in that row, never
 * in the security log (D11). A request that passes is marked with its {@link AuthorizedOperation}.
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
  private final AuthorizationDenialAudit audit;

  /**
   * Creates the interceptor.
   *
   * @param metrics operation metrics
   * @param tenants verified tenant resolver
   * @param memberships the Core-side tenant access authority (MVP-012A)
   * @param audit durable denial evidence (MVP-013)
   */
  public ScopeAuthorizationInterceptor(
      OperationMetrics metrics,
      TenantContextResolver tenants,
      MembershipAuthority memberships,
      AuthorizationDenialAudit audit) {
    this.metrics = metrics;
    this.tenants = tenants;
    this.memberships = memberships;
    this.audit = audit;
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
      Gate gate = new Gate(request, authentication, Scope.PLATFORM, platform.operation());
      requireSubject(gate, PlatformScoped.ROLE);
      requireRole(gate, PlatformScoped.ROLE);
      requireAssurance(gate, PlatformScoped.ROLE);
      // Never the token's tenant: platform operations have no effective tenant.
      new AuthorizedOperation(Scope.PLATFORM, platform.operation(), null).bind(request);
      return true;
    }
    Gate gate = new Gate(request, authentication, Scope.TENANT, tenant.operation());
    requireSubject(gate, tenant.role());
    if (!tenant.role().isEmpty()) {
      requireRole(gate, tenant.role());
    }
    TenantContext context = requireTenant(gate, tenant.role());
    if (AssuranceEvidence.requiredFor(tenant.role())) {
      requireAssurance(gate, tenant.role());
    }
    // Step 5, only after MFA so that a password-level session learns nothing about memberships.
    requireMembership(gate, context.tenantId(), tenant.role());
    // The tenant is effective only now: verified token tenant confirmed by an active membership.
    new AuthorizedOperation(Scope.TENANT, tenant.operation(), context.tenantId()).bind(request);
    return true;
  }

  /** The request being authorized; the subject is read from the authentication, never logged. */
  private record Gate(
      HttpServletRequest request, Authentication authentication, Scope scope, String operation) {

    String scopeValue() {
      return scope.value();
    }
  }

  /**
   * Step 3: tenant-scoped calls need a valid verified tenant. The resolver's {@code
   * TENANT_CONTEXT_MISSING} is rethrown unchanged after the safe log, metric and evidence (D10);
   * the unvalidated claim is never recorded.
   */
  private TenantContext requireTenant(Gate gate, String role) {
    try {
      return tenants.current();
    } catch (ApiException missing) {
      if (missing.code() != ErrorCode.TENANT_CONTEXT_MISSING) {
        throw missing;
      }
      Outcome outcome =
          audit.record(
              gate.request(),
              gate.authentication(),
              gate.scope(),
              Stage.TENANT_CONTEXT,
              gate.operation(),
              null);
      if (!gate.operation().isEmpty()) {
        metrics.record(gate.operation(), OperationMetrics.Outcome.DENIED);
      }
      var event =
          SECURITY_LOG
              .atWarn()
              .addKeyValue("event", "authorization_denied")
              .addKeyValue("reason", "tenant_context_missing")
              .addKeyValue("operation", gate.operation())
              .addKeyValue("scope", gate.scopeValue());
      if (!role.isEmpty()) {
        event = event.addKeyValue("requiredRole", role);
      }
      event
          .addKeyValue("result", "DENIED")
          .addKeyValue("audit", outcome.value())
          .log("authorization_denied");
      throw missing;
    }
  }

  /**
   * MVP-012A: the caller must hold an active membership in the token's tenant with exactly the
   * required role (M2: no role hierarchy), which the token already proved in step 2. A role-less
   * tenant operation accepts a membership whose role the token also holds. Any mismatch gives the
   * same {@code ACCESS_DENIED} as a missing role, with no membership-specific detail; the log
   * carries only the safe authorization fields. A lookup failure propagates and fails closed (M5).
   */
  private void requireMembership(Gate gate, TenantId tenant, String role) {
    Authentication authentication = gate.authentication();
    String operation = gate.operation();
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
    // The token's tenant is not effective here (it may be foreign): no tenant in the evidence.
    Outcome outcome =
        audit.record(
            gate.request(), authentication, gate.scope(), Stage.MEMBERSHIP, operation, null);
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
    event
        .addKeyValue("result", "DENIED")
        .addKeyValue("audit", outcome.value())
        .log("authorization_denied");
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
  private void requireAssurance(Gate gate, String role) {
    if (gate.authentication() instanceof JwtAuthenticationToken token
        && AssuranceEvidence.provesMfa(token.getToken())) {
      return;
    }
    String operation = gate.operation();
    // Before the membership gate: the token's tenant is not effective, so none is recorded.
    Outcome outcome =
        audit.record(
            gate.request(), gate.authentication(), gate.scope(), Stage.MFA, operation, null);
    if (!operation.isEmpty()) {
      metrics.record(operation, OperationMetrics.Outcome.MFA_REQUIRED);
    }
    SECURITY_LOG
        .atWarn()
        .addKeyValue("event", "authorization_denied")
        .addKeyValue("reason", "mfa_required")
        .addKeyValue("operation", operation)
        .addKeyValue("scope", gate.scopeValue())
        .addKeyValue("requiredRole", role)
        .addKeyValue("result", "DENIED")
        .addKeyValue("audit", outcome.value())
        .log("authorization_denied");
    throw new MfaRequiredException();
  }

  /**
   * Scoped operations are keyed (idempotency, audit) on the verified JWT subject, so a caller that
   * is not a JWT, or whose {@code sub} is missing or blank, is denied before role, tenant, query,
   * argument or body processing. The subject value itself is never logged.
   */
  private void requireSubject(Gate gate, String role) {
    if (gate.authentication() instanceof JwtAuthenticationToken token) {
      String subject = token.getToken().getSubject();
      if (subject != null && !subject.isBlank()) {
        return;
      }
    }
    String operation = gate.operation();
    // No verified actor: never durable, never attributed (A13-3).
    Outcome outcome = audit.subjectMissing(gate.request(), gate.scope());
    if (!operation.isEmpty()) {
      metrics.record(operation, OperationMetrics.Outcome.DENIED);
    }
    var event =
        SECURITY_LOG
            .atWarn()
            .addKeyValue("event", "authorization_denied")
            .addKeyValue("reason", "subject_missing")
            .addKeyValue("operation", operation)
            .addKeyValue("scope", gate.scopeValue());
    if (!role.isEmpty()) {
      event = event.addKeyValue("requiredRole", role);
    }
    event
        .addKeyValue("result", "DENIED")
        .addKeyValue("audit", outcome.value())
        .log("authorization_denied");
    throw new AccessDeniedException("verified subject required");
  }

  /**
   * Step 2. The security log never names the actor (D11): the subject is kept only in the durable
   * denial row.
   */
  private void requireRole(Gate gate, String role) {
    Authentication authentication = gate.authentication();
    String authority = "ROLE_" + role;
    boolean allowed =
        authentication != null
            && authentication.getAuthorities().stream()
                .anyMatch(granted -> authority.equals(granted.getAuthority()));
    if (allowed) {
      return;
    }
    String operation = gate.operation();
    Outcome outcome =
        audit.record(gate.request(), authentication, gate.scope(), Stage.ROLE, operation, null);
    if (!operation.isEmpty()) {
      metrics.record(operation, OperationMetrics.Outcome.DENIED);
    }
    SECURITY_LOG
        .atWarn()
        .addKeyValue("event", "authorization_denied")
        .addKeyValue("reason", "role_missing")
        .addKeyValue("operation", operation)
        .addKeyValue("scope", gate.scopeValue())
        .addKeyValue("requiredRole", role)
        .addKeyValue("result", "DENIED")
        .addKeyValue("audit", outcome.value())
        .log("authorization_denied");
    throw new AccessDeniedException(role + " required");
  }
}
