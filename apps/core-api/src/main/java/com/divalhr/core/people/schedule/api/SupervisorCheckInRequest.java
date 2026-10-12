package com.divalhr.core.people.schedule.api;

import com.divalhr.core.people.api.StrictRequest;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-060B: a manager's check-in of a direct report on a scheduled shift. Every value is kept raw
 * so that each defect maps to a stable {@code {field, constraint}} pair; unknown properties are
 * captured and rejected. Tenant, employee, manager, employment, actor and instant are never fields.
 */
@Schema(name = "SupervisorCheckInRequest")
public class SupervisorCheckInRequest extends StrictRequest {
  private Object slotId;
  private Object reasonLocale;
  private Object reason;

  /**
   * The shift's slot.
   *
   * @return raw value
   */
  @Schema(type = "string", format = "uuid")
  public Object getSlotId() {
    return slotId;
  }

  /**
   * Sets the raw slotId.
   *
   * @param slotId raw value
   */
  public void setSlotId(Object slotId) {
    this.slotId = slotId;
  }

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
