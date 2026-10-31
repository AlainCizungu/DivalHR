package com.divalhr.core.payroll.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * The language a payslip is rendered in (MVP-094C, ADR 0035 section 12). The locale selects the
 * wording only; it never changes an amount, a code, a version or a digest.
 */
public enum PayslipLocale {
  /** English. */
  EN("en"),
  /** French. */
  FR("fr");

  private final String code;

  PayslipLocale(String code) {
    this.code = code;
  }

  /**
   * The wire code.
   *
   * @return {@code en} or {@code fr}
   */
  public String code() {
    return code;
  }

  /**
   * Parses a requested locale exactly.
   *
   * @param text the raw value
   * @return the locale, or empty for anything but {@code en} or {@code fr}
   */
  public static Optional<PayslipLocale> parse(String text) {
    if ("en".equals(text)) {
      return Optional.of(EN);
    }
    if ("fr".equals(text)) {
      return Optional.of(FR);
    }
    return Optional.empty();
  }

  /**
   * The default locale of a request without a {@code locale} parameter: the first of {@code en} or
   * {@code fr} listed in {@code Accept-Language} (a range with quality 0 is skipped), otherwise
   * English.
   *
   * @param header the raw header value, or {@code null}
   * @return the locale
   */
  public static PayslipLocale fromAcceptLanguage(String header) {
    if (header == null || header.length() > 512) {
      return EN;
    }
    for (String range : header.split(",")) {
      String[] parts = range.trim().split(";");
      String tag = parts[0].trim().toLowerCase(Locale.ROOT);
      int dash = tag.indexOf('-');
      String primary = dash < 0 ? tag : tag.substring(0, dash);
      boolean refused = false;
      for (int i = 1; i < parts.length; i++) {
        String parameter = parts[i].trim().replace(" ", "");
        if (parameter.equals("q=0") || parameter.equals("q=0.0") || parameter.equals("q=0.00")) {
          refused = true;
        }
      }
      if (refused) {
        continue;
      }
      if ("en".equals(primary)) {
        return EN;
      }
      if ("fr".equals(primary)) {
        return FR;
      }
    }
    return EN;
  }
}
