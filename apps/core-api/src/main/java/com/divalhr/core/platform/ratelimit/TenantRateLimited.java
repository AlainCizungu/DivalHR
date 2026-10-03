package com.divalhr.core.platform.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a tenant-scoped handler whose verified tenant is rate-limited (MVP-020, A20-4). The check
 * runs in {@link TenantRateLimitInterceptor}, after the scope interceptor authorized the caller and
 * established the effective tenant, after the per-subject limit, and before any argument or body is
 * read. The bucket must be configured under {@code divalhr.request-limits.tenant}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TenantRateLimited {

  /**
   * Stable, low-cardinality bucket name.
   *
   * @return bucket
   */
  String bucket();
}
