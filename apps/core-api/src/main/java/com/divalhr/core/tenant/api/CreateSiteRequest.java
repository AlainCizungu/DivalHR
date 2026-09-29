package com.divalhr.core.tenant.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/** Request body for {@code createSite}. */
@Schema(name = "CreateSite")
public final class CreateSiteRequest extends StrictRequest {

  @JsonProperty("legalEntityId")
  private String legalEntityId;

  @JsonProperty("code")
  private String code;

  @JsonProperty("name")
  private String name;

  @JsonProperty("timezone")
  private String timezone;

  @JsonProperty("effectiveFrom")
  private String effectiveFrom;

  @JsonProperty("effectiveTo")
  private String effectiveTo;

  /** Creates an empty request (used by Jackson). */
  public CreateSiteRequest() {}

  /**
   * Creates a request (used by tests).
   *
   * @param legalEntityId parent id
   * @param code code
   * @param name name
   * @param timezone time zone
   * @param effectiveFrom ISO date
   * @param effectiveTo ISO date or {@code null}
   * @return request
   */
  public static CreateSiteRequest of(
      String legalEntityId,
      String code,
      String name,
      String timezone,
      String effectiveFrom,
      String effectiveTo) {
    CreateSiteRequest request = new CreateSiteRequest();
    request.legalEntityId = legalEntityId;
    request.code = code;
    request.name = name;
    request.timezone = timezone;
    request.effectiveFrom = effectiveFrom;
    request.effectiveTo = effectiveTo;
    return request;
  }

  /**
   * Returns the parent id.
   *
   * @return parent legal entity id as submitted
   */
  public String getLegalEntityId() {
    return legalEntityId;
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
   * Returns the time zone.
   *
   * @return time zone
   */
  public String getTimezone() {
    return timezone;
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
