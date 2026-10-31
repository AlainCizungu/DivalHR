package com.divalhr.core.payroll.domain;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The closed reasons for voiding a payroll package (MVP-094C, D94C-5). Only {@link
 * #OTHER_CONTROLLED} carries an explanation, which is restricted to the void evidence.
 */
public enum PayrollVoidReason {
  /** The payroll run or its sources were corrected. */
  SOURCE_CORRECTION,
  /** The statutory profiles were corrected. */
  PROFILE_CORRECTION,
  /** The rule pack or its activation was corrected. */
  RULE_PACK_CORRECTION,
  /** An administrative error. */
  ADMINISTRATIVE_ERROR,
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
   * @param text the raw value
   * @return the reason, or empty
   */
  public static Optional<PayrollVoidReason> parse(String text) {
    for (PayrollVoidReason reason : values()) {
      if (reason.name().equals(text)) {
        return Optional.of(reason);
      }
    }
    return Optional.empty();
  }

  /**
   * Whether the reason requires an explanation.
   *
   * @return true for {@link #OTHER_CONTROLLED}
   */
  public boolean requiresExplanation() {
    return this == OTHER_CONTROLLED;
  }

  /**
   * Whether an explanation is acceptable for this reason: present, trimmed, 10 to 500 characters
   * without a control character for {@link #OTHER_CONTROLLED}; absent for every other reason.
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
