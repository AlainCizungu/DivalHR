package com.divalhr.core.people.leave.api;

import com.divalhr.core.people.api.StrictRequest;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A tenant administrator's appointment of a leave approval substitute (MVP-041G). Every value is
 * kept raw so that each defect maps to a stable {@code {field, constraint}} pair; unknown
 * properties are captured and rejected. Tenant and actor are never fields.
 */
@Schema(name = "CreateLeaveApprovalSubstitution")
public class CreateLeaveApprovalSubstitutionRequest extends StrictRequest {
  private Object managerEmployeeId;
  private Object substituteEmployeeId;
  private Object effectiveFrom;
  private Object effectiveTo;
  private Object reasonLocale;
  private Object reason;

  /**
   * The delegating manager's employee ID (an employee of the organization).
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getManagerEmployeeId() {
    return managerEmployeeId;
  }

  /**
   * Sets: the manager's employee ID.
   *
   * @param managerEmployeeId raw value
   */
  public void setManagerEmployeeId(Object managerEmployeeId) {
    this.managerEmployeeId = managerEmployeeId;
  }

  /**
   * The substitute's employee ID (another employee of the organization).
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getSubstituteEmployeeId() {
    return substituteEmployeeId;
  }

  /**
   * Sets: the substitute's employee ID.
   *
   * @param substituteEmployeeId raw value
   */
  public void setSubstituteEmployeeId(Object substituteEmployeeId) {
    this.substituteEmployeeId = substituteEmployeeId;
  }

  /**
   * First day of the appointment ({@code YYYY-MM-DD}, inclusive).
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getEffectiveFrom() {
    return effectiveFrom;
  }

  /**
   * Sets: the first day.
   *
   * @param effectiveFrom raw value
   */
  public void setEffectiveFrom(Object effectiveFrom) {
    this.effectiveFrom = effectiveFrom;
  }

  /**
   * Last day of the appointment ({@code YYYY-MM-DD}, inclusive), not before the first.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getEffectiveTo() {
    return effectiveTo;
  }

  /**
   * Sets: the last day.
   *
   * @param effectiveTo raw value
   */
  public void setEffectiveTo(Object effectiveTo) {
    this.effectiveTo = effectiveTo;
  }

  /**
   * {@code en} or {@code fr}: the language the reason is written in.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getReasonLocale() {
    return reasonLocale;
  }

  /**
   * Sets: the reason's language.
   *
   * @param reasonLocale raw value
   */
  public void setReasonLocale(Object reasonLocale) {
    this.reasonLocale = reasonLocale;
  }

  /**
   * The reason (plain text, decision-reason grammar version 1), required.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getReason() {
    return reason;
  }

  /**
   * Sets: the reason.
   *
   * @param reason raw value
   */
  public void setReason(Object reason) {
    this.reason = reason;
  }
}
