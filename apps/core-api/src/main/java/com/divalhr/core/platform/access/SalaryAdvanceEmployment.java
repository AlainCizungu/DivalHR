package com.divalhr.core.platform.access;

import com.divalhr.core.platform.tenancy.TenantId;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * What payroll may ask of people to evaluate a salary-advance request (MVP-120A, ADR 0041),
 * implemented by {@code people}. Payroll never reads people's tables; the port returns dates and
 * identifiers only.
 */
public interface SalaryAdvanceEmployment {

  /**
   * The start date of the employment covering a date.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param on business date
   * @return the employment start, if the employee is employed on the date
   */
  Optional<LocalDate> employmentStart(TenantId tenant, UUID employeeId, LocalDate on);

  /**
   * The legal entity of the employee's active placement on a date.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param on business date
   * @return the legal entity (the lowest identifier when several), if the employee is placed
   */
  Optional<UUID> placementEntity(TenantId tenant, UUID employeeId, LocalDate on);
}
