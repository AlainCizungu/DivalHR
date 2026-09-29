package com.divalhr.core.platform.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Marks a platform-scoped operation: it acts across tenants (for example provisioning a new
 * organization) and never derives scope from the caller's {@code tenant_id} claim. Only {@code
 * platform-admin} may invoke it.
 *
 * <p>Enforced twice: by {@link PlatformScopeInterceptor} before the request body is read, and by
 * method security through the {@link PreAuthorize} meta-annotation.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasRole('" + PlatformScoped.ROLE + "')")
public @interface PlatformScoped {

  /** Role required for platform-scoped operations. */
  String ROLE = "platform-admin";

  /**
   * Stable, low-cardinality operation name used in logs and metrics.
   *
   * @return operation name, e.g. {@code organization.create}
   */
  String operation();
}
