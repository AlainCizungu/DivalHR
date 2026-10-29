package com.divalhr.core.payroll.domain;

/**
 * The two currencies of a monthly base compensation (MVP-093A, D93-4). Amounts are decimal strings
 * on the wire and integer minor units in storage: Congolese francs have no minor unit, US dollars
 * have two decimals. Nothing converts one into the other and nothing ever adds them together.
 */
public enum CompensationCurrency {
  /** Congolese franc: exponent 0, 1 to 1,000,000,000 francs. */
  CDF(0, 1_000_000_000L),
  /** US dollar: exponent 2, 1 to 10,000,000 cents (0.01 to 100,000.00). */
  USD(2, 10_000_000L);

  private final int exponent;
  private final long maxMinor;

  CompensationCurrency(int exponent, long maxMinor) {
    this.exponent = exponent;
    this.maxMinor = maxMinor;
  }

  /**
   * The ISO 4217 exponent.
   *
   * @return 0 or 2
   */
  public int exponent() {
    return exponent;
  }

  /**
   * The smallest legal amount in minor units.
   *
   * @return 1
   */
  public long minMinor() {
    return 1L;
  }

  /**
   * The largest legal amount in minor units.
   *
   * @return maximum minor units
   */
  public long maxMinor() {
    return maxMinor;
  }
}
