package com.divalhr.core.people.leave.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A leave policy with its current version (MVP-040A). Confidential organization data; the names
 * never appear in logs, errors, metrics, audit metadata or events.
 *
 * @param id policy id
 * @param versionId version id
 * @param code normalized code (immutable)
 * @param versionNumber version number (1 in this story)
 * @param nameEn English name
 * @param nameFr French name
 * @param unit unit
 * @param balanceMode balance mode
 * @param annualEntitlement entitlement with two decimals when tracked, otherwise {@code null}
 * @param minimumServiceDays service days before eligibility (0 to 3650)
 * @param approvalRoute approval route
 * @param payrollEffect payroll effect (descriptive only)
 * @param effectiveFrom first day
 * @param effectiveTo last day, or {@code null}
 * @param createdAt creation time
 */
public record LeavePolicy(
    UUID id,
    UUID versionId,
    String code,
    int versionNumber,
    String nameEn,
    String nameFr,
    LeaveUnit unit,
    BalanceMode balanceMode,
    BigDecimal annualEntitlement,
    int minimumServiceDays,
    ApprovalRoute approvalRoute,
    PayrollEffect payrollEffect,
    LocalDate effectiveFrom,
    LocalDate effectiveTo,
    Instant createdAt) {

  /**
   * Identifiers only: the names never reach a log line through {@code toString}.
   *
   * @return safe text
   */
  @Override
  public String toString() {
    return "LeavePolicy[id="
        + id
        + ", versionId="
        + versionId
        + ", versionNumber="
        + versionNumber
        + "]";
  }
}
