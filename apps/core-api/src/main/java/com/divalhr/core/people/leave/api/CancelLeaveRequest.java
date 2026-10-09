package com.divalhr.core.people.leave.api;

import com.divalhr.core.people.api.StrictRequest;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The employee's cancellation of their own pending leave request (MVP-041C). Every value is kept
 * raw so that each defect maps to a stable {@code {field, constraint}} pair; unknown properties are
 * captured and rejected. The request comes from the path; tenant, employee and actor are never
 * fields.
 */
@Schema(name = "CancelMyLeaveRequest")
public class CancelLeaveRequest extends StrictRequest {
  private Object reasonLocale;
  private Object reason;

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
