package com.divalhr.core.support;

import java.util.UUID;

/** Test fixtures for MVP-001. */
public final class Organizations {

  private Organizations() {}

  /**
   * A fresh, well-formed idempotency key.
   *
   * @return key
   */
  public static String newKey() {
    return "test-" + UUID.randomUUID();
  }

  /**
   * A valid request body with a unique name.
   *
   * @param name organization name
   * @return JSON body
   */
  public static String body(String name) {
    return body(name, "CD", "fr", "Africa/Kinshasa", "[\"USD\", \"CDF\"]");
  }

  /**
   * A request body.
   *
   * @param name name
   * @param country country code
   * @param locale default locale
   * @param timezone time zone
   * @param currenciesJson JSON array literal
   * @return JSON body
   */
  public static String body(
      String name, String country, String locale, String timezone, String currenciesJson) {
    return """
        {"name": %s, "countryCode": %s, "defaultLocale": %s, "timezone": %s, "currencies": %s}
        """
        .formatted(quote(name), quote(country), quote(locale), quote(timezone), currenciesJson);
  }

  /**
   * A unique organization name containing accents.
   *
   * @return name
   */
  public static String uniqueName() {
    return "Hôpital Général " + UUID.randomUUID().toString().substring(0, 8);
  }

  private static String quote(String value) {
    return value == null ? "null" : "\"" + value.replace("\"", "\\\"") + "\"";
  }
}
