package com.divalhr.core.identity.domain;

import java.net.IDN;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A normalized invitee email address (decision D-9): trimmed, Unicode NFC, lower-cased as a whole,
 * domain converted to ASCII (IDNA). Confidential personal data: {@link #toString()} never reveals
 * it, so it cannot leak through logging by accident.
 *
 * @param value normalized address
 */
public record EmailAddress(String value) {

  /** Maximum address length (RFC 5321 path limit). */
  public static final int MAX_LENGTH = 254;

  private static final int MAX_LOCAL = 64;
  private static final Pattern LOCAL =
      Pattern.compile("^[a-z0-9!#$%&'*+/=?^_`{|}~-]+(\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*$");
  private static final Pattern LABEL = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");

  /** Validation outcome for a submitted address. */
  public enum Defect {
    /** Missing or blank. */
    REQUIRED,
    /** Too long or too short. */
    LENGTH,
    /** Not a supported address. */
    FORMAT
  }

  /**
   * Checks a submitted address without normalizing it.
   *
   * @param raw submitted value
   * @return the defect, or empty when {@link #parse(String)} succeeds
   */
  public static Optional<Defect> defectOf(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.of(Defect.REQUIRED);
    }
    String trimmed = raw.strip();
    if (trimmed.length() < 3 || trimmed.length() > MAX_LENGTH) {
      return Optional.of(Defect.LENGTH);
    }
    return parse(raw).isPresent() ? Optional.empty() : Optional.of(Defect.FORMAT);
  }

  /**
   * Normalizes and validates a submitted address.
   *
   * @param raw submitted value
   * @return the normalized address, or empty when it is not supported
   */
  public static Optional<EmailAddress> parse(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String text = Normalizer.normalize(raw.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    if (text.length() < 3 || text.length() > MAX_LENGTH || text.chars().anyMatch(c -> c < 0x21)) {
      return Optional.empty();
    }
    int at = text.indexOf('@');
    if (at <= 0 || at != text.lastIndexOf('@') || at == text.length() - 1) {
      return Optional.empty();
    }
    String local = text.substring(0, at);
    String domain;
    try {
      domain =
          IDN.toASCII(text.substring(at + 1), IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
    } catch (IllegalArgumentException invalidDomain) {
      return Optional.empty();
    }
    if (local.length() > MAX_LOCAL || !LOCAL.matcher(local).matches()) {
      return Optional.empty();
    }
    String[] labels = domain.split("\\.", -1);
    if (labels.length < 2) {
      return Optional.empty();
    }
    for (String label : labels) {
      if (!LABEL.matcher(label).matches()) {
        return Optional.empty();
      }
    }
    String normalized = local + "@" + domain;
    return normalized.length() > MAX_LENGTH
        ? Optional.empty()
        : Optional.of(new EmailAddress(normalized));
  }

  @Override
  public String toString() {
    return "EmailAddress[<redacted>]";
  }
}
