package com.divalhr.core.people.schedule.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-071: preview the unlock of a locked week with its closed reason and explanation. Every value
 * is raw: the command validates shape and grammar; the explanation is Restricted HR/Operations data
 * and is stored only with the UNLOCKED action.
 */
@Schema(name = "TimesheetPeriodUnlockPreviewRequest")
public class TimesheetPeriodUnlockPreviewRequest extends TimesheetPeriodScopeRequest {
  private Object reasonCode;

  /**
   * The closed reason code.
   *
   * @return raw value
   */
  @Schema(
      type = "string",
      allowableValues = {
        "ATTENDANCE_CORRECTION_REQUIRED",
        "TIMESHEET_EVIDENCE_ERROR",
        "ORGANIZATION_REQUEST",
        "OTHER"
      })
  public Object getReasonCode() {
    return reasonCode;
  }

  /**
   * Sets the raw reasonCode.
   *
   * @param reasonCode raw value
   */
  public void setReasonCode(Object reasonCode) {
    this.reasonCode = reasonCode;
  }

  private Object explanation;

  /**
   * The explanation: 10 to 500 characters after trimming, without markup, links, control or format
   * characters.
   *
   * @return raw value
   */
  @Schema(type = "string", minLength = 10, maxLength = 500)
  public Object getExplanation() {
    return explanation;
  }

  /**
   * Sets the raw explanation.
   *
   * @param explanation raw value
   */
  public void setExplanation(Object explanation) {
    this.explanation = explanation;
  }
}
