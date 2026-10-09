package com.divalhr.core.people.leave.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * The terminal decision on a leave request (MVP-041B). Restricted HR: the reason is personal data
 * and never appears in logs, errors, audit metadata or events; the deciding subject and manager are
 * never shown to the requesting employee.
 *
 * @param id decision
 * @param requestId the decided request
 * @param outcome {@code APPROVED} or {@code REJECTED}
 * @param route the route it was decided under
 * @param managerEmployeeId the deciding manager's employee ({@code MANAGER} route only)
 * @param reasonLocale {@code en} or {@code fr}, the language the reason was written in
 * @param reason the reason, as written
 * @param decidedAt decision time
 */
public record LeaveDecision(
    UUID id,
    UUID requestId,
    LeaveRequestState outcome,
    ApprovalRoute route,
    UUID managerEmployeeId,
    String reasonLocale,
    String reason,
    Instant decidedAt) {

  /**
   * Identifiers and outcome only.
   *
   * @return safe text
   */
  @Override
  public String toString() {
    return "LeaveDecision[id=" + id + ", outcome=" + outcome + "]";
  }
}
