package com.divalhr.core.platform.web;

import com.divalhr.core.platform.security.PlatformScopeInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers platform-level MVC interceptors. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

  private final PlatformScopeInterceptor platformScopeInterceptor;

  /**
   * Creates the configuration.
   *
   * @param platformScopeInterceptor platform-scope interceptor
   */
  public WebConfig(PlatformScopeInterceptor platformScopeInterceptor) {
    this.platformScopeInterceptor = platformScopeInterceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(platformScopeInterceptor);
  }
}
