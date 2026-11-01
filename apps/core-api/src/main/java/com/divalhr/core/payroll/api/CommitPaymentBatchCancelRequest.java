package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-095A: the reason, the evidence the cancel preview returned and the acknowledgement. Every value is read as {@code Object} so that a wrong type is a field error, never a
 * deserialization failure. Restricted financial and HR data: nothing here is ever logged.
 */
@Schema(name = "CommitPaymentBatchCancel")
public class CommitPaymentBatchCancelRequest extends StrictRequest {

  private Object reasonCode;
  private Object batchVersion;
  private Object batchDigest;
  private Object previewDigest;
  private Object expiresAt;
  private Object noMoneySentAcknowledged;

  /**
   * The reasonCode value.
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
   * The batchVersion value.
   *
   * @return raw value
   */
  @Schema(type = "integer")
  public Object getBatchVersion() {
    return batchVersion;
  }

  public void setBatchVersion(Object batchVersion) {
    this.batchVersion = batchVersion;
  }

  /**
   * The batchDigest value.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getBatchDigest() {
    return batchDigest;
  }

  public void setBatchDigest(Object batchDigest) {
    this.batchDigest = batchDigest;
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
   * The noMoneySentAcknowledged value.
   *
   * @return raw value
   */
  @Schema(type = "boolean")
  public Object getNoMoneySentAcknowledged() {
    return noMoneySentAcknowledged;
  }

  public void setNoMoneySentAcknowledged(Object noMoneySentAcknowledged) {
    this.noMoneySentAcknowledged = noMoneySentAcknowledged;
  }

}
