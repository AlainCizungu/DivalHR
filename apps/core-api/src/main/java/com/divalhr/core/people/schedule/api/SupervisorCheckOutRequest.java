package com.divalhr.core.people.schedule.api;

import com.divalhr.core.people.api.StrictRequest;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-060B: a manager's check-out of a direct report's open record (the record comes from the
 * path). Every value is kept raw so that each defect maps to a stable {@code {field, constraint}}
 * pair; unknown properties are captured and rejected.
 */
@Schema(name = "SupervisorCheckOutRequest")
public class SupervisorCheckOutRequest extends StrictRequest {
  private Object reasonLocale;
  private Object reason;

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
