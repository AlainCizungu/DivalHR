package com.divalhr.core.people.schedule.api;

import com.divalhr.core.people.api.StrictRequest;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-051: a manager's decision on a substitution request. Every value is kept raw so that each
 * defect maps to a stable {@code {field, constraint}} pair; unknown properties are captured and
 * rejected. The request comes from the path; tenant, manager and actor are never fields.
 */
@Schema(name = "DecideWorkScheduleSubstitution")
public class DecideWorkScheduleSubstitutionRequest extends StrictRequest {
  private Object decision;
  private Object substituteEmployeeId;
  private Object reasonLocale;
  private Object reason;

  /**
   * {@code APPROVED} or {@code REJECTED}.
   *
   * @return raw value
   */
  @Schema(
      type = "string",
      allowableValues = {"APPROVED", "REJECTED"})
  public Object getDecision() {
    return decision;
  }

  /**
   * Sets the raw decision.
   *
   * @param decision raw value
   */
  public void setDecision(Object decision) {
    this.decision = decision;
  }

  /**
   * The substitute (approval only).
   *
   * @return raw value
   */
  @Schema(type = "string", format = "uuid", nullable = true)
  public Object getSubstituteEmployeeId() {
    return substituteEmployeeId;
  }

  /**
   * Sets the raw substituteEmployeeId.
   *
   * @param substituteEmployeeId raw value
   */
  public void setSubstituteEmployeeId(Object substituteEmployeeId) {
    this.substituteEmployeeId = substituteEmployeeId;
  }

  /**
   * The reason's language.
   *
   * @return raw value
   */
  @Schema(
      type = "string",
      allowableValues = {"en", "fr"})
  public Object getReasonLocale() {
    return reasonLocale;
  }

  /**
   * Sets the raw reasonLocale.
   *
   * @param reasonLocale raw value
   */
  public void setReasonLocale(Object reasonLocale) {
    this.reasonLocale = reasonLocale;
  }

  /**
   * The reason (decision-reason grammar version 1).
   *
   * @return raw value
   */
  @Schema(type = "string", minLength = 2, maxLength = 500)
  public Object getReason() {
    return reason;
  }

  /**
   * Sets the raw reason.
   *
   * @param reason raw value
   */
  public void setReason(Object reason) {
    this.reason = reason;
  }
}
