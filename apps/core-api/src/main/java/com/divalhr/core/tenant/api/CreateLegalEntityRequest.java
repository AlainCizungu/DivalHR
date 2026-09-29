package com.divalhr.core.tenant.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request body for {@code createLegalEntity}. Plain strings so every defect maps to a stable code.
 */
@Schema(name = "CreateLegalEntity")
public final class CreateLegalEntityRequest extends StrictRequest {

  @JsonProperty("code")
  private String code;

  @JsonProperty("name")
  private String name;

  @JsonProperty("countryCode")
  private String countryCode;

  @JsonProperty("effectiveFrom")
  private String effectiveFrom;

  @JsonProperty("effectiveTo")
  private String effectiveTo;

  /** Creates an empty request (used by Jackson). */
  public CreateLegalEntityRequest() {}

  /**
   * Creates a request (used by tests).
   *
   * @param code code
   * @param name name
   * @param countryCode country
   * @param effectiveFrom ISO date
   * @param effectiveTo ISO date or {@code null}
   * @return request
   */
  public static CreateLegalEntityRequest of(
      String code, String name, String countryCode, String effectiveFrom, String effectiveTo) {
    CreateLegalEntityRequest request = new CreateLegalEntityRequest();
    request.code = code;
    request.name = name;
    request.countryCode = countryCode;
    request.effectiveFrom = effectiveFrom;
    request.effectiveTo = effectiveTo;
    return request;
  }

  /**
   * Returns the code.
   *
   * @return code as submitted
   */
  public String getCode() {
    return code;
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
   * Returns the start date.
   *
   * @return ISO date text
   */
  public String getEffectiveFrom() {
    return effectiveFrom;
  }

  /**
   * Returns the end date.
   *
   * @return ISO date text or {@code null}
   */
  public String getEffectiveTo() {
    return effectiveTo;
  }
}
