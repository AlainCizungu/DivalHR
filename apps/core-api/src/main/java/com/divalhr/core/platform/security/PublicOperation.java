package com.divalhr.core.platform.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an anonymous {@code /api/v1/public/**} handler ({@code x-divalhr-scope: public}). Such
 * handlers are served by a separate security filter chain that neither requires nor reads access
 * tokens, and must never read tenant data through the caller's identity. Every other {@code
 * /api/v1} handler declares exactly one of {@link PlatformScoped} or {@link TenantScoped}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PublicOperation {

  /**
   * Stable operation name for metrics.
   *
   * @return operation name, e.g. {@code invitation.accept}
   */
  String operation();
}
