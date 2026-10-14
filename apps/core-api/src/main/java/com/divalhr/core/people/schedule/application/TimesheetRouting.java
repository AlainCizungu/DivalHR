package com.divalhr.core.people.schedule.application;

import com.divalhr.core.people.schedule.domain.TimesheetApproval;
import com.divalhr.core.people.schedule.internal.JdbcTimesheetActionRepository;
import com.divalhr.core.people.schedule.internal.JdbcTimesheetActionRepository.RouteFacts;
import com.divalhr.core.people.schedule.internal.JdbcTimesheetRepository.Header;
import com.divalhr.core.platform.access.EmployeeAccessLinks;
import com.divalhr.core.platform.tenancy.TenantId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * MVP-070B/C approval route (T70BC-3): the one manager who owns a weekly timesheet, resolved from
 * the current effective-dated employment history on the site-local Sunday of the week. It is never
 * stored as authority: submissions record the manager routed then, and the queue, every manager
 * read and every decision resolve it again, so a manager who loses the route loses access at once
 * and the new effective manager receives it.
 *
 * <p>The route resolves when the timesheet's rows have exactly one qualifying employment, that
 * employment is effective on the Sunday, the employee's employments effective on the Sunday have
 * exactly one manager, the manager is not the employee, and the manager holds an active {@code
 * employee} membership and link in the tenant. Otherwise there is no route, and nobody is named.
 */
@Component
public class TimesheetRouting {

  private final JdbcTimesheetActionRepository actions;
  private final EmployeeAccessLinks links;

  /**
   * Creates the routing.
   *
   * @param actions action and route storage
   * @param links the identity port (whether the manager is an active, linked employee)
   */
  public TimesheetRouting(JdbcTimesheetActionRepository actions, EmployeeAccessLinks links) {
    this.actions = actions;
    this.links = links;
  }

  /**
   * The qualifying employment of a revision's rows, if there is exactly one.
   *
   * @param tenant verified tenant
   * @param header the timesheet
   * @return the employment
   */
  public Optional<UUID> employment(TenantId tenant, Header header) {
    List<UUID> employments = actions.employments(tenant, header.id(), header.revision());
    return employments.size() == 1 ? Optional.of(employments.get(0)) : Optional.empty();
  }

  /**
   * The manager the timesheet routes to now, if any.
   *
   * @param tenant verified tenant
   * @param header the timesheet
   * @param employmentId its bound employment ({@link #employment}, or the submission's)
   * @return the manager's employee, or empty when there is no route
   */
  public Optional<UUID> manager(TenantId tenant, Header header, UUID employmentId) {
    if (employmentId == null) {
      return Optional.empty();
    }
    RouteFacts facts =
        actions.route(
            tenant,
            header.employeeId(),
            employmentId,
            TimesheetApproval.routeDate(header.period().weekStart()));
    if (!facts.employed() || facts.managers().size() != 1) {
      return Optional.empty();
    }
    UUID manager = facts.managers().get(0);
    if (manager.equals(header.employeeId()) || !links.activeEmployee(tenant, manager)) {
      return Optional.empty();
    }
    return Optional.of(manager);
  }
}
