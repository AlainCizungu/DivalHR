package com.divalhr.core.identity.domain;

import java.util.Locale;
import java.util.Optional;

/** Language of the invitation email and acceptance page. */
public enum InvitationLocale {
  /** French. */
  FR("fr"),
  /** English. */
  EN("en");

  private final String tag;

  InvitationLocale(String tag) {
    this.tag = tag;
  }

  /**
   * The contract value.
   *
   * @return {@code fr} or {@code en}
   */
  public String tag() {
    return tag;
  }

  /**
   * The Java locale.
   *
   * @return locale
   */
  public Locale locale() {
    return Locale.forLanguageTag(tag);
  }

  /**
   * Parses a contract value.
   *
   * @param raw submitted value
   * @return the locale, or empty
   */
  public static Optional<InvitationLocale> fromTag(String raw) {
    for (InvitationLocale locale : values()) {
      if (locale.tag.equals(raw)) {
        return Optional.of(locale);
      }
    }
    return Optional.empty();
  }
}
