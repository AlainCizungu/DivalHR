package com.divalhr.core.operations.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The values of an expense revision (MVP-081, D81-3). Every value is kept raw so that each defect
 * maps to a stable {@code {field, constraint}} pair; unknown properties are captured and rejected.
 * Tenant, employee and actor are never fields.
 */
@Schema(hidden = true)
public abstract class ExpenseDraftRequest extends StrictRequest {
  private Object businessDate;
  private Object currency;
  private Object amount;
  private Object category;
  private Object merchant;
  private Object description;

  /**
   * The raw businessDate.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getBusinessDate() {
    return businessDate;
  }

  /**
   * Sets the raw businessDate.
   *
   * @param businessDate raw value
   */
  public void setBusinessDate(Object businessDate) {
    this.businessDate = businessDate;
  }

  /**
   * The raw currency.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getCurrency() {
    return currency;
  }

  /**
   * Sets the raw currency.
   *
   * @param currency raw value
   */
  public void setCurrency(Object currency) {
    this.currency = currency;
  }

  /**
   * The raw amount.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getAmount() {
    return amount;
  }

  /**
   * Sets the raw amount.
   *
   * @param amount raw value
   */
  public void setAmount(Object amount) {
    this.amount = amount;
  }

  /**
   * The raw category.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getCategory() {
    return category;
  }

  /**
   * Sets the raw category.
   *
   * @param category raw value
   */
  public void setCategory(Object category) {
    this.category = category;
  }

  /**
   * The raw merchant.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getMerchant() {
    return merchant;
  }

  /**
   * Sets the raw merchant.
   *
   * @param merchant raw value
   */
  public void setMerchant(Object merchant) {
    this.merchant = merchant;
  }

  /**
   * The raw description.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getDescription() {
    return description;
  }

  /**
   * Sets the raw description.
   *
   * @param description raw value
   */
  public void setDescription(Object description) {
    this.description = description;
  }
}
