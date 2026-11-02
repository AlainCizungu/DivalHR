package com.divalhr.core.payroll.domain;

import java.util.Optional;

/**
 * The allow-listed outcome codes a submission and its evidence may carry (MVP-095B, D95B-6). No
 * arbitrary provider text, body or header is ever stored: a provider answer is mapped to exactly one
 * of these codes or to {@link #UNKNOWN_BAD_RESPONSE}. The names equal the database check.
 */
public enum PaymentSubmissionOutcome {
  /** The provider accepted the instructions for processing. */
  ACCEPTED(Kind.ACCEPTED),
  /** The provider refused the request as malformed. */
  REJECTED_INVALID_REQUEST(Kind.REJECTED),
  /** The provider refused the signature. */
  REJECTED_SIGNATURE(Kind.REJECTED),
  /** The provider does not support a method or currency of the request. */
  REJECTED_UNSUPPORTED(Kind.REJECTED),
  /** The request ID was already used with different content. */
  REJECTED_DUPLICATE_MISMATCH(Kind.REJECTED),
  /** A provider limit was exceeded. */
  REJECTED_LIMIT(Kind.REJECTED),
  /** Any other definitive refusal. */
  REJECTED_OTHER(Kind.REJECTED),
  /** No connection could be made; nothing was written. */
  TRANSIENT_CONNECT(Kind.TRANSIENT),
  /** The TLS handshake failed; nothing was written. */
  TRANSIENT_TLS(Kind.TRANSIENT),
  /** The provider answered 429. */
  TRANSIENT_RATE_LIMITED(Kind.TRANSIENT),
  /** The provider answered 5xx. */
  TRANSIENT_SERVER(Kind.TRANSIENT),
  /** A timeout after the request was written. */
  UNKNOWN_TIMEOUT(Kind.UNKNOWN),
  /** The connection was lost after the request was written. */
  UNKNOWN_CONNECTION_LOST(Kind.UNKNOWN),
  /** A worker's lease expired while sending. */
  UNKNOWN_LEASE_EXPIRED(Kind.UNKNOWN),
  /** The answer was unparsable, oversized or not an authenticated acceptance. */
  UNKNOWN_BAD_RESPONSE(Kind.UNKNOWN),
  /** Bounded automatic retries ended. */
  RETRIES_EXHAUSTED(Kind.EXHAUSTED),
  /** A destination could not be read or mapped: nothing was sent, the batch needs a person. */
  NOT_SENT_UNREADABLE(Kind.EXHAUSTED);

  /** The class of an outcome. */
  public enum Kind {
    /** Accepted for processing. */
    ACCEPTED,
    /** Definitively rejected. */
    REJECTED,
    /** Retryable before acceptance. */
    TRANSIENT,
    /** No trustworthy final answer. */
    UNKNOWN,
    /** Retries ended. */
    EXHAUSTED
  }

  private final Kind kind;

  PaymentSubmissionOutcome(Kind kind) {
    this.kind = kind;
  }

  /**
   * @return the class of this outcome
   */
  public Kind kind() {
    return kind;
  }

  /**
   * Parses an outcome code exactly.
   *
   * @param text raw value
   * @return the outcome, or empty
   */
  public static Optional<PaymentSubmissionOutcome> parse(String text) {
    for (PaymentSubmissionOutcome outcome : values()) {
      if (outcome.name().equals(text)) {
        return Optional.of(outcome);
      }
    }
    return Optional.empty();
  }

  /**
   * The state a worker records for this outcome.
   *
   * @param attemptsExhausted whether this was the last allowed attempt
   * @return the next state
   */
  public PaymentSubmissionState nextState(boolean attemptsExhausted) {
    return switch (kind) {
      case ACCEPTED -> PaymentSubmissionState.ACCEPTED_FOR_PROCESSING;
      case REJECTED -> PaymentSubmissionState.REJECTED;
      case UNKNOWN -> PaymentSubmissionState.DELIVERY_UNKNOWN;
      case TRANSIENT ->
          attemptsExhausted
              ? PaymentSubmissionState.MANUAL_INTERVENTION
              : PaymentSubmissionState.QUEUED;
      case EXHAUSTED -> PaymentSubmissionState.MANUAL_INTERVENTION;
    };
  }
}
