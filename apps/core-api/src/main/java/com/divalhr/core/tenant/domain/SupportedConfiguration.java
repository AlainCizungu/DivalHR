package com.divalhr.core.tenant.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The country, locale, time-zone and currency allow-list for organizations.
 *
 * <p>Deliberately defined in code, not runtime configuration, so that deployment settings can never
 * widen what the API accepts beyond {@code docs/API-SPEC.yaml}. A contract test fails if the two
 * diverge. Supporting a new country is a contract change first, then a change here.
 */
public final class SupportedConfiguration {

  /** Supported product locales (docs/I18N.md). */
  public static final List<String> LOCALES = List.of("fr", "en");

  /** Supported countries and their rules. */
  public static final Map<String, CountryRules> COUNTRIES =
      Map.of(
          "CD",
          new CountryRules(
              "CD", List.of("Africa/Kinshasa", "Africa/Lubumbashi"), List.of("CDF", "USD")));

  private SupportedConfiguration() {}

  /**
   * Looks up a supported country.
   *
   * @param countryCode ISO 3166-1 alpha-2 code
   * @return its rules when supported
   */
  public static Optional<CountryRules> country(String countryCode) {
    return Optional.ofNullable(COUNTRIES.get(countryCode));
  }

  /**
   * Rules for one country.
   *
   * @param countryCode ISO code
   * @param timezones allowed IANA time zones
   * @param currencies allowed ISO 4217 currencies
   */
  public record CountryRules(String countryCode, List<String> timezones, List<String> currencies) {

    /** Defensively copies the lists. */
    public CountryRules {
      timezones = List.copyOf(timezones);
      currencies = List.copyOf(currencies);
    }
  }
}
