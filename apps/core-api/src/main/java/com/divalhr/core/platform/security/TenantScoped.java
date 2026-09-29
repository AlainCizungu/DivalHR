package com.divalhr.core.platform.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a tenant-scoped operation: it must resolve the caller's verified tenant through {@code
 * TenantContextResolver} or {@code TenantAccessGuard} and must never trust a tenant supplied in the
 * request. Every mutating {@code /api/v1} handler carries exactly one of {@link PlatformScoped} or
 * this annotation (enforced by a test).
 */
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TenantScoped {

  /**
   * Stable, low-cardinality operation name for logs and metrics; empty when not instrumented.
   *
   * @return operation name
   */
  String operation() default "";

  /**
   * Realm role required in addition to a verified tenant; empty when any tenant member may call. A
   * platform role never satisfies it implicitly.
   *
   * @return required role
   */
  String role() default "";
}
