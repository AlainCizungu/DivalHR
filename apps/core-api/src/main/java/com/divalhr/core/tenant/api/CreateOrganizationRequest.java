package com.divalhr.core.tenant.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Request body for {@code createOrganization}. Fields are plain strings so that unsupported values
 * map to the contract's stable error codes rather than a deserialization failure. Unknown
 * properties are captured (names only) and rejected: callers can never choose the id or tenant.
 */
@Schema(name = "CreateOrganization")
public final class CreateOrganizationRequest {

  @JsonProperty("name")
  private String name;

  @JsonProperty("countryCode")
  private String countryCode;

  @JsonProperty("defaultLocale")
  private String defaultLocale;

  @JsonProperty("timezone")
  private String timezone;

  @JsonProperty("currencies")
  private List<String> currencies;

  private final Set<String> unknownProperties = new TreeSet<>();

  /** Creates an empty request (used by Jackson). */
  public CreateOrganizationRequest() {}

  /**
   * Creates a request (used by tests).
   *
   * @param name name
   * @param countryCode country
   * @param defaultLocale locale
   * @param timezone time zone
   * @param currencies currencies
   * @return request
   */
  public static CreateOrganizationRequest of(
      String name,
      String countryCode,
      String defaultLocale,
      String timezone,
      List<String> currencies) {
    CreateOrganizationRequest request = new CreateOrganizationRequest();
    request.name = name;
    request.countryCode = countryCode;
    request.defaultLocale = defaultLocale;
    request.timezone = timezone;
    request.currencies = currencies == null ? null : new ArrayList<>(currencies);
    return request;
  }

  @JsonAnySetter
  void captureUnknown(String property, Object ignoredValue) {
    unknownProperties.add(property);
  }

  /**
   * Returns the name.
   *
   * @return name as submitted
   */
  public String getName() {
    return name;
  }

  /**
   * Returns the country code.
   *
   * @return country code
   */
  public String getCountryCode() {
    return countryCode;
  }

  /**
   * Returns the default locale.
   *
   * @return locale
   */
  public String getDefaultLocale() {
    return defaultLocale;
  }

  /**
   * Returns the time zone.
   *
   * @return time zone
   */
  public String getTimezone() {
    return timezone;
  }

  /**
   * Returns the currencies.
   *
   * @return copy of the currencies, or {@code null}
   */
  public List<String> getCurrencies() {
    return currencies == null ? null : new ArrayList<>(currencies);
  }

  /**
   * Names of properties not in the contract.
   *
   * @return unknown property names
   */
  @Schema(hidden = true)
  @com.fasterxml.jackson.annotation.JsonIgnore
  public Set<String> unknownProperties() {
    return Set.copyOf(unknownProperties);
  }
}
