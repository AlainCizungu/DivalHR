package com.divalhr.core.payroll.domain;

import java.util.Optional;

/**
 * The allow-listed outcome codes of one provider status inquiry (MVP-095C, ADR 0038 section 4). A
 * provider answer is mapped to exactly one of these codes; arbitrary provider text, bodies and
 * references are never stored or returned. The names equal the database check.
 */
public enum PaymentInquiryOutcome {
  /** An authenticated answer: the provider holds the request and accepted it for processing. */
  ACCEPTED_FOR_PROCESSING(Kind.ACCEPTED),
  /** An authenticated answer: rejected as malformed. */
  REJECTED_INVALID_REQUEST(Kind.REJECTED),
  /** An authenticated answer: rejected because of the signature. */
  REJECTED_SIGNATURE(Kind.REJECTED),
  /** An authenticated answer: a method or currency is unsupported. */
  REJECTED_UNSUPPORTED(Kind.REJECTED),
  /** An authenticated answer: the request ID was used with different content. */
  REJECTED_DUPLICATE_MISMATCH(Kind.REJECTED),
  /** An authenticated answer: a provider limit was exceeded. */
  REJECTED_LIMIT(Kind.REJECTED),
  /** An authenticated answer: any other definitive refusal. */
  REJECTED_OTHER(Kind.REJECTED),
  /** An authenticated answer: the provider has no record of the request ID. */
  NOT_FOUND(Kind.NON_FINAL),
  /** An authenticated answer: the provider holds the request and has not decided. */
  PENDING(Kind.NON_FINAL),
  /** An authenticated answer: the provider cannot say. */
  UNKNOWN(Kind.NON_FINAL),
  /** The answer was unsigned, wrongly signed, malformed, oversized or off-contract. */
  BAD_RESPONSE(Kind.BAD),
  /** No connection could be made. */
  TRANSIENT_CONNECT(Kind.TRANSIENT),
  /** The TLS handshake failed. */
  TRANSIENT_TLS(Kind.TRANSIENT),
  /** No answer arrived in time. */
  TRANSIENT_TIMEOUT(Kind.TRANSIENT),
  /** The provider answered 429. */
  TRANSIENT_RATE_LIMITED(Kind.TRANSIENT),
  /** The provider answered 5xx. */
  TRANSIENT_SERVER(Kind.TRANSIENT),
  /** Bounded automatic inquiries ended. */
  RETRIES_EXHAUSTED(Kind.EXHAUSTED);

  /** The class of an outcome. */
  public enum Kind {
    /** Authenticated acceptance for processing. */
    ACCEPTED,
    /** Authenticated rejection. */
    REJECTED,
    /** An authenticated answer that decides nothing. */
    NON_FINAL,
    /** An answer that cannot be trusted. */
    BAD,
    /** Retryable before a usable answer. */
    TRANSIENT,
    /** Retries ended. */
    EXHAUSTED
  }

  private final Kind kind;

  PaymentInquiryOutcome(Kind kind) {
    this.kind = kind;
  }

  /**
   * @return the class of this outcome
   */
  public Kind kind() {
    return kind;
  }

  /**
   * @return whether the outcome rests on a provider answer whose signature verified
   */
  public boolean authenticated() {
    return kind == Kind.ACCEPTED || kind == Kind.REJECTED || kind == Kind.NON_FINAL;
  }

  /**
   * Parses an outcome code exactly.
   *
   * @param text raw value
   * @return the outcome, or empty
   */
  public static Optional<PaymentInquiryOutcome> parse(String text) {
    for (PaymentInquiryOutcome outcome : values()) {
      if (outcome.name().equals(text)) {
        return Optional.of(outcome);
      }
    }
    return Optional.empty();
  }

  /**
   * The state a worker records for this outcome. Only an authenticated acceptance or rejection can
   * conclude; everything else is unresolved, retried or ends in manual review.
   *
   * @param attemptsExhausted whether this was the last allowed attempt
   * @return the next state
   */
  public PaymentReconciliationState nextState(boolean attemptsExhausted) {
    return switch (kind) {
      case ACCEPTED -> PaymentReconciliationState.CONFIRMED_ACCEPTED_FOR_PROCESSING;
      case REJECTED -> PaymentReconciliationState.CONFIRMED_REJECTED;
      case NON_FINAL, BAD -> PaymentReconciliationState.STILL_UNRESOLVED;
      case TRANSIENT ->
          attemptsExhausted
              ? PaymentReconciliationState.MANUAL_REVIEW_REQUIRED
              : PaymentReconciliationState.OPEN;
      case EXHAUSTED -> PaymentReconciliationState.MANUAL_REVIEW_REQUIRED;
    };
  }
}
