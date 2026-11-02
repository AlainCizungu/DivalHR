package com.divalhr.core.payroll.domain;

import java.util.Optional;

/** The closed reasons for cancelling a payment batch (MVP-095A, D95A-6). No free text. */
public enum PaymentBatchCancelReason {
  /** A destination was wrong or changed. */
  DESTINATION_CORRECTION,
  /** The payroll package needs a correction. */
  PACKAGE_CORRECTION,
  /** The batch was prepared by mistake. */
  PREPARED_IN_ERROR,
  /** Another controlled reason. */
  OTHER_CONTROLLED;

  /**
   * Parses a reason code exactly.
   *
   * @param text raw value
   * @return the reason, or empty
   */
  public static Optional<PaymentBatchCancelReason> parse(String text) {
    for (PaymentBatchCancelReason reason : values()) {
      if (reason.name().equals(text)) {
        return Optional.of(reason);
      }
    }
    return Optional.empty();
  }
}
