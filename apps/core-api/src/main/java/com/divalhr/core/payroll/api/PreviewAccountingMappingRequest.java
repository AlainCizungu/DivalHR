package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-096: the currency, effective date and five account codes of a mapping version to preview.
 * Every value is read as {@code Object} so that a wrong type is a field error, never a
 * deserialization failure. Restricted financial data: nothing here is ever logged.
 */
@Schema(name = "PreviewAccountingMapping")
public class PreviewAccountingMappingRequest extends StrictRequest {

  private Object currency;

  private Object effectiveFrom;

  private Object accounts;

  /**
   * The currency value.
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
   * The effectiveFrom value.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getEffectiveFrom() {
    return effectiveFrom;
  }

  public void setEffectiveFrom(Object effectiveFrom) {
    this.effectiveFrom = effectiveFrom;
  }

  /**
   * The accounts value.
   *
   * @return raw value
   */
  @Schema(type = "object")
  public Object getAccounts() {
    return accounts;
  }

  public void setAccounts(Object accounts) {
    this.accounts = accounts;
  }
}
