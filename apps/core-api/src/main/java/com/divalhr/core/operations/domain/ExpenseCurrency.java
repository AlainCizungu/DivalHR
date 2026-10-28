package com.divalhr.core.operations.domain;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The two currencies of an expense (MVP-081, D81-2). Amounts are decimal strings on the wire and
 * integer minor units in storage: Congolese francs have no minor unit, US dollars have two decimals.
 * Nothing converts one into the other and nothing ever adds them together.
 */
public enum ExpenseCurrency {
  /** Congolese franc: whole francs from 1 to 100,000,000. */
  CDF(0, 100_000_000L, Pattern.compile("[1-9][0-9]{0,8}")),
  /** US dollar: 0.01 to 100,000.00. */
  USD(2, 10_000_000L, Pattern.compile("(?:0|[1-9][0-9]{0,5})(?:\\.[0-9]{1,2})?"));

  private final int exponent;
  private final long maxMinor;
  private final Pattern shape;

  ExpenseCurrency(int exponent, long maxMinor, Pattern shape) {
    this.exponent = exponent;
    this.maxMinor = maxMinor;
    this.shape = shape;
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
   * The largest amount in minor units.
   *
   * @return maximum minor units
   */
  public long maxMinor() {
    return maxMinor;
  }

  /**
   * Parses a decimal string exactly, never through a floating point number.
   *
   * @param amount the submitted text
   * @return the amount in minor units, or empty when the shape, scale or range is wrong
   */
  public Optional<Long> parse(String amount) {
    if (amount == null || !shape.matcher(amount).matches()) {
      return Optional.empty();
    }
    long minor = new BigDecimal(amount).movePointRight(exponent).longValueExact();
    return minor >= 1 && minor <= maxMinor ? Optional.of(minor) : Optional.empty();
  }

  /**
   * The canonical decimal text of an amount (exactly the currency's scale).
   *
   * @param minor minor units
   * @return the decimal string
   */
  public String format(long minor) {
    return BigDecimal.valueOf(minor, exponent).toPlainString();
  }
}
