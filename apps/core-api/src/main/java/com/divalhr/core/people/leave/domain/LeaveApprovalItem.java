package com.divalhr.core.people.leave.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A pending request as an approver sees it (MVP-041B): only what deciding needs. Restricted HR:
 * names, number, dates and amount never reach logs, errors or audit metadata.
 *
 * @param id request
 * @param submittedAt submission time
 * @param employeeId requesting employee
 * @param employeeNumber their employee number
 * @param givenNames their given names
 * @param familyName their family name
 * @param policyId policy
 * @param policyVersionId policy version
 * @param policyCode policy code
 * @param nameEn policy name in English
 * @param nameFr policy name in French
 * @param unit unit of the amount
 * @param amount requested amount
 * @param startDate first day (inclusive)
 * @param endDate last day (inclusive)
 * @param route approval route
 * @param state state
 */
public record LeaveApprovalItem(
    UUID id,
    Instant submittedAt,
    UUID employeeId,
    String employeeNumber,
    String givenNames,
    String familyName,
    UUID policyId,
    UUID policyVersionId,
    String policyCode,
    String nameEn,
    String nameFr,
    LeaveUnit unit,
    BigDecimal amount,
    LocalDate startDate,
    LocalDate endDate,
    ApprovalRoute route,
    LeaveRequestState state) {

  /**
   * Identifiers only.
   *
   * @return safe text
   */
  @Override
  public String toString() {
    return "LeaveApprovalItem[id=" + id + "]";
  }
}
