package com.divalhr.core.platform.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a handler whose verified caller is rate-limited per subject (MVP-012B, B5). The check runs
 * in {@link SubjectRateLimitInterceptor}, after the scope interceptor has authorized the caller and
 * before any argument or body binding. Handlers sharing a bucket share one quota per subject.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SubjectRateLimited {

  /**
   * Stable, low-cardinality bucket name shared by the handlers of one quota.
   *
   * @return bucket
   */
  String bucket();
}
