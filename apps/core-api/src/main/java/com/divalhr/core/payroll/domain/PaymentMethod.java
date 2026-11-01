package com.divalhr.core.payroll.domain;

import java.util.Optional;

/**
 * How an employee's salary is prepared for payment (MVP-095A, D95A-2). A destination is only ever
 * RECORDED; no bank or provider verifies it and nothing here sends money.
 */
public enum PaymentMethod {
  /** An account at a controlled bank. */
  BANK_ACCOUNT,
  /** An E.164 number at a controlled mobile-money provider. */
  MOBILE_MONEY,
  /** Payroll handled outside DivalHR: no account or phone details. */
  MANUAL;

  /**
   * Parses a code exactly.
   *
   * @param text raw value
   * @return the method, or empty
   */
  public static Optional<PaymentMethod> parse(String text) {
    for (PaymentMethod method : values()) {
      if (method.name().equals(text)) {
        return Optional.of(method);
      }
    }
    return Optional.empty();
  }

  /**
   * @return whether the method carries an account, a country and a provider
   */
  public boolean hasAccount() {
    return this != MANUAL;
  }
}
