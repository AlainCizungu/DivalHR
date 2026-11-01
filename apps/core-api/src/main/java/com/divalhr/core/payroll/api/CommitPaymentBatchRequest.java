package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-095A: the evidence a batch preview returned and the two confirmations. Every value is read as {@code Object} so that a wrong type is a field error, never a
 * deserialization failure. Restricted financial and HR data: nothing here is ever logged.
 */
@Schema(name = "CommitPaymentBatch")
public class CommitPaymentBatchRequest extends StrictRequest {

  private Object expectedVersion;
  private Object packageVersion;
  private Object previewDigest;
  private Object expiresAt;
  private Object reviewedTotalsAndDestinations;
  private Object noMoneySentAcknowledged;

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
   * The packageVersion value.
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
   * The reviewedTotalsAndDestinations value.
   *
   * @return raw value
   */
  @Schema(type = "boolean")
  public Object getReviewedTotalsAndDestinations() {
    return reviewedTotalsAndDestinations;
  }

  public void setReviewedTotalsAndDestinations(Object reviewedTotalsAndDestinations) {
    this.reviewedTotalsAndDestinations = reviewedTotalsAndDestinations;
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
