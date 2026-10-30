package com.divalhr.core.platform.access;

import com.divalhr.core.platform.tenancy.TenantId;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What the payroll module may know about the people of a legal entity to calculate a monthly run
 * (MVP-094A, ADR 0009, ADR 0033), implemented by {@code people}. Payroll never reads people's
 * tables, and the port returns no name or other attribute of an employee: only the employee number
 * (the run's stable line key), the employments and the placements of the month.
 */
public interface PayrollPopulation {

  /**
   * Locks {@code FOR SHARE}, until the caller's transaction ends (MANDATORY; lock order step 2,
   * rows in identifier order), every employment of the tenant that overlaps the month: a change,
   * correction or separation of any of them waits, and no employment version can move under the
   * run. Call before {@link #read}; the result of {@code read} is then stable.
   *
   * @param tenant verified tenant
   * @param from first day of the month
   * @param to last day of the month
   */
  void lockShared(TenantId tenant, LocalDate from, LocalDate to);

  /**
   * The population of a legal entity for a month: employees that have an active {@code PLACEMENT}
   * assignment in the legal entity that overlaps one of their employments in the month. For each,
   * every employment overlapping the month and every active placement overlapping the month in any
   * legal entity.
   *
   * @param tenant verified tenant
   * @param legalEntityId the legal entity (already resolved in the tenant)
   * @param from first day of the month
   * @param to last day of the month
   * @param limit the most members to return (callers pass one more than they accept to detect an
   *     excess)
   * @return the members ordered by employee number in byte order, then identifier
   */
  List<Member> read(TenantId tenant, UUID legalEntityId, LocalDate from, LocalDate to, int limit);

  /**
   * One employee of the population.
   *
   * @param employeeId employee
   * @param employeeNumber employee number (Confidential)
   * @param employments employments overlapping the month
   * @param placements active placements overlapping the month, in any legal entity
   */
  record Member(
      UUID employeeId,
      String employeeNumber,
      List<Employment> employments,
      List<Placement> placements) {

    /** Copies the lists. */
    public Member {
      employments = List.copyOf(employments);
      placements = List.copyOf(placements);
    }

    @Override
    public String toString() {
      return "PayrollPopulation.Member[" + employeeId + "]";
    }
  }

  /**
   * One employment of an employee.
   *
   * @param id employment
   * @param version its timeline version
   * @param start its first day
   * @param end its last day, or {@code null} while open
   */
  record Employment(UUID id, long version, LocalDate start, LocalDate end) {}

  /**
   * One active placement of an employee in a legal entity.
   *
   * @param id the placement assignment
   * @param legalEntityId the legal entity placed in
   * @param start its first day
   * @param end its last day, or {@code null} while open
   */
  record Placement(UUID id, UUID legalEntityId, LocalDate start, LocalDate end) {}
}
