package com.divalhr.core.people.schedule.api;

import com.divalhr.core.people.api.StrictRequest;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A proposed full replacement of an entirely future published schedule (MVP-050B, D50B-6): every
 * slot of the new schedule and the reason. The site and the period are the original's and cannot be
 * sent. Values are bound raw and validated by the service; nothing is echoed.
 */
public class WorkScheduleReplacementRequest extends StrictRequest {
  private Object slots;
  private Object reason;

  /**
   * The replacement's slots, 1 to 500, in display order; each one required worker.
   *
   * @return raw value
   */
  @ArraySchema(schema = @Schema(implementation = WorkScheduleSlotInput.class))
  public Object getSlots() {
    return slots;
  }

  /**
   * Sets: the slots.
   *
   * @param slots raw value
   */
  public void setSlots(Object slots) {
    this.slots = slots;
  }

  /**
   * Why the schedule is replaced (decision-reason grammar version 1; Restricted HR).
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
}
