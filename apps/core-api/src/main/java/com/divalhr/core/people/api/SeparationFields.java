package com.divalhr.core.people.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** The fields of a separation command (MVP-022), validated by the service as raw JSON values. */
public abstract class SeparationFields extends StrictRequest {

  private Object lastDay;
  private Object reasonCode;
  private Object accessTiming;
  private Object reportPlan;

  /**
   * Inclusive last day (ISO date).
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getLastDay() {
    return lastDay;
  }

  /**
   * Sets: inclusive last day (ISO date).
   *
   * @param lastDay raw value
   */
  public void setLastDay(Object lastDay) {
    this.lastDay = lastDay;
  }

  /**
   * Separation reason code.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getReasonCode() {
    return reasonCode;
  }

  /**
   * Sets: separation reason code.
   *
   * @param reasonCode raw value
   */
  public void setReasonCode(Object reasonCode) {
    this.reasonCode = reasonCode;
  }

  /**
   * END_OF_LAST_DAY or IMMEDIATELY.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getAccessTiming() {
    return accessTiming;
  }

  /**
   * Sets: eND_OF_LAST_DAY or IMMEDIATELY.
   *
   * @param accessTiming raw value
   */
  public void setAccessTiming(Object accessTiming) {
    this.accessTiming = accessTiming;
  }

  /**
   * Direct-report plan, or null.
   *
   * @return raw value
   */
  @Schema(type = "object")
  public Object getReportPlan() {
    return reportPlan;
  }

  /**
   * Sets: direct-report plan, or null.
   *
   * @param reportPlan raw value
   */
  public void setReportPlan(Object reportPlan) {
    this.reportPlan = reportPlan;
  }
}
