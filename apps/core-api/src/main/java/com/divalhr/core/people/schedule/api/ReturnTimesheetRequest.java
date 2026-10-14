package com.divalhr.core.people.schedule.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-070B/C: the routed manager returns exactly the reviewed submission with a reason (10 to 500
 * characters after trimming; no control or format character, markup or link; Restricted
 * HR/Operations).
 */
@Schema(name = "ReturnTimesheetRequest")
public class ReturnTimesheetRequest extends ApproveTimesheetRequest {
  private Object reason;

  /**
   * The return reason, visible to the employee, tenant administrators and the routed manager.
   *
   * @return raw value
   */
  @Schema(type = "string", minLength = 10, maxLength = 500)
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
