package com.divalhr.core.platform.access;

import com.divalhr.core.platform.tenancy.TenantId;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * What the statutory preview of the payroll module asks about earnings it does not model (MVP-094B,
 * ADR 0034 section 6, decision D94B-3): an allowance, a benefit in kind, a bonus, overtime pay or
 * retro pay. The preview models the contractual base salary only, so an employee with any other
 * earning in the period is blocked ({@code UNMODELED_EARNINGS}) instead of being calculated as if
 * the earning did not exist.
 *
 * <p>The shipped implementation reports none, because no other earning type exists in the data
 * model today; a future module that introduces one implements this port and the preview blocks
 * those employees without any change to the calculation. The port reads, never writes, and returns
 * identifiers only.
 */
public interface StatutoryEarningsGuard {

  /**
   * The employees that have an earning other than the contractual base salary in the period.
   *
   * @param tenant verified tenant
   * @param employeeIds the employees of a payroll run
   * @param first first day of the period
   * @param last last day of the period
   * @return the employees with an unmodeled earning (a subset of the given ones), possibly empty
   */
  Set<UUID> employeesWithUnmodeledEarnings(
      TenantId tenant, Collection<UUID> employeeIds, LocalDate first, LocalDate last);
}
