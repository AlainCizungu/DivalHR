package com.divalhr.core.payroll.domain;

/**
 * The canonical decimal text of a payroll amount (MVP-094A): exactly the currency's scale (CDF
 * whole francs, USD two decimals). Unlike a compensation amount, a contractual gross or a run total
 * may be zero (a very short period of a very small salary rounds to nothing). Restricted financial
 * data: nothing here is logged.
 */
public final class PayrollMoney {

  private PayrollMoney() {}

  /**
   * The canonical decimal text.
   *
   * @param currency currency
   * @param minor non-negative amount in minor units
   * @return {@code 232759} or {@code 625.00}
   */
  public static String text(CompensationCurrency currency, long minor) {
    if (minor < 0) {
      throw new IllegalArgumentException("a payroll amount is never negative");
    }
    if (currency.exponent() == 0) {
      return Long.toString(minor);
    }
    long units = minor / 100;
    long cents = minor % 100;
    return units + "." + (cents < 10 ? "0" : "") + cents;
  }
}
