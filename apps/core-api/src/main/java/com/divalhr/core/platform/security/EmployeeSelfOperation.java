package com.divalhr.core.platform.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.core.annotation.AliasFor;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * A tenant-scoped self-service operation of an employee (MVP-030): the caller acts only on records
 * bound to their own active employee-access link. Enforced by the scope interceptor before the
 * request is read (verified subject, role {@code employee}, verified tenant, active membership with
 * that role; no MFA, which employees do not use) and by method security.
 *
 * <p>Not a privileged operation (A30-1): its denials go to the safe security log and the bounded
 * counter {@code divalhr.employee.self_service.denials}, never to the durable privileged-denial
 * evidence of MVP-013.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@TenantScoped(role = EmployeeSelfOperation.ROLE)
@PreAuthorize("hasRole('" + EmployeeSelfOperation.ROLE + "')")
public @interface EmployeeSelfOperation {

  /** Required role. */
  String ROLE = "employee";

  /**
   * Stable, low-cardinality operation name, e.g. {@code contract.acknowledge}.
   *
   * @return operation name
   */
  @AliasFor(annotation = TenantScoped.class, attribute = "operation")
  String operation();
}
