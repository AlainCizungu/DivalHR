package com.divalhr.core.people.schedule.api;

import com.divalhr.core.people.api.StrictRequest;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-060A: an employee's check-in on one of their own scheduled shifts. The value is kept raw so
 * that each defect maps to a stable {@code {field, constraint}} pair; unknown properties are
 * captured and rejected. Tenant, employee, employment, schedule, instant and actor are never
 * fields: the instant is the server's.
 */
@Schema(name = "CheckInAttendanceRequest")
public class CheckInAttendanceRequest extends StrictRequest {
  private Object slotId;

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
}
