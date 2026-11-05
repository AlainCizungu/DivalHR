package com.divalhr.core.payroll.domain;

import java.util.Optional;

/**
 * The closed states of a payment reconciliation (MVP-095C, ADR 0038 section 5). {@code
 * CONFIRMED_ACCEPTED_FOR_PROCESSING} means only that the provider's authenticated answer said it
 * holds the payment request and accepted it for processing: it never means paid, settled or
 * disbursed. The legal transitions are enforced here and again by the database.
 */
public enum PaymentReconciliationState {
  /** An investigation exists; no trustworthy conclusion. */
  OPEN,
  /** One worker holds a bounded lease and is asking the provider. */
  INQUIRY_IN_PROGRESS,
  /** An authenticated answer said the provider accepted the request for processing (not paid). */
  CONFIRMED_ACCEPTED_FOR_PROCESSING,
  /** An authenticated answer said the provider rejected the request, with an allow-listed code. */
  CONFIRMED_REJECTED,
  /** An authenticated answer could not say, or the answer could not be authenticated. */
  STILL_UNRESOLVED,
  /** Bounded automatic handling ended. */
  MANUAL_REVIEW_REQUIRED;

  /**
   * Parses a state exactly.
   *
   * @param text raw value
   * @return the state, or empty
   */
  public static Optional<PaymentReconciliationState> parse(String text) {
    for (PaymentReconciliationState state : values()) {
      if (state.name().equals(text)) {
        return Optional.of(state);
      }
    }
    return Optional.empty();
  }

  /**
   * Whether a transition is legal.
   *
   * @param to the next state
   * @return {@code true} when this state may move to {@code to}
   */
  public boolean mayMoveTo(PaymentReconciliationState to) {
    return switch (this) {
      case OPEN -> to == INQUIRY_IN_PROGRESS || to == MANUAL_REVIEW_REQUIRED;
      case INQUIRY_IN_PROGRESS ->
          to == OPEN
              || to == CONFIRMED_ACCEPTED_FOR_PROCESSING
              || to == CONFIRMED_REJECTED
              || to == STILL_UNRESOLVED
              || to == MANUAL_REVIEW_REQUIRED;
      case CONFIRMED_ACCEPTED_FOR_PROCESSING,
          CONFIRMED_REJECTED,
          STILL_UNRESOLVED,
          MANUAL_REVIEW_REQUIRED ->
          false;
    };
  }

  /**
   * @return whether no automatic or administrator action leaves this state in this story
   */
  public boolean concluded() {
    return this != OPEN && this != INQUIRY_IN_PROGRESS;
  }
}
