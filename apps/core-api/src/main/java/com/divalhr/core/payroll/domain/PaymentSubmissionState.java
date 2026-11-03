package com.divalhr.core.payroll.domain;

import java.util.Optional;

/**
 * The closed states of a provider submission (MVP-095B, D95B-6). {@code ACCEPTED_FOR_PROCESSING}
 * means only that the provider's authenticated answer accepted the instructions for processing: it
 * never means paid, settled or disbursed. The legal transitions are enforced here and again by the
 * database.
 */
public enum PaymentSubmissionState {
  /** A durable submission exists; no provider outcome yet. */
  QUEUED,
  /** Claimed by one worker under a bounded lease. */
  SENDING,
  /** The provider accepted the instructions for processing (not paid). */
  ACCEPTED_FOR_PROCESSING,
  /** The provider definitively rejected the whole submission. */
  REJECTED,
  /** A request may have escaped and no trustworthy final answer exists. */
  DELIVERY_UNKNOWN,
  /** Bounded automatic handling ended. */
  MANUAL_INTERVENTION;

  /**
   * Parses a state exactly.
   *
   * @param text raw value
   * @return the state, or empty
   */
  public static Optional<PaymentSubmissionState> parse(String text) {
    for (PaymentSubmissionState state : values()) {
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
  public boolean mayMoveTo(PaymentSubmissionState to) {
    return switch (this) {
      case QUEUED -> to == SENDING || to == MANUAL_INTERVENTION;
      case SENDING ->
          to == QUEUED
              || to == ACCEPTED_FOR_PROCESSING
              || to == REJECTED
              || to == DELIVERY_UNKNOWN
              || to == MANUAL_INTERVENTION;
      case ACCEPTED_FOR_PROCESSING, REJECTED, DELIVERY_UNKNOWN, MANUAL_INTERVENTION -> false;
    };
  }

  /**
   * @return whether no automatic or administrator action leaves this state in this story
   */
  public boolean terminal() {
    return this != QUEUED && this != SENDING;
  }
}
