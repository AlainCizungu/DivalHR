package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-094A: the monthly payroll run to preview. Every value is read as {@code Object} so that a
 * wrong type is a field error, never a deserialization failure.
 */
@Schema(name = "PayrollRunCommand")
public class PayrollRunCommandRequest extends StrictRequest {

  private Object period;
  private Object currency;

  /**
   * The calendar month, YYYY-MM.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getPeriod() {
    return period;
  }

  public void setPeriod(Object period) {
    this.period = period;
  }

  /**
   * The run currency (CDF or USD).
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getCurrency() {
    return currency;
  }

  public void setCurrency(Object currency) {
    this.currency = currency;
  }
}
