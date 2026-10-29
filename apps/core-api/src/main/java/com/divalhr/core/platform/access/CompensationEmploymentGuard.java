package com.divalhr.core.platform.access;

import com.divalhr.core.platform.tenancy.TenantId;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * What the people module may ask of payroll before it ends an employment (MVP-093A, ADR 0009, ADR
 * 0032), implemented by {@code payroll}. People never reads payroll's tables; a separation never
 * rewrites compensation.
 */
public interface CompensationEmploymentGuard {

  /**
   * The first day of the employee's latest active monthly compensation interval.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @return that day, or empty when the employee has no compensation
   */
  Optional<LocalDate> latestCompensationStart(TenantId tenant, UUID employeeId);
}
