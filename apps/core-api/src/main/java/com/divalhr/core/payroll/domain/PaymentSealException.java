package com.divalhr.core.payroll.domain;

/**
 * A sealed payment value could not be produced or authenticated. The message never carries a value.
 */
public final class PaymentSealException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception with a fixed, value-free message. */
  public PaymentSealException() {
    super("payment value could not be sealed or authenticated");
  }
}
