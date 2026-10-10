package com.divalhr.core.people.leave.api;

import com.divalhr.core.people.api.StrictRequest;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The employee's amendment of their own pending leave request by replacement (MVP-041D): the
 * submission fields of {@code CreateMyLeaveRequest} for the replacement and the amendment reason.
 * Every value is kept raw so that each defect maps to a stable {@code {field, constraint}} pair;
 * unknown properties are captured and rejected. The original request comes from the path; tenant,
 * employee and actor are never fields.
 */
@Schema(name = "AmendMyLeaveRequest")
public class AmendLeaveRequest extends StrictRequest {
  private Object policyId;
  private Object startDate;
  private Object endDate;
  private Object amount;
  private Object reasonLocale;
  private Object reason;

  /**
   * Policy ID (UUID).
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getPolicyId() {
    return policyId;
  }

  /**
   * Sets: policy ID (UUID).
   *
   * @param policyId raw value
   */
  public void setPolicyId(Object policyId) {
    this.policyId = policyId;
  }

  /**
   * First day (ISO date).
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getStartDate() {
    return startDate;
  }

  /**
   * Sets: first day (ISO date).
   *
   * @param startDate raw value
   */
  public void setStartDate(Object startDate) {
    this.startDate = startDate;
  }

  /**
   * Last day (ISO date).
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getEndDate() {
    return endDate;
  }

  /**
   * Sets: last day (ISO date).
   *
   * @param endDate raw value
   */
  public void setEndDate(Object endDate) {
    this.endDate = endDate;
  }

  /**
   * Requested amount in the policy's unit.
   *
   * @return raw value
   */
  @Schema(type = "number")
  public Object getAmount() {
    return amount;
  }

  /**
   * Sets: requested amount in the policy's unit.
   *
   * @param amount raw value
   */
  public void setAmount(Object amount) {
    this.amount = amount;
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
