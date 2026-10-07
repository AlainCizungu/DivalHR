package com.divalhr.core.platform.access;

import com.divalhr.core.platform.tenancy.TenantId;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What the documents module may know about employments to build the contract expiration queue
 * (MVP-031A, Issue #73, amendment A31A-2), implemented by {@code people}. Documents never reads
 * people's tables. Read-only; every method runs inside the caller's transaction (the queue's one
 * {@code REPEATABLE READ} snapshot) and takes no lock.
 */
public interface EmploymentExpirationScope {

  /**
   * The employments still relevant on a business date: one fact per <em>employment</em> (a rehire
   * is a separate employment) whose last day is not before the day, optionally narrowed to the
   * employees matching a search and to a placement unit. The unit and the search apply to the
   * employment's placement on {@link #placementDay(LocalDate, LocalDate) its placement day}.
   *
   * @param tenant verified tenant
   * @param day business date
   * @param filter optional search and unit
   * @return identifiers and dates only (never names), in no particular order
   */
  List<RelevantEmployment> relevant(TenantId tenant, LocalDate day, Filter filter);

  /**
   * Employee number and names by employee ID (Confidential: never logged), for the rows of one
   * page.
   *
   * @param tenant verified tenant
   * @param employeeIds employees
   * @return the employees found, by ID
   */
  Map<UUID, EmployeeLabel> employees(TenantId tenant, Set<UUID> employeeIds);

  /**
   * The department or cost center of each employment's placement on its placement day, for the rows
   * of one page.
   *
   * @param tenant verified tenant
   * @param day business date
   * @param employmentIds employments
   * @return the unit per employment; an employment without a department or cost center that day is
   *     absent
   */
  Map<UUID, UUID> units(TenantId tenant, LocalDate day, Set<UUID> employmentIds);

  /**
   * The day whose placement is the employment's current unit: the business date, or the first day
   * of an employment that has not started yet.
   *
   * @param day business date
   * @param employmentStart the employment's first day
   * @return the placement day
   */
  static LocalDate placementDay(LocalDate day, LocalDate employmentStart) {
    return employmentStart.isAfter(day) ? employmentStart : day;
  }

  /**
   * Optional narrowing of the relevant employments.
   *
   * @param query normalized search text (employee-number prefix or name words, as the employee
   *     search), or {@code null}
   * @param unitId department or cost center of the placement, or {@code null}
   */
  record Filter(String query, UUID unitId) {

    /** No narrowing. */
    public static final Filter NONE = new Filter(null, null);

    @Override
    public String toString() {
      return "Filter[query=" + (query != null) + ", unit=" + (unitId != null) + "]";
    }
  }

  /**
   * One relevant employment.
   *
   * @param employmentId employment
   * @param employeeId its employee
   * @param lastDay its last day (set only by a separation that is not cancelled), or {@code null}
   */
  record RelevantEmployment(UUID employmentId, UUID employeeId, LocalDate lastDay) {}

  /**
   * An employee's label (Confidential: never logged).
   *
   * @param employeeNumber employee number
   * @param givenNames given names
   * @param familyName family name
   */
  record EmployeeLabel(String employeeNumber, String givenNames, String familyName) {

    @Override
    public String toString() {
      return "EmployeeLabel[redacted]";
    }
  }
}
