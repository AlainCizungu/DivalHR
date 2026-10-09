package com.divalhr.core.people.leave.domain;

import java.time.LocalDate;
import java.util.UUID;

/**
 * What deciding a request depends on (MVP-041B): its owner, employment, policy version and that
 * version's immutable route, its period and its state.
 *
 * @param id request
 * @param employeeId requesting employee
 * @param employmentId the request's employment
 * @param policyId policy
 * @param policyVersionId policy version (immutable route)
 * @param route the version's approval route
 * @param startDate first day (the manager relationship is evaluated on it)
 * @param endDate last day
 * @param state current state
 */
public record LeaveRouting(
    UUID id,
    UUID employeeId,
    UUID employmentId,
    UUID policyId,
    UUID policyVersionId,
    ApprovalRoute route,
    LocalDate startDate,
    LocalDate endDate,
    LeaveRequestState state) {

  /**
   * Identifiers only.
   *
   * @return safe text
   */
  @Override
  public String toString() {
    return "LeaveRouting[id=" + id + ", state=" + state + "]";
  }
}
