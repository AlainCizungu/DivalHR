package com.divalhr.core.platform.access;

import com.divalhr.core.platform.tenancy.TenantId;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * What the operations module may know about reporting lines to route an expense to the employee's
 * effective manager (MVP-081, ADR 0031), implemented by {@code people}. Operations never reads
 * people's tables. The route is never stored as authority: every list, read and decision asks
 * again, so a manager who loses the line loses access at once.
 */
public interface ReportingLines {

  /**
   * The route facts on a day under the current employment history, without locking: whether an
   * employment of the employee is effective that day, and the distinct managers of the employee's
   * effective employments that day (at most two are returned; more than one is an unusable route).
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param on the day (an expense's business date)
   * @return the facts
   */
  RouteFacts route(TenantId tenant, UUID employeeId, LocalDate on);

  /**
   * The employees a manager has a reporting line to at some time (the candidates of the manager's
   * queue; each candidate is still confirmed with {@link #route} on the expense's own date).
   *
   * @param tenant verified tenant
   * @param managerEmployeeId the manager
   * @param limit most employees returned
   * @return employee IDs, in a stable order
   */
  List<UUID> reportsOf(TenantId tenant, UUID managerEmployeeId, int limit);

  /**
   * Takes the tenant's manager-graph transaction lock until the caller's transaction ends (ADR 0008
   * step 1; MANDATORY): every manager-history write and every manager decision of the tenant
   * serialize on it, so a decision never races a change of the route.
   *
   * @param tenant verified tenant
   */
  void lockManagerGraph(TenantId tenant);

  /**
   * The identity an authorized reader needs to tell employees apart in a list: number and current
   * names. The caller has already established the right to see these employees (their own reports,
   * or the tenant administrator role).
   *
   * @param tenant verified tenant
   * @param employeeIds employees (at most 200)
   * @return the people found (unknown and foreign are absent)
   */
  List<Person> describe(TenantId tenant, Collection<UUID> employeeIds);

  /**
   * One employee as a reader tells them apart.
   *
   * @param id employee
   * @param employeeNumber employee number
   * @param givenNames current given names
   * @param familyName current family name
   */
  record Person(UUID id, String employeeNumber, String givenNames, String familyName) {

    @Override
    public String toString() {
      return "Person[" + id + "]";
    }
  }

  /**
   * The route facts of one employee on one day.
   *
   * @param employed whether an employment of the employee is effective that day
   * @param managers the distinct effective managers that day (at most two)
   */
  record RouteFacts(boolean employed, List<UUID> managers) {

    /** Copies the managers. */
    public RouteFacts {
      managers = List.copyOf(managers);
    }

    @Override
    public String toString() {
      return "RouteFacts[employed=" + employed + ", managers=" + managers.size() + "]";
    }
  }
}
