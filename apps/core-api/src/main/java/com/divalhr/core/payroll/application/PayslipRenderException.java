package com.divalhr.core.payroll.application;

/**
 * A payslip PDF could not be rendered (it exceeded its size or time limit, or its content cannot
 * be laid out on one page). It carries no message detail: nothing of the payslip may reach a log
 * or a response through it.
 */
public final class PayslipRenderException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception without any detail. */
  public PayslipRenderException() {
    super("payslip render failed", null, false, false);
  }
}
