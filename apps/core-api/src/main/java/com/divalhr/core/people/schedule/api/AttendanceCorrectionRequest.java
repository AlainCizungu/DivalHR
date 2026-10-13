package com.divalhr.core.people.schedule.api;

import com.divalhr.core.people.api.StrictRequest;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-062: the proposed correction of an existing attendance record (REPLACE_TIMES): its complete
 * new effective values, the closed reason and the explanation. Values are kept raw so that each
 * defect maps to a stable {@code {field, constraint}} pair; unknown properties are captured and
 * rejected. Tenant, actor, employee, employment, schedule and slot are never fields.
 */
@Schema(name = "AttendanceCorrectionRequest")
public class AttendanceCorrectionRequest extends StrictRequest {
  private Object checkedInAt;
  private Object checkedOutAt;
  private Object reasonCode;
  private Object explanation;

  /**
   * The corrected check-in: RFC 3339 with an explicit offset, at most six fractional digits.
   *
   * @return raw value
   */
  @Schema(type = "string", format = "date-time")
  public Object getCheckedInAt() {
    return checkedInAt;
  }

  /**
   * Sets the raw checkedInAt.
   *
   * @param checkedInAt raw value
   */
  public void setCheckedInAt(Object checkedInAt) {
    this.checkedInAt = checkedInAt;
  }

  /**
   * The corrected check-out (same format), or {@code null} or absent for none.
   *
   * @return raw value
   */
  @Schema(type = "string", format = "date-time", nullable = true)
  public Object getCheckedOutAt() {
    return checkedOutAt;
  }

  /**
   * Sets the raw checkedOutAt.
   *
   * @param checkedOutAt raw value
   */
  public void setCheckedOutAt(Object checkedOutAt) {
    this.checkedOutAt = checkedOutAt;
  }

  /**
   * The closed reason code.
   *
   * @return raw value
   */
  @Schema(
      type = "string",
      allowableValues = {
        "DEVICE_OR_CONNECTIVITY",
        "SUPERVISOR_OR_EMPLOYEE_REPORT",
        "DATA_ENTRY_ERROR",
        "SYSTEM_ERROR",
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

  /**
   * The administrator's explanation: 10 to 500 characters after trimming; no control character,
   * markup or link (Restricted HR/Operations).
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
