package com.divalhr.core.payroll.domain;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The closed reasons for recording or replacing a payment destination (MVP-095A, D95A-2). Only
 * {@link #OTHER_CONTROLLED} carries an explanation, which is restricted to the encrypted envelope.
 */
public enum PaymentDestinationReason {
  /** The first destination of the employee. */
  INITIAL_SETUP,
  /** The employee asked for the change. */
  EMPLOYEE_REQUEST,
  /** The bank or provider changed. */
  PROVIDER_CHANGE,
  /** A recorded value was wrong. */
  ACCOUNT_CORRECTION,
  /** Another reason, explained in 10 to 500 characters. */
  OTHER_CONTROLLED;

  /** Shortest explanation. */
  public static final int MIN_EXPLANATION = 10;

  /** Longest explanation. */
  public static final int MAX_EXPLANATION = 500;

  private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}]");

  /**
   * Parses a reason code exactly.
   *
   * @param text raw value
   * @return the reason, or empty
   */
  public static Optional<PaymentDestinationReason> parse(String text) {
    for (PaymentDestinationReason reason : values()) {
      if (reason.name().equals(text)) {
        return Optional.of(reason);
      }
    }
    return Optional.empty();
  }

  /**
   * @return whether an explanation is required
   */
  public boolean requiresExplanation() {
    return this == OTHER_CONTROLLED;
  }

  /**
   * Whether an explanation is acceptable: present, trimmed, 10 to 500 characters without a control
   * character for {@link #OTHER_CONTROLLED}; absent for every other reason.
   *
   * @param explanation the explanation, or {@code null}
   * @return true when the combination is valid
   */
  public boolean accepts(String explanation) {
    if (!requiresExplanation()) {
      return explanation == null;
    }
    if (explanation == null || !explanation.equals(explanation.strip())) {
      return false;
    }
    int length = explanation.codePointCount(0, explanation.length());
    return length >= MIN_EXPLANATION
        && length <= MAX_EXPLANATION
        && !CONTROL.matcher(explanation).find();
  }
}
