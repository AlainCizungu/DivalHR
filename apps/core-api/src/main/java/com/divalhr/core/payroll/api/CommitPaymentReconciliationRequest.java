package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-095C: the evidence an investigation preview returned and the two confirmations. Every value
 * is read as {@code Object} so that a wrong type is a field error, never a deserialization failure.
 * Nothing here is ever logged.
 */
@Schema(name = "CommitPaymentReconciliation")
public class CommitPaymentReconciliationRequest extends StrictRequest {

  private Object expectedSubmissionVersion;

  private Object expectedSubmissionState;

  private Object previewDigest;

  private Object expiresAt;

  private Object understandInquiryDoesNotResend;

  private Object understandAcceptedIsNotPaid;

  /**
   * The expectedSubmissionVersion value.
   *
   * @return raw value
   */
  @Schema(type = "integer")
  public Object getExpectedSubmissionVersion() {
    return expectedSubmissionVersion;
  }

  public void setExpectedSubmissionVersion(Object expectedSubmissionVersion) {
    this.expectedSubmissionVersion = expectedSubmissionVersion;
  }

  /**
   * The expectedSubmissionState value.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getExpectedSubmissionState() {
    return expectedSubmissionState;
  }

  public void setExpectedSubmissionState(Object expectedSubmissionState) {
    this.expectedSubmissionState = expectedSubmissionState;
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
   * The understandInquiryDoesNotResend value.
   *
   * @return raw value
   */
  @Schema(type = "boolean")
  public Object getUnderstandInquiryDoesNotResend() {
    return understandInquiryDoesNotResend;
  }

  public void setUnderstandInquiryDoesNotResend(Object understandInquiryDoesNotResend) {
    this.understandInquiryDoesNotResend = understandInquiryDoesNotResend;
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
