package com.divalhr.core.people.schedule.api;

import com.divalhr.core.people.api.StrictRequest;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A proposed schedule (MVP-050A): one site, inclusive dates and 1 to 500 slots. Values are bound
 * raw and validated by the service, so every problem is reported with a stable constraint and
 * nothing is echoed.
 */
public class WorkScheduleRequest extends StrictRequest {
  private Object siteId;
  private Object firstDate;
  private Object lastDate;
  private Object slots;

  /**
   * The site (an active site of the organization).
   *
   * @return raw value
   */
  @Schema(type = "string", format = "uuid")
  public Object getSiteId() {
    return siteId;
  }

  /**
   * Sets: the site.
   *
   * @param siteId raw value
   */
  public void setSiteId(Object siteId) {
    this.siteId = siteId;
  }

  /**
   * First date ({@code YYYY-MM-DD}, inclusive, today or later in the organization's calendar).
   *
   * @return raw value
   */
  @Schema(type = "string", format = "date")
  public Object getFirstDate() {
    return firstDate;
  }

  /**
   * Sets: the first date.
   *
   * @param firstDate raw value
   */
  public void setFirstDate(Object firstDate) {
    this.firstDate = firstDate;
  }

  /**
   * Last date ({@code YYYY-MM-DD}, inclusive, at most 30 days after the first).
   *
   * @return raw value
   */
  @Schema(type = "string", format = "date")
  public Object getLastDate() {
    return lastDate;
  }

  /**
   * Sets: the last date.
   *
   * @param lastDate raw value
   */
  public void setLastDate(Object lastDate) {
    this.lastDate = lastDate;
  }

  /**
   * The slots, 1 to 500, in display order; each one required worker.
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
}
