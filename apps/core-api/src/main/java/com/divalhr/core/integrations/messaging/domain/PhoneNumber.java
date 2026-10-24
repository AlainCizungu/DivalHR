package com.divalhr.core.integrations.messaging.domain;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * An E.164 number (D111-5): a plus sign, a non-zero first digit and 7 to 15 digits in total. The
 * full number exists only in memory while it is validated, sealed or sent; {@link #masked()} is the
 * only form that is ever shown, and nothing here prints the number ({@link #toString()} is masked).
 */
public final class PhoneNumber {

  private static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{6,14}$");

  private final String e164;

  private PhoneNumber(String e164) {
    this.e164 = e164;
  }

  /**
   * Parses a submitted number. Spaces, dashes and other decoration are rejected rather than
   * silently repaired, so a typo never becomes a different valid number.
   *
   * @param raw candidate
   * @return the number, or empty
   */
  public static Optional<PhoneNumber> parse(Object raw) {
    if (!(raw instanceof String text) || !E164.matcher(text).matches()) {
      return Optional.empty();
    }
    return Optional.of(new PhoneNumber(text));
  }

  /**
   * @return the number with its plus sign; only for sealing and for the provider request
   */
  public String e164() {
    return e164;
  }

  /**
   * @return the digits without the plus sign, as the Graph API {@code to} field expects them
   */
  public String digits() {
    return e164.substring(1);
  }

  /**
   * The only displayable form: country-code prefix of up to three digits is not derivable from the
   * number alone, so the mask keeps the plus sign, the first digit and the last two digits.
   *
   * @return for example {@code +2 *** ** 42}
   */
  public String masked() {
    return mask(e164);
  }

  /**
   * Masks a number.
   *
   * @param e164 a valid E.164 number
   * @return the masked form
   */
  public static String mask(String e164) {
    return "+" + e164.charAt(1) + " *** ** " + e164.substring(e164.length() - 2);
  }

  @Override
  public String toString() {
    return masked();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof PhoneNumber that && e164.equals(that.e164);
  }

  @Override
  public int hashCode() {
    return e164.hashCode();
  }
}
