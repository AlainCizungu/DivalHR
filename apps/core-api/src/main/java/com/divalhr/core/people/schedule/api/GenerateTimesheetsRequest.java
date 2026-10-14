package com.divalhr.core.people.schedule.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-070A: generate the week's draft timesheets of one site. Every value is raw: the command
 * validates shape and range and never trusts a tenant, employee or boundary from the request.
 */
@Schema(name = "GenerateTimesheetsRequest")
public class GenerateTimesheetsRequest extends TimesheetPeriodRequest {
  private Object confirm;

  /**
   * Must be {@code true}: the administrator confirmed the reviewed preview.
   *
   * @return raw value
   */
  @Schema(type = "boolean")
  public Object getConfirm() {
    return confirm;
  }

  /**
   * Sets the raw confirm.
   *
   * @param confirm raw value
   */
  public void setConfirm(Object confirm) {
    this.confirm = confirm;
  }
}
