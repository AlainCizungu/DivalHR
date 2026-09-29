package com.divalhr.core.tenant.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request body for {@code createTeam}: exactly one of {@code departmentId} or {@code costCenterId}
 * (absent and {@code null} both mean "not supplied"). The site, tenant and every server-owned field
 * are unknown properties and rejected. Plain strings so every defect maps to a stable code.
 */
@Schema(name = "CreateTeam")
public final class CreateTeamRequest extends StrictRequest {

  @JsonProperty("departmentId")
  private String departmentId;

  @JsonProperty("costCenterId")
  private String costCenterId;

  @JsonProperty("code")
  private String code;

  @JsonProperty("name")
  private String name;

  @JsonProperty("effectiveFrom")
  private String effectiveFrom;

  @JsonProperty("effectiveTo")
  private String effectiveTo;

  /** Creates an empty request (used by Jackson). */
  public CreateTeamRequest() {}

  /**
   * Creates a request (used by tests).
   *
   * @param departmentId parent department or {@code null}
   * @param costCenterId parent cost center or {@code null}
   * @param code code
   * @param name name
   * @param effectiveFrom ISO date
   * @param effectiveTo ISO date or {@code null}
   * @return request
   */
  public static CreateTeamRequest of(
      String departmentId,
      String costCenterId,
      String code,
      String name,
      String effectiveFrom,
      String effectiveTo) {
    CreateTeamRequest request = new CreateTeamRequest();
    request.departmentId = departmentId;
    request.costCenterId = costCenterId;
    request.code = code;
    request.name = name;
    request.effectiveFrom = effectiveFrom;
    request.effectiveTo = effectiveTo;
    return request;
  }

  /**
   * Returns the parent department.
   *
   * @return id as submitted, or {@code null} when not supplied
   */
  public String getDepartmentId() {
    return departmentId;
  }

  /**
   * Returns the parent cost center.
   *
   * @return id as submitted, or {@code null} when not supplied
   */
  public String getCostCenterId() {
    return costCenterId;
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
