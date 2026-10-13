package com.divalhr.core.people.schedule.api;

import com.divalhr.core.people.api.StrictRequest;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-061A: one attendance event captured while the employee was offline. Values are kept raw so
 * that each defect maps to a stable {@code {field, constraint}} pair; unknown properties are
 * captured and rejected. Tenant, employee, employment, schedule, record, receipt instant, recording
 * method and actor are never fields.
 */
@Schema(name = "SyncOfflineAttendanceRequest")
public class SyncOfflineAttendanceRequest extends StrictRequest {
  private Object clientEventId;
  private Object slotId;
  private Object type;
  private Object deviceOccurredAt;
  private Object deviceOffsetMinutes;

  /**
   * The client's immutable identity of the event.
   *
   * @return raw value
   */
  @Schema(type = "string", format = "uuid")
  public Object getClientEventId() {
    return clientEventId;
  }

  /**
   * Sets the raw clientEventId.
   *
   * @param clientEventId raw value
   */
  public void setClientEventId(Object clientEventId) {
    this.clientEventId = clientEventId;
  }

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
   * CHECK_IN or CHECK_OUT.
   *
   * @return raw value
   */
  @Schema(
      type = "string",
      allowableValues = {"CHECK_IN", "CHECK_OUT"})
  public Object getType() {
    return type;
  }

  /**
   * Sets the raw type.
   *
   * @param type raw value
   */
  public void setType(Object type) {
    this.type = type;
  }

  /**
   * The claimed device time: RFC 3339 with an explicit offset.
   *
   * @return raw value
   */
  @Schema(type = "string", format = "date-time")
  public Object getDeviceOccurredAt() {
    return deviceOccurredAt;
  }

  /**
   * Sets the raw deviceOccurredAt.
   *
   * @param deviceOccurredAt raw value
   */
  public void setDeviceOccurredAt(Object deviceOccurredAt) {
    this.deviceOccurredAt = deviceOccurredAt;
  }

  /**
   * The device's UTC offset in minutes at capture.
   *
   * @return raw value
   */
  @Schema(type = "integer", minimum = "-840", maximum = "840")
  public Object getDeviceOffsetMinutes() {
    return deviceOffsetMinutes;
  }

  /**
   * Sets the raw deviceOffsetMinutes.
   *
   * @param deviceOffsetMinutes raw value
   */
  public void setDeviceOffsetMinutes(Object deviceOffsetMinutes) {
    this.deviceOffsetMinutes = deviceOffsetMinutes;
  }
}
