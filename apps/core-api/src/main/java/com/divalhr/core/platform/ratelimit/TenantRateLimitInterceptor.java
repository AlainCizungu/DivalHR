package com.divalhr.core.platform.ratelimit;

import com.divalhr.core.platform.audit.AuthorizationDenial.Stage;
import com.divalhr.core.platform.audit.AuthorizationDenialAudit;
import com.divalhr.core.platform.audit.AuthorizationDenialAudit.Outcome;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.security.AuthorizedOperation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies {@link TenantRateLimited} (MVP-020, A20-4). Registered after the scope interceptor and
 * the per-subject limit, so only fully authorized requests are counted, and before any argument or
 * body is read. The tenant is the effective tenant the scope interceptor established; a request
 * without one is refused rather than counted. The log and metric carry the bucket and operation
 * only. The tenant's first refusal in a window becomes MVP-013 denial evidence; later ones are
 * telemetry.
 */
@Component
public class TenantRateLimitInterceptor implements HandlerInterceptor {

  private static final Logger SECURITY_LOG = LoggerFactory.getLogger("divalhr.security");

  private final TenantRateLimiter limiter;
  private final OperationMetrics metrics;
  private final AuthorizationDenialAudit audit;

  /**
   * Creates the interceptor.
   *
   * @param limiter per-tenant limiter
   * @param metrics operation metrics
   * @param audit durable denial evidence (MVP-013)
   */
  public TenantRateLimitInterceptor(
      TenantRateLimiter limiter, OperationMetrics metrics, AuthorizationDenialAudit audit) {
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
    TenantRateLimited limit = method.getMethodAnnotation(TenantRateLimited.class);
    if (limit == null) {
      return true;
    }
    AuthorizedOperation authorized = AuthorizedOperation.of(request);
    if (authorized == null || authorized.effectiveTenant() == null) {
      throw new AccessDeniedException("effective tenant required");
    }
    try {
      limiter.acquire(limit.bucket(), authorized.effectiveTenant());
    } catch (TenantRateLimitedException limited) {
      String evidence = "not_first";
      if (limited.firstInWindow()) {
        Outcome outcome =
            audit.record(
                request,
                SecurityContextHolder.getContext().getAuthentication(),
                authorized.scope(),
                Stage.RATE_LIMIT,
                authorized.operation(),
                authorized.effectiveTenant());
        evidence = outcome.value();
      }
      if (!authorized.operation().isEmpty()) {
        metrics.record(authorized.operation(), OperationMetrics.Outcome.RATE_LIMITED);
      }
      SECURITY_LOG
          .atWarn()
          .addKeyValue("event", "rate_limited")
          .addKeyValue("bucket", limit.bucket())
          .addKeyValue("scope", "tenant")
          .addKeyValue("operation", authorized.operation())
          .addKeyValue("audit", evidence)
          .log("rate_limited");
      throw limited;
    }
    return true;
  }
}
