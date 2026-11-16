package com.divalhr.core.platform.access;

import com.divalhr.core.platform.tenancy.TenantId;
import java.time.LocalDate;
import java.util.List;
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
   * The legal entities of the employee's active placements covering a date, at most two (two means
   * "more than one"; the caller fails closed and never picks one, MVP-120A, R205-1).
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param on business date
   * @return one entry per covering placement row, empty when the employee is not placed
   */
  List<UUID> placementEntities(TenantId tenant, UUID employeeId, LocalDate on);
}
