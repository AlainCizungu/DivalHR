package com.divalhr.core.tenant.application;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A validated, normalized request: trimmed name, sorted unique currencies. Its canonical form is
 * the idempotency fingerprint input, so semantically identical payloads match.
 *
 * @param name trimmed name
 * @param countryCode country
 * @param defaultLocale locale
 * @param timezone time zone
 * @param currencies sorted currencies
 */
public record CreateOrganizationCommand(
    String name,
    String countryCode,
    String defaultLocale,
    String timezone,
    List<String> currencies) {

  /** Normalizes currencies. */
  public CreateOrganizationCommand {
    currencies = currencies.stream().sorted().distinct().toList();
  }

  /**
   * Canonical key-sorted representation for fingerprinting.
   *
   * @return ordered map
   */
  public Map<String, Object> canonical() {
    Map<String, Object> map = new TreeMap<>();
    map.put("countryCode", countryCode);
    map.put("currencies", currencies);
    map.put("defaultLocale", defaultLocale);
    map.put("name", name);
    map.put("timezone", timezone);
    return map;
  }
}
