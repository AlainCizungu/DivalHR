package com.divalhr.core.platform.web;

import com.divalhr.core.platform.ratelimit.SubjectRateLimitInterceptor;
import com.divalhr.core.platform.ratelimit.TenantRateLimitInterceptor;
import com.divalhr.core.platform.security.ScopeAuthorizationInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers platform-level MVC interceptors. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

  private final ScopeAuthorizationInterceptor scopeAuthorizationInterceptor;
  private final SubjectRateLimitInterceptor subjectRateLimitInterceptor;
  private final TenantRateLimitInterceptor tenantRateLimitInterceptor;

  /**
   * Creates the configuration.
   *
   * @param scopeAuthorizationInterceptor scope authorization interceptor
   * @param subjectRateLimitInterceptor per-subject rate limit (MVP-012B)
   * @param tenantRateLimitInterceptor per-tenant rate limit (MVP-020)
   */
  public WebConfig(
      ScopeAuthorizationInterceptor scopeAuthorizationInterceptor,
      SubjectRateLimitInterceptor subjectRateLimitInterceptor,
      TenantRateLimitInterceptor tenantRateLimitInterceptor) {
    this.scopeAuthorizationInterceptor = scopeAuthorizationInterceptor;
    this.subjectRateLimitInterceptor = subjectRateLimitInterceptor;
    this.tenantRateLimitInterceptor = tenantRateLimitInterceptor;
  }

  /**
   * Order matters (MVP-012B B5, MVP-020 A20-4): authorization first, then the per-subject limit,
   * then the per-tenant limit, all before any argument or body binding.
   */
  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(scopeAuthorizationInterceptor);
    registry.addInterceptor(subjectRateLimitInterceptor);
    registry.addInterceptor(tenantRateLimitInterceptor);
  }
}
