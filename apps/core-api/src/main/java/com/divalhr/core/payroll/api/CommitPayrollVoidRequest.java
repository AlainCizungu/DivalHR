package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-094C: the evidence a void preview returned and the no-payment confirmation. Every value is
 * read as {@code Object} so that a wrong type is a field error, never a deserialization failure.
 */
@Schema(name = "CommitPayrollVoid")
public class CommitPayrollVoidRequest extends StrictRequest {

  private Object reasonCode;
  private Object explanation;
  private Object packageVersion;
  private Object approvalDigest;
  private Object previewDigest;
  private Object expiresAt;
  private Object noPaymentAcknowledged;

  /**
   * The previewed closed void reason.
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
   * The previewed explanation, or null.
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
   * The package version the preview showed.
   *
   * @return raw value
   */
  @Schema(type = "integer")
  public Object getPackageVersion() {
    return packageVersion;
  }

  public void setPackageVersion(Object packageVersion) {
    this.packageVersion = packageVersion;
  }

  /**
   * The approval digest the preview returned.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getApprovalDigest() {
    return approvalDigest;
  }

  public void setApprovalDigest(Object approvalDigest) {
    this.approvalDigest = approvalDigest;
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
   * No payment or disbursement was made. Must be true.
   *
   * @return raw value
   */
  @Schema(type = "boolean")
  public Object getNoPaymentAcknowledged() {
    return noPaymentAcknowledged;
  }

  public void setNoPaymentAcknowledged(Object noPaymentAcknowledged) {
    this.noPaymentAcknowledged = noPaymentAcknowledged;
  }
}
