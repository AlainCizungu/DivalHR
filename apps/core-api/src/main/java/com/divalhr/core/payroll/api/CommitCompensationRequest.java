package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-093A: the previewed command plus what the preview returned. Every value is read as {@code
 * Object} so that a wrong type is a field error, never a deserialization failure.
 */
@Schema(name = "CommitCompensation")
public class CommitCompensationRequest extends StrictRequest {

  private Object action;
  private Object effectiveFrom;
  private Object currency;
  private Object amount;
  private Object targetChangeId;
  private Object reasonCode;
  private Object explanation;
  private Object expectedVersion;
  private Object employmentDigest;
  private Object previewDigest;
  private Object expiresAt;
  private Object acknowledgeRetroactive;
  private Object confirmed;

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

  /**
   * The compensation version the preview showed.
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
   * The employment digest the preview returned.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getEmploymentDigest() {
    return employmentDigest;
  }

  public void setEmploymentDigest(Object employmentDigest) {
    this.employmentDigest = employmentDigest;
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
   * Whether the retroactive effect is acknowledged.
   *
   * @return raw value
   */
  @Schema(type = "boolean")
  public Object getAcknowledgeRetroactive() {
    return acknowledgeRetroactive;
  }

  public void setAcknowledgeRetroactive(Object acknowledgeRetroactive) {
    this.acknowledgeRetroactive = acknowledgeRetroactive;
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
