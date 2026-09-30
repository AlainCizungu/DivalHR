package com.divalhr.core.platform.tenancy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a repository operation that deliberately runs without the verified tenant, for example a
 * lookup by a 256-bit single-use token or a background job claiming due rows across tenants. The
 * architecture test requires every other public repository operation to take the verified {@link
 * TenantId}. Such operations must never return data to a caller of another tenant.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CrossTenantAccess {

  /**
   * Why the operation cannot be tenant-scoped.
   *
   * @return reason
   */
  String value();
}
