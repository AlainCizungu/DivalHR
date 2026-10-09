package com.divalhr.core.people.leave.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A leave request with the policy it was made under (MVP-041A) and, once decided, its decision
 * (MVP-041B). Restricted HR: dates, amount and reason are personal data and never appear in logs,
 * errors or audit metadata.
 *
 * @param id request
 * @param employeeId the requesting employee
 * @param employmentId the employment covering the whole interval
 * @param policyId policy
 * @param policyVersionId the policy version covering the whole interval
 * @param policyCode policy code
 * @param nameEn policy name in English
 * @param nameFr policy name in French
 * @param unit the policy's unit (the amount is in it)
 * @param startDate first day (inclusive)
 * @param endDate last day (inclusive)
 * @param amount requested amount, two decimals
 * @param state state
 * @param submittedAt submission time
 * @param decision the decision, or {@code null} while pending
 */
public record LeaveRequest(
    UUID id,
    UUID employeeId,
    UUID employmentId,
    UUID policyId,
    UUID policyVersionId,
    String policyCode,
    String nameEn,
    String nameFr,
    LeaveUnit unit,
    LocalDate startDate,
    LocalDate endDate,
    BigDecimal amount,
    LeaveRequestState state,
    Instant submittedAt,
    LeaveDecision decision) {

  /**
   * Identifiers only: no date, amount or name reaches a log line through {@code toString}.
   *
   * @return safe text
   */
  @Override
  public String toString() {
    return "LeaveRequest[id=" + id + ", state=" + state + "]";
  }
}
