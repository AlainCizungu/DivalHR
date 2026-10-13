package com.divalhr.core.people.schedule.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-062: the proposed creation of a missing attendance record (CREATE_MISSING_RECORD): the slot,
 * its effective assignee as reviewed, the corrected check-in and optional check-out, the reason and
 * the explanation.
 */
@Schema(name = "MissingAttendanceCorrectionRequest")
public class MissingAttendanceCorrectionRequest extends AttendanceCorrectionRequest {
  private Object slotId;
  private Object employeeId;

  /**
   * The slot of the missing record.
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
   * The slot's effective assignee, as reviewed.
   *
   * @return raw value
   */
  @Schema(type = "string", format = "uuid")
  public Object getEmployeeId() {
    return employeeId;
  }

  /**
   * Sets the raw employeeId.
   *
   * @param employeeId raw value
   */
  public void setEmployeeId(Object employeeId) {
    this.employeeId = employeeId;
  }
}
