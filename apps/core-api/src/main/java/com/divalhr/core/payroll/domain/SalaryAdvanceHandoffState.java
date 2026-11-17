package com.divalhr.core.payroll.domain;

import java.util.Optional;

/**
 * The closed delivery lifecycle of a controlled partner handoff (MVP-120B, ADR 0042 §6). {@code
 * RECEIVED} means only that the controlled synthetic receiver durably acknowledged receipt: never
 * reviewed, approved or funded. There is no approved, funded, disbursed, repaid or paid state, and
 * the database repeats these rules.
 */
public enum SalaryAdvanceHandoffState {
  /** Evidence-only first state; a handoff row is inserted already {@code QUEUED}. */
  AUTHORIZED,
  /** Due for delivery. */
  QUEUED,
  /** Claimed by one worker under a bounded lease. */
  SENDING,
  /** The receiver durably acknowledged receipt (terminal). */
  RECEIVED,
  /** A send may have escaped and its outcome is ambiguous: never resent automatically. */
  DELIVERY_UNKNOWN,
  /** Automatic delivery ended without receipt. */
  FAILED,
  /** The employee withdrew before receipt (terminal). */
  WITHDRAWN;

  /**
   * Parses a state exactly.
   *
   * @param text raw value
   * @return the state, or empty
   */
  public static Optional<SalaryAdvanceHandoffState> parse(String text) {
    for (SalaryAdvanceHandoffState state : values()) {
      if (state.name().equals(text)) {
        return Optional.of(state);
      }
    }
    return Optional.empty();
  }

  /**
   * Whether a transition to a different state is legal.
   *
   * @param to the next state
   * @return {@code true} when this state may move to {@code to}
   */
  public boolean mayMoveTo(SalaryAdvanceHandoffState to) {
    return switch (this) {
      case AUTHORIZED -> to == QUEUED;
      case QUEUED -> to == SENDING || to == WITHDRAWN;
      case SENDING -> to == RECEIVED || to == QUEUED || to == DELIVERY_UNKNOWN || to == FAILED;
      case DELIVERY_UNKNOWN -> to == RECEIVED || to == QUEUED || to == FAILED;
      case FAILED -> to == QUEUED;
      case RECEIVED, WITHDRAWN -> false;
    };
  }

  /**
   * @return whether the employee may still withdraw (before the partner durably received the
   *     request)
   */
  public boolean employeeMayWithdraw() {
    return this == QUEUED;
  }
}
