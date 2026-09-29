package com.divalhr.core.platform.web;

import com.divalhr.core.platform.security.ScopeAuthorizationInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers platform-level MVC interceptors. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

  private final ScopeAuthorizationInterceptor scopeAuthorizationInterceptor;

  /**
   * Creates the configuration.
   *
   * @param scopeAuthorizationInterceptor scope authorization interceptor
   */
  public WebConfig(ScopeAuthorizationInterceptor scopeAuthorizationInterceptor) {
    this.scopeAuthorizationInterceptor = scopeAuthorizationInterceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(scopeAuthorizationInterceptor);
  }
}
