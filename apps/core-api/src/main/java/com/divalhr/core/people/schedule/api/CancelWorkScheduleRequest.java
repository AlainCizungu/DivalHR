package com.divalhr.core.people.schedule.api;

import com.divalhr.core.people.api.StrictRequest;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Cancellation of an entirely future published schedule (MVP-050B, D50B-5): the reason and an
 * explicit confirmation. Values are bound raw and validated by the service; nothing is echoed.
 */
public class CancelWorkScheduleRequest extends StrictRequest {
  private Object reason;
  private Object confirm;

  /**
   * Why the schedule is cancelled (decision-reason grammar version 1; Restricted HR).
   *
   * @return raw value
   */
  @Schema(type = "string", minLength = 2, maxLength = 500)
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

  /**
   * Must be {@code true}: the administrator confirmed the cancellation.
   *
   * @return raw value
   */
  @Schema(type = "boolean")
  public Object getConfirm() {
    return confirm;
  }

  /**
   * Sets: the confirmation.
   *
   * @param confirm raw value
   */
  public void setConfirm(Object confirm) {
    this.confirm = confirm;
  }
}
