package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-094A: the previewed policy plus what the preview returned. Every value is read as {@code
 * Object} so that a wrong type is a field error, never a deserialization failure.
 */
@Schema(name = "CommitPayrollPolicy")
public class CommitPayrollPolicyRequest extends StrictRequest {

  private Object effectiveFrom;
  private Object payDay;
  private Object prorationMethod;
  private Object expectedVersion;
  private Object previewDigest;
  private Object expiresAt;
  private Object confirmed;

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

  /**
   * The policy version the preview showed.
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
