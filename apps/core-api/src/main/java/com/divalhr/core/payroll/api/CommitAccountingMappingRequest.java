package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-096: the previewed mapping version, the evidence the preview returned and the confirmation.
 * Every value is read as {@code Object} so that a wrong type is a field error, never a
 * deserialization failure. Restricted financial data: nothing here is ever logged.
 */
@Schema(name = "CommitAccountingMapping")
public class CommitAccountingMappingRequest extends StrictRequest {

  private Object currency;

  private Object effectiveFrom;

  private Object accounts;

  private Object expectedVersion;

  private Object previewDigest;

  private Object expiresAt;

  private Object understandExportOnly;

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

  /**
   * The expectedVersion value.
   *
   * @return raw value
   */
  @Schema(type = "integer")
  public Object getExpectedVersion() {
    return expectedVersion;
  }

  public void setExpectedVersion(Object expectedVersion) {
    this.expectedVersion = expectedVersion;
  }

  /**
   * The previewDigest value.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getPreviewDigest() {
    return previewDigest;
  }

  public void setPreviewDigest(Object previewDigest) {
    this.previewDigest = previewDigest;
  }

  /**
   * The expiresAt value.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Object expiresAt) {
    this.expiresAt = expiresAt;
  }

  /**
   * The understandExportOnly value.
   *
   * @return raw value
   */
  @Schema(type = "boolean")
  public Object getUnderstandExportOnly() {
    return understandExportOnly;
  }

  public void setUnderstandExportOnly(Object understandExportOnly) {
    this.understandExportOnly = understandExportOnly;
  }
}
