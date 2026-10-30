package com.divalhr.core.payroll.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * One effective-dated employer policy of a legal entity (MVP-094A, D94A-3): pay day and proration
 * method. An employer policy, not a legal-compliance claim.
 *
 * @param id policy
 * @param sequence 1, 2, 3 per legal entity (the entity's policy version is the last sequence)
 * @param effectiveFrom first day the policy is in force
 * @param payDay pay day
 * @param method proration method
 */
public record PayrollPolicy(
    UUID id, int sequence, LocalDate effectiveFrom, PayrollPayDay payDay, PayrollProration method) {

  /** Requires every component. */
  public PayrollPolicy {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(effectiveFrom, "effectiveFrom");
    Objects.requireNonNull(payDay, "payDay");
    Objects.requireNonNull(method, "method");
  }

  /**
   * Whether another policy has the same pay day and method (the effective date is not compared).
   *
   * @param other another policy
   * @return true when the rule is the same
   */
  public boolean sameRule(PayrollPolicy other) {
    return payDay.equals(other.payDay) && method == other.method;
  }
}
