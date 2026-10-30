package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-094A: the previewed run plus what the preview returned. Every value is read as {@code Object}
 * so that a wrong type is a field error, never a deserialization failure.
 */
@Schema(name = "CommitPayrollRun")
public class CommitPayrollRunRequest extends StrictRequest {

  private Object period;
  private Object currency;
  private Object expectedVersion;
  private Object inputDigest;
  private Object previewDigest;
  private Object expiresAt;
  private Object confirmed;

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

  /**
   * The chain version the preview showed.
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
   * The input digest the preview returned.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getInputDigest() {
    return inputDigest;
  }

  public void setInputDigest(Object inputDigest) {
    this.inputDigest = inputDigest;
  }

  /**
   * The preview digest the preview returned.
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
   * The expiry the preview returned.
   *
   * @return raw value
   */
  @Schema(type = "string", format = "date-time")
  public Object getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Object expiresAt) {
    this.expiresAt = expiresAt;
  }

  /**
   * Must be true.
   *
   * @return raw value
   */
  @Schema(type = "boolean")
  public Object getConfirmed() {
    return confirmed;
  }

  public void setConfirmed(Object confirmed) {
    this.confirmed = confirmed;
  }
}
