package com.divalhr.core.platform.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.core.annotation.AliasFor;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * A tenant-scoped operation reserved for {@code tenant-admin}. The platform-admin role grants no
 * implicit access. Enforced by the scope interceptor before the request is read and by method
 * security through {@link PreAuthorize}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@TenantScoped(role = TenantAdminOperation.ROLE)
@PreAuthorize("hasRole('" + TenantAdminOperation.ROLE + "')")
public @interface TenantAdminOperation {

  /** Required role. */
  String ROLE = "tenant-admin";

  /**
   * Stable, low-cardinality operation name, e.g. {@code legal-entity.create}.
   *
   * @return operation name
   */
  @AliasFor(annotation = TenantScoped.class, attribute = "operation")
  String operation();
}
