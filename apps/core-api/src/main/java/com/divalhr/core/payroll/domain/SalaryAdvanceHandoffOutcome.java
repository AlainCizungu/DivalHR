package com.divalhr.core.payroll.domain;

import java.util.Optional;

/**
 * Closed, allow-listed delivery outcome codes (ADR 0042 §6). Never a partner response, header or
 * signature.
 */
public enum SalaryAdvanceHandoffOutcome {
  RECEIVED_ACKNOWLEDGED,
  REJECTED_BY_PARTNER,
  TRANSIENT_NOT_SENT,
  UNKNOWN_TIMEOUT,
  UNKNOWN_BAD_RESPONSE,
  UNKNOWN_LEASE_EXPIRED,
  RETRIES_EXHAUSTED,
  WITHDRAWN_BEFORE_RECEIPT,
  RECONCILED_RECEIVED,
  RECONCILED_NOT_RECEIVED,
  RECONCILE_INCONCLUSIVE,
  ADMIN_RETRY;

  /**
   * Parses an outcome exactly.
   *
   * @param text raw value
   * @return the outcome, or empty
   */
  public static Optional<SalaryAdvanceHandoffOutcome> parse(String text) {
    for (SalaryAdvanceHandoffOutcome outcome : values()) {
      if (outcome.name().equals(text)) {
        return Optional.of(outcome);
      }
    }
    return Optional.empty();
  }
}
