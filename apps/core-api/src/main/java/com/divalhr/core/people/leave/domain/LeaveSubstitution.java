package com.divalhr.core.people.leave.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A leave approval substitute appointment (MVP-041G, V24): append-only. Restricted HR: the reason
 * is personal data and never appears in logs, errors, audit metadata or events; it is returned to
 * tenant administrators only.
 *
 * @param id appointment
 * @param managerEmployeeId the delegating manager
 * @param substituteEmployeeId the substitute
 * @param effectiveFrom first day (inclusive)
 * @param effectiveTo last day (inclusive)
 * @param reasonLocale {@code en} or {@code fr}
 * @param reason the reason, as written
 * @param createdAt creation time
 */
public record LeaveSubstitution(
    UUID id,
    UUID managerEmployeeId,
    UUID substituteEmployeeId,
    LocalDate effectiveFrom,
    LocalDate effectiveTo,
    String reasonLocale,
    String reason,
    Instant createdAt) {

  /**
   * Identifiers only.
   *
   * @return safe text
   */
  @Override
  public String toString() {
    return "LeaveSubstitution[id=" + id + "]";
  }
}
