package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-095B: the evidence a submission preview returned and the two confirmations. Every value is
 * read as {@code Object} so that a wrong type is a field error, never a deserialization failure.
 * Restricted financial and HR data: nothing here is ever logged.
 */
@Schema(name = "CommitPaymentSubmission")
public class CommitPaymentSubmissionRequest extends StrictRequest {

  private Object expectedBatchVersion;

  private Object batchDigest;

  private Object previewDigest;

  private Object expiresAt;

  private Object reviewedProviderAndTotals;

  private Object understandAcceptedIsNotPaid;

  /**
   * The expectedBatchVersion value.
   *
   * @return raw value
   */
  @Schema(type = "integer")
  public Object getExpectedBatchVersion() {
    return expectedBatchVersion;
  }

  public void setExpectedBatchVersion(Object expectedBatchVersion) {
    this.expectedBatchVersion = expectedBatchVersion;
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
   * The reviewedProviderAndTotals value.
   *
   * @return raw value
   */
  @Schema(type = "boolean")
  public Object getReviewedProviderAndTotals() {
    return reviewedProviderAndTotals;
  }

  public void setReviewedProviderAndTotals(Object reviewedProviderAndTotals) {
    this.reviewedProviderAndTotals = reviewedProviderAndTotals;
  }

  /**
   * The understandAcceptedIsNotPaid value.
   *
   * @return raw value
   */
  @Schema(type = "boolean")
  public Object getUnderstandAcceptedIsNotPaid() {
    return understandAcceptedIsNotPaid;
  }

  public void setUnderstandAcceptedIsNotPaid(Object understandAcceptedIsNotPaid) {
    this.understandAcceptedIsNotPaid = understandAcceptedIsNotPaid;
  }
}
