package com.divalhr.core.payroll.domain;

import java.util.Optional;

/**
 * The bounded partner-review status, separate from delivery and present only after receipt (ADR
 * 0042 §6). It reports a controlled synthetic partner's status; it is never a credit decision of
 * DivalHR.
 */
public enum SalaryAdvanceHandoffReviewState {
  /** Receipt acknowledged; nothing reviewed yet. */
  RECEIVED,
  /** The partner reports that it is reviewing. */
  UNDER_REVIEW,
  /** The partner reports that more information is needed. */
  MORE_INFORMATION_REQUIRED,
  /** The partner reports that it declined (terminal). */
  DECLINED,
  /** The partner reports that it cancelled its own review (terminal; not an employee withdrawal). */
  CANCELLED_BY_PARTNER;

  /**
   * Parses a state exactly.
   *
   * @param text raw value
   * @return the state, or empty
   */
  public static Optional<SalaryAdvanceHandoffReviewState> parse(String text) {
    for (SalaryAdvanceHandoffReviewState state : values()) {
      if (state.name().equals(text)) {
        return Optional.of(state);
      }
    }
    return Optional.empty();
  }

  /**
   * Whether a callback may move the review from this state to {@code to}.
   *
   * @param to the reported state
   * @return {@code true} for the closed review transitions only
   */
  public boolean mayMoveTo(SalaryAdvanceHandoffReviewState to) {
    return switch (this) {
      case RECEIVED ->
          to == UNDER_REVIEW
              || to == MORE_INFORMATION_REQUIRED
              || to == DECLINED
              || to == CANCELLED_BY_PARTNER;
      case UNDER_REVIEW ->
          to == MORE_INFORMATION_REQUIRED || to == DECLINED || to == CANCELLED_BY_PARTNER;
      case MORE_INFORMATION_REQUIRED ->
          to == UNDER_REVIEW || to == DECLINED || to == CANCELLED_BY_PARTNER;
      case DECLINED, CANCELLED_BY_PARTNER -> false;
    };
  }

  /**
   * Whether no further partner status can follow.
   *
   * @return {@code true} for the two terminal statuses
   */
  public boolean terminal() {
    return this == DECLINED || this == CANCELLED_BY_PARTNER;
  }
}
