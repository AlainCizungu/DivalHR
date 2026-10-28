package com.divalhr.core.operations.application;

import com.divalhr.core.operations.domain.Expense;
import com.divalhr.core.platform.access.EmployeeAccessLinks;
import com.divalhr.core.platform.access.ReportingLines;
import com.divalhr.core.platform.access.ReportingLines.RouteFacts;
import com.divalhr.core.platform.tenancy.TenantId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The approval route of an expense (MVP-081, D81-7): the one effective manager of the owning
 * employee on the expense's business date, resolved from the current employment history through
 * people's reporting-lines port. It is never stored as authority: submission records the manager
 * routed then, and every queue, read, receipt download and decision resolves it again, so a manager
 * who loses the line loses access at once and the new effective manager receives the expense.
 *
 * <p>The route resolves when an employment of the employee is effective on the business date, those
 * employments have exactly one manager that day, the manager is not the employee, and the manager
 * holds an active {@code employee} membership and link in the tenant. Otherwise there is no route
 * and nobody is named. There is no substitute and no administrator override.
 */
@Component
public class ExpenseRouting {

  private final ReportingLines lines;
  private final EmployeeAccessLinks links;

  /**
   * Creates the routing.
   *
   * @param lines people's reporting-lines port
   * @param links the identity port (whether the manager is an active, linked employee)
   */
  public ExpenseRouting(ReportingLines lines, EmployeeAccessLinks links) {
    this.lines = lines;
    this.links = links;
  }

  /**
   * The manager the expense routes to now, if any.
   *
   * @param tenant verified tenant
   * @param expense the expense (its owner and current business date)
   * @return the manager's employee, or empty when there is no route
   */
  public Optional<UUID> manager(TenantId tenant, Expense expense) {
    RouteFacts facts = lines.route(tenant, expense.employeeId(), expense.businessDate());
    if (!facts.employed() || facts.managers().size() != 1) {
      return Optional.empty();
    }
    UUID manager = facts.managers().get(0);
    if (manager.equals(expense.employeeId()) || !links.activeEmployee(tenant, manager)) {
      return Optional.empty();
    }
    return Optional.of(manager);
  }

  /**
   * Whether the expense routes to this manager now.
   *
   * @param tenant verified tenant
   * @param expense the expense
   * @param managerEmployeeId the caller's own employee
   * @return true when it is routed to them
   */
  public boolean routedTo(TenantId tenant, Expense expense, UUID managerEmployeeId) {
    return manager(tenant, expense).filter(managerEmployeeId::equals).isPresent();
  }
}
