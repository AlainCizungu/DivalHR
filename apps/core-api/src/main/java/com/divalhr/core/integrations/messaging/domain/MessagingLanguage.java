package com.divalhr.core.integrations.messaging.domain;

import java.util.Optional;

/** The two supported recipient languages (French and English are equally required). */
public enum MessagingLanguage {
  /** English. */
  EN("en"),
  /** French. */
  FR("fr");

  private final String code;

  MessagingLanguage(String code) {
    this.code = code;
  }

  /**
   * @return the lower-case code used by the API and by Meta templates
   */
  public String code() {
    return code;
  }

  /**
   * Parses exactly {@code en} or {@code fr}.
   *
   * @param value candidate
   * @return the language, or empty
   */
  public static Optional<MessagingLanguage> of(Object value) {
    if ("en".equals(value)) {
      return Optional.of(EN);
    }
    if ("fr".equals(value)) {
      return Optional.of(FR);
    }
    return Optional.empty();
  }
}
