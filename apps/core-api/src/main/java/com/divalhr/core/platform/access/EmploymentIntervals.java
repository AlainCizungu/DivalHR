package com.divalhr.core.platform.access;

import com.divalhr.core.platform.tenancy.TenantId;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the payroll module may know about an employee's employments to record compensation
 * (MVP-093A, ADR 0009, ADR 0032), implemented by {@code people}. Payroll never reads people's
 * tables.
 */
public interface EmploymentIntervals {

  /**
   * The employee's employments without locking them (previews and reads, inside the caller's
   * transaction).
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @return the employments by start date, or empty when the employee is not in the tenant
   */
  Optional<List<Interval>> read(TenantId tenant, UUID employeeId);

  /**
   * The same employments with every row locked {@code FOR SHARE} until the caller's transaction
   * ends (MANDATORY; lock order step 2, rows in identifier order): a change, correction or
   * separation of an employment waits, and no employment version can move under the command. The
   * result is read after the locks are held.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @return the employments by start date, or empty when the employee is not in the tenant
   */
  Optional<List<Interval>> lockShared(TenantId tenant, UUID employeeId);

  /**
   * One employment of an employee.
   *
   * @param id employment
   * @param version its timeline version
   * @param start its first day
   * @param end its last day, or {@code null} while open
   */
  record Interval(UUID id, long version, LocalDate start, LocalDate end) {}
}
