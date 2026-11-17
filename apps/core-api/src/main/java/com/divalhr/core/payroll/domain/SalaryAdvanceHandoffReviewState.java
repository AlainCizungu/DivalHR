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
  DECLINED;

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
      case RECEIVED -> to == UNDER_REVIEW;
      case UNDER_REVIEW -> to == MORE_INFORMATION_REQUIRED || to == DECLINED;
      case MORE_INFORMATION_REQUIRED -> to == UNDER_REVIEW || to == DECLINED;
      case DECLINED -> false;
    };
  }
}
