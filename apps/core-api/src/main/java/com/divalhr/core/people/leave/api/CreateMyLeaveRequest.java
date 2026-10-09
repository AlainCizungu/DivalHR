package com.divalhr.core.people.leave.api;

import com.divalhr.core.people.api.StrictRequest;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * An employee's leave request (MVP-041A). Mirrors {@code CreateMyLeaveRequest}. Every value is kept
 * raw so that each defect maps to a stable {@code {field, constraint}} pair; unknown properties are
 * captured and rejected. The employee is never a field: it comes from the caller's own link.
 */
@Schema(name = "CreateMyLeaveRequest")
public class CreateMyLeaveRequest extends StrictRequest {
  private Object policyId;
  private Object startDate;
  private Object endDate;
  private Object amount;

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
}
