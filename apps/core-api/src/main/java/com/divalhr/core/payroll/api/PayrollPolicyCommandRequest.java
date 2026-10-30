package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-094A: the payroll policy to preview. Every value is read as {@code Object} so that a wrong type is a field error, never a
 * deserialization failure.
 */
@Schema(name = "PayrollPolicyCommand")
public class PayrollPolicyCommandRequest extends StrictRequest {

  private Object effectiveFrom;
  private Object payDay;
  private Object prorationMethod;

  /**
   * The first day the policy is in force.
   *
   * @return raw value
   */
  @Schema(type = "string", format = "date")
  public Object getEffectiveFrom() {
    return effectiveFrom;
  }

  public void setEffectiveFrom(Object effectiveFrom) {
    this.effectiveFrom = effectiveFrom;
  }

  /**
   * The pay day: kind DAY with a day from 1 to 28, or LAST_DAY with a null day.
   *
   * @return raw value
   */
  @Schema(type = "object")
  public Object getPayDay() {
    return payDay;
  }

  public void setPayDay(Object payDay) {
    this.payDay = payDay;
  }

  /**
   * The proration method (CALENDAR_DAYS, FIXED_30_DAYS or NONE).
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getProrationMethod() {
    return prorationMethod;
  }

  public void setProrationMethod(Object prorationMethod) {
    this.prorationMethod = prorationMethod;
  }
}
