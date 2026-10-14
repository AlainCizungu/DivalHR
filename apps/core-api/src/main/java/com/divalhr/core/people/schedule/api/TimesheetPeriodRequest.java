package com.divalhr.core.people.schedule.api;

import com.divalhr.core.people.api.StrictRequest;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-070A: one site and one site-local week (Monday 00:00 to the next Monday 00:00). Every value
 * is raw: the command validates shape and range and never trusts a tenant, employee or boundary
 * from the request.
 */
@Schema(name = "TimesheetPeriodRequest")
public class TimesheetPeriodRequest extends StrictRequest {
  private Object siteId;
  private Object weekStart;

  /**
   * The site (any malformed, unknown or foreign value is the same safe {@code 404}).
   *
   * @return raw value
   */
  @Schema(type = "string", format = "uuid")
  public Object getSiteId() {
    return siteId;
  }

  /**
   * Sets the raw siteId.
   *
   * @param siteId raw value
   */
  public void setSiteId(Object siteId) {
    this.siteId = siteId;
  }

  /**
   * The site-local Monday of the week, {@code YYYY-MM-DD}: the current week or an earlier one.
   *
   * @return raw value
   */
  @Schema(type = "string", format = "date")
  public Object getWeekStart() {
    return weekStart;
  }

  /**
   * Sets the raw weekStart.
   *
   * @param weekStart raw value
   */
  public void setWeekStart(Object weekStart) {
    this.weekStart = weekStart;
  }
}
