package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-090A: one legal entity and one inclusive period (business-zone dates). Every value is read as
 * {@code Object} so that a wrong type is a field error, never a deserialization failure.
 */
@Schema(name = "PayrollInputPreviewRequest")
public class PayrollInputPreviewRequest extends StrictRequest {

  private Object legalEntityId;
  private Object periodStart;
  private Object periodEnd;

  /**
   * The legal entity.
   *
   * @return raw value
   */
  @Schema(type = "string", format = "uuid")
  public Object getLegalEntityId() {
    return legalEntityId;
  }

  public void setLegalEntityId(Object legalEntityId) {
    this.legalEntityId = legalEntityId;
  }

  /**
   * The first date (inclusive).
   *
   * @return raw value
   */
  @Schema(type = "string", format = "date")
  public Object getPeriodStart() {
    return periodStart;
  }

  public void setPeriodStart(Object periodStart) {
    this.periodStart = periodStart;
  }

  /**
   * The last date (inclusive).
   *
   * @return raw value
   */
  @Schema(type = "string", format = "date")
  public Object getPeriodEnd() {
    return periodEnd;
  }

  public void setPeriodEnd(Object periodEnd) {
    this.periodEnd = periodEnd;
  }
}
