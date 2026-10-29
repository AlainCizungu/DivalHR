package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-093A: the command to preview (establish, change or correct monthly compensation). Every
 * value is read as {@code Object} so that a wrong type is a field error, never a deserialization
 * failure. Restricted financial data: the amount and the explanation are never logged.
 */
@Schema(name = "CompensationCommand")
public class CompensationCommandRequest extends StrictRequest {

  private Object action;
  private Object effectiveFrom;
  private Object currency;
  private Object amount;
  private Object targetChangeId;
  private Object reasonCode;
  private Object explanation;

  /**
   * The action (ESTABLISHED, CHANGED or CORRECTED).
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getAction() {
    return action;
  }

  public void setAction(Object action) {
    this.action = action;
  }

  /**
   * The first day of the value.
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
   * The currency (CDF or USD).
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

  /**
   * The amount, a decimal string (never a JSON number).
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getAmount() {
    return amount;
  }

  public void setAmount(Object amount) {
    this.amount = amount;
  }

  /**
   * The origin change of the active fact to correct, or null.
   *
   * @return raw value
   */
  @Schema(type = "string", format = "uuid")
  public Object getTargetChangeId() {
    return targetChangeId;
  }

  public void setTargetChangeId(Object targetChangeId) {
    this.targetChangeId = targetChangeId;
  }

  /**
   * The correction reason code, or null.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getReasonCode() {
    return reasonCode;
  }

  public void setReasonCode(Object reasonCode) {
    this.reasonCode = reasonCode;
  }

  /**
   * The restricted correction explanation, or null.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getExplanation() {
    return explanation;
  }

  public void setExplanation(Object explanation) {
    this.explanation = explanation;
  }
}
