package com.divalhr.core.platform.ratelimit;

import com.divalhr.core.platform.audit.AuthorizationDenial.Stage;
import com.divalhr.core.platform.audit.AuthorizationDenialAudit;
import com.divalhr.core.platform.audit.AuthorizationDenialAudit.Outcome;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.security.AuthorizedOperation;
import com.divalhr.core.platform.security.TenantScoped;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies {@link SubjectRateLimited} (MVP-012B, B5). Registered after the scope interceptor, so
 * only callers that passed the subject, role, tenant, MFA and membership checks are counted, and it
 * runs before argument and body binding, so authorized malformed requests consume the quota too.
 * The log and metric carry the bucket and operation only: never the subject or request values.
 *
 * <p>MVP-013 (D6, A13-2): only the subject's first refusal in a window becomes denial evidence,
 * with the effective tenant the scope interceptor established; later refusals in the same window
 * are telemetry only, so a limited caller cannot turn refusals into audit writes.
 */
@Component
public class SubjectRateLimitInterceptor implements HandlerInterceptor {

  private static final Logger SECURITY_LOG = LoggerFactory.getLogger("divalhr.security");

  private final SubjectRateLimiter limiter;
  private final OperationMetrics metrics;
  private final AuthorizationDenialAudit audit;

  /**
   * Creates the interceptor.
   *
   * @param limiter per-subject limiter
   * @param metrics operation metrics
   * @param audit durable denial evidence (MVP-013)
   */
  public SubjectRateLimitInterceptor(
      SubjectRateLimiter limiter, OperationMetrics metrics, AuthorizationDenialAudit audit) {
    this.limiter = limiter;
    this.metrics = metrics;
    this.audit = audit;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod method)) {
      return true;
    }
    SubjectRateLimited limit = method.getMethodAnnotation(SubjectRateLimited.class);
    if (limit == null) {
      return true;
    }
    // The scope interceptor already guaranteed a JWT with a non-blank subject; anything else is
    // refused rather than counted under a shared key.
    if (!(SecurityContextHolder.getContext().getAuthentication()
        instanceof JwtAuthenticationToken token)) {
      throw new AccessDeniedException("verified subject required");
    }
    String subject = token.getToken().getSubject();
    if (subject == null || subject.isBlank()) {
      throw new AccessDeniedException("verified subject required");
    }
    try {
      limiter.acquire(limit.bucket(), subject);
    } catch (SubjectRateLimitedException limited) {
      TenantScoped scoped =
          AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), TenantScoped.class);
      String operation = scoped == null ? "" : scoped.operation();
      String evidence = "not_first";
      AuthorizedOperation authorized = AuthorizedOperation.of(request);
      if (limited.firstInWindow() && authorized != null) {
        Outcome outcome =
            audit.record(
                request,
                token,
                authorized.scope(),
                Stage.RATE_LIMIT,
                authorized.operation(),
                authorized.effectiveTenant());
        evidence = outcome.value();
      }
      if (!operation.isEmpty()) {
        metrics.record(operation, OperationMetrics.Outcome.RATE_LIMITED);
      }
      SECURITY_LOG
          .atWarn()
          .addKeyValue("event", "rate_limited")
          .addKeyValue("bucket", limit.bucket())
          .addKeyValue("operation", operation)
          .addKeyValue("audit", evidence)
          .log("rate_limited");
      throw limited;
    }
    return true;
  }
}
