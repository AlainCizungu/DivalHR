package com.divalhr.core.payroll.domain;

import java.util.Objects;

/**
 * A monthly base amount in one currency, held as integer minor units (MVP-093A, D93-4). Parsing is
 * exact and never goes through a floating point number or a rounding mode: the text must be an
 * unsigned decimal in the currency's own scale (CDF whole francs, USD one or two fraction digits),
 * with no sign, exponent, grouping separator, leading zero (except a single {@code 0} before the
 * fraction) or excess scale.
 *
 * <p>Restricted financial data: {@code toString}, {@code hashCode} and every exception message show
 * the currency only, never the amount.
 */
public final class CompensationMoney {

  /** The longest text considered at all; anything longer cannot be an amount. */
  static final int MAX_TEXT = 32;

  /** The most integer digits that can still be range-checked without overflow. */
  static final int MAX_INTEGER_DIGITS = 15;

  /** How a text was understood. */
  public enum Status {
    /** A legal amount. */
    OK,
    /** Not a decimal in the currency's grammar (sign, exponent, grouping, scale, shape). */
    MALFORMED,
    /** A well-formed decimal outside the currency's legal range. */
    OUT_OF_RANGE
  }

  /**
   * The outcome of parsing.
   *
   * @param status how the text was understood
   * @param money the amount, only when {@code status} is {@link Status#OK}
   */
  public record Parsed(Status status, CompensationMoney money) {

    /** Requires an amount exactly when the text was legal. */
    public Parsed {
      Objects.requireNonNull(status, "status");
      if ((status == Status.OK) != (money != null)) {
        throw new IllegalArgumentException("an amount accompanies exactly a legal text");
      }
    }

    @Override
    public String toString() {
      return "Parsed[" + status + "]";
    }
  }

  private final CompensationCurrency currency;
  private final long minor;

  private CompensationMoney(CompensationCurrency currency, long minor) {
    this.currency = currency;
    this.minor = minor;
  }

  /**
   * An amount from minor units, as stored.
   *
   * @param currency currency
   * @param minor minor units
   * @return the amount
   * @throws IllegalArgumentException if the amount is outside the currency's legal range (the
   *     message never shows the value)
   */
  public static CompensationMoney ofMinor(CompensationCurrency currency, long minor) {
    Objects.requireNonNull(currency, "currency");
    if (minor < currency.minMinor() || minor > currency.maxMinor()) {
      throw new IllegalArgumentException("amount outside the legal range of " + currency);
    }
    return new CompensationMoney(currency, minor);
  }

  /**
   * Parses a wire amount exactly.
   *
   * @param currency the currency whose scale applies
   * @param text the submitted text
   * @return how the text was understood, with the amount when it is legal
   */
  public static Parsed parse(CompensationCurrency currency, String text) {
    Objects.requireNonNull(currency, "currency");
    if (text == null || text.isEmpty() || text.length() > MAX_TEXT) {
      return new Parsed(Status.MALFORMED, null);
    }
    int dot = text.indexOf('.');
    String whole = dot < 0 ? text : text.substring(0, dot);
    String fraction = dot < 0 ? "" : text.substring(dot + 1);
    if (!isDigits(whole) || (whole.length() > 1 && whole.charAt(0) == '0')) {
      return new Parsed(Status.MALFORMED, null);
    }
    if (dot >= 0) {
      if (currency.exponent() == 0
          || fraction.isEmpty()
          || fraction.length() > currency.exponent()
          || !isDigits(fraction)) {
        return new Parsed(Status.MALFORMED, null);
      }
    }
    if (whole.length() > MAX_INTEGER_DIGITS) {
      return new Parsed(Status.OUT_OF_RANGE, null);
    }
    long value = Long.parseLong(whole);
    for (int i = 0; i < currency.exponent(); i++) {
      int digit = i < fraction.length() ? fraction.charAt(i) - '0' : 0;
      value = value * 10 + digit;
    }
    // The integer part has at most 15 digits, so value stays far below Long.MAX_VALUE.
    if (value < currency.minMinor() || value > currency.maxMinor()) {
      return new Parsed(Status.OUT_OF_RANGE, null);
    }
    return new Parsed(Status.OK, new CompensationMoney(currency, value));
  }

  private static boolean isDigits(String text) {
    if (text.isEmpty()) {
      return false;
    }
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c < '0' || c > '9') {
        return false;
      }
    }
    return true;
  }

  /**
   * The currency.
   *
   * @return currency
   */
  public CompensationCurrency currency() {
    return currency;
  }

  /**
   * The amount in minor units.
   *
   * @return minor units
   */
  public long minor() {
    return minor;
  }

  /**
   * The canonical decimal text: exactly the currency's scale ({@code 135000}, {@code 1250.00}).
   *
   * @return the wire amount
   */
  public String amount() {
    if (currency.exponent() == 0) {
      return Long.toString(minor);
    }
    long units = minor / 100;
    long cents = minor % 100;
    return units + "." + (cents < 10 ? "0" : "") + cents;
  }

  /**
   * Whether the two amounts are the same currency and value.
   *
   * @param other another amount
   * @return true when identical
   */
  public boolean sameAs(CompensationMoney other) {
    return other != null && currency == other.currency && minor == other.minor;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof CompensationMoney money && sameAs(money);
  }

  @Override
  public int hashCode() {
    // Deliberately independent of the amount: a hash must never be a function of a salary.
    return currency.name().hashCode();
  }

  @Override
  public String toString() {
    return "CompensationMoney[" + currency + "]";
  }
}
