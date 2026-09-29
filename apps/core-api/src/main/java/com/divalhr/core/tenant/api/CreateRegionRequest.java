package com.divalhr.core.tenant.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/** Request body for {@code createRegion}. Plain strings so every defect maps to a stable code. */
@Schema(name = "CreateRegion")
public final class CreateRegionRequest extends StrictRequest {

  @JsonProperty("legalEntityId")
  private String legalEntityId;

  @JsonProperty("code")
  private String code;

  @JsonProperty("name")
  private String name;

  @JsonProperty("effectiveFrom")
  private String effectiveFrom;

  @JsonProperty("effectiveTo")
  private String effectiveTo;

  /** Creates an empty request (used by Jackson). */
  public CreateRegionRequest() {}

  /**
   * Creates a request (used by tests).
   *
   * @param legalEntityId parent id
   * @param code code
   * @param name name
   * @param effectiveFrom ISO date
   * @param effectiveTo ISO date or {@code null}
   * @return request
   */
  public static CreateRegionRequest of(
      String legalEntityId, String code, String name, String effectiveFrom, String effectiveTo) {
    CreateRegionRequest request = new CreateRegionRequest();
    request.legalEntityId = legalEntityId;
    request.code = code;
    request.name = name;
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
