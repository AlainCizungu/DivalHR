package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-120B request bodies of the controlled partner handoff API. Every value is read as {@code
 * Object} so that a wrong type is a field error, never a deserialization failure; an unknown
 * property is recorded (the contract declares {@code additionalProperties: false}). Consent
 * evidence, digests and signatures are never logged or echoed in an error.
 */
public final class SalaryAdvanceHandoffBodies {

  private SalaryAdvanceHandoffBodies() {}

  /**
   * The employee's authorization of the handoff: the previewed request, the digest and the consent.
   */
  @Schema(name = "CommitSalaryAdvanceHandoff")
  public static class CommitHandoff extends StrictRequest {

    private Object expectedRequestVersion;
    private Object previewDigest;
    private Object expiresAt;
    private Object consent;
    private Object understandsSyntheticTest;

    @Schema(type = "integer")
    public Object getExpectedRequestVersion() {
      return expectedRequestVersion;
    }

    public void setExpectedRequestVersion(Object expectedRequestVersion) {
      this.expectedRequestVersion = expectedRequestVersion;
    }

    @Schema(type = "string")
    public Object getPreviewDigest() {
      return previewDigest;
    }

    public void setPreviewDigest(Object previewDigest) {
      this.previewDigest = previewDigest;
    }

    @Schema(type = "string", format = "date-time")
    public Object getExpiresAt() {
      return expiresAt;
    }

    public void setExpiresAt(Object expiresAt) {
      this.expiresAt = expiresAt;
    }

    @Schema(type = "object")
    public Object getConsent() {
      return consent;
    }

    public void setConsent(Object consent) {
      this.consent = consent;
    }

    @Schema(type = "boolean")
    public Object getUnderstandsSyntheticTest() {
      return understandsSyntheticTest;
    }

    public void setUnderstandsSyntheticTest(Object understandsSyntheticTest) {
      this.understandsSyntheticTest = understandsSyntheticTest;
    }
  }

  /** A pre-receipt withdrawal: the version the caller saw and the confirmation. */
  @Schema(name = "SalaryAdvanceHandoffWithdraw")
  public static class WithdrawHandoff extends VersionedBody {}

  /** An administrator reconcile or retry: the version the caller saw and the confirmation. */
  @Schema(name = "SalaryAdvanceHandoffReconcile")
  public static class ReconcileHandoff extends VersionedBody {}

  /** The shared shape of a versioned, confirmed command. */
  public abstract static class VersionedBody extends StrictRequest {

    private Object expectedVersion;
    private Object confirmed;

    @Schema(type = "integer")
    public Object getExpectedVersion() {
      return expectedVersion;
    }

    public void setExpectedVersion(Object expectedVersion) {
      this.expectedVersion = expectedVersion;
    }

    @Schema(type = "boolean")
    public Object getConfirmed() {
      return confirmed;
    }

    public void setConfirmed(Object confirmed) {
      this.confirmed = confirmed;
    }
  }

  /** A signed partner status callback. */
  @Schema(name = "SyntheticPartnerCallback")
  public static class PartnerCallback extends StrictRequest {

    private Object applicationId;
    private Object reviewState;
    private Object reasonCode;
    private Object issuedAt;

    @Schema(type = "string", format = "uuid")
    public Object getApplicationId() {
      return applicationId;
    }

    public void setApplicationId(Object applicationId) {
      this.applicationId = applicationId;
    }

    @Schema(type = "string")
    public Object getReviewState() {
      return reviewState;
    }

    public void setReviewState(Object reviewState) {
      this.reviewState = reviewState;
    }

    @Schema(type = "string")
    public Object getReasonCode() {
      return reasonCode;
    }

    public void setReasonCode(Object reasonCode) {
      this.reasonCode = reasonCode;
    }

    @Schema(type = "string", format = "date-time")
    public Object getIssuedAt() {
      return issuedAt;
    }

    public void setIssuedAt(Object issuedAt) {
      this.issuedAt = issuedAt;
    }
  }
}
