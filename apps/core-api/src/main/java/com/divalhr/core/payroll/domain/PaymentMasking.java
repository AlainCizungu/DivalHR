package com.divalhr.core.payroll.domain;

/**
 * The only form in which an account number or phone number is ever shown after entry (MVP-095A,
 * D95A-3): asterisks and the last digits. ASCII only and never starting with a character a
 * spreadsheet reads as a formula.
 */
public final class PaymentMasking {

  private PaymentMasking() {}

  /**
   * Masks an account number: four asterisks and the last four characters (the last two when the
   * number has fewer than eight).
   *
   * @param account the normalized account number
   * @return the masked value
   */
  public static String account(String account) {
    int keep = account.length() >= 8 ? 4 : 2;
    return "****" + account.substring(account.length() - keep);
  }

  /**
   * Masks an E.164 number: six asterisks and the last three digits.
   *
   * @param phone the E.164 number
   * @return the masked value
   */
  public static String phone(String phone) {
    return "******" + phone.substring(phone.length() - 3);
  }
}
