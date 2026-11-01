package com.divalhr.core.payroll.application;

/**
 * The bytes of a rendered payslip (Restricted financial and HR data). The array is copied on the
 * way in and on the way out, and nothing of the content is printed by {@code toString}.
 */
public final class PayslipPdf {

  private final byte[] bytes;

  PayslipPdf(byte[] bytes) {
    this.bytes = bytes.clone();
  }

  /**
   * A copy of the document.
   *
   * @return the PDF bytes
   */
  public byte[] bytes() {
    return bytes.clone();
  }

  /**
   * The size of the document.
   *
   * @return bytes
   */
  public int size() {
    return bytes.length;
  }

  @Override
  public String toString() {
    return "PayslipPdf[" + bytes.length + " bytes]";
  }
}
