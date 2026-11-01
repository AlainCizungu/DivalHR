package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-094C: the evidence an approval preview returned and the three confirmations. Every value is
 * read as {@code Object} so that a wrong type is a field error, never a deserialization failure.
 */
@Schema(name = "CommitPayrollApproval")
public class CommitPayrollApprovalRequest extends StrictRequest {

  private Object expectedVersion;
  private Object sourceRunDigest;
  private Object calculationDigest;
  private Object profileDigest;
  private Object rulePackDigest;
  private Object previewDigest;
  private Object expiresAt;
  private Object reviewedTotals;
  private Object reviewedExceptions;
  private Object understandsNoPayment;

  /**
   * The package chain version the preview showed.
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
   * The source-run digest the preview returned.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getSourceRunDigest() {
    return sourceRunDigest;
  }

  public void setSourceRunDigest(Object sourceRunDigest) {
    this.sourceRunDigest = sourceRunDigest;
  }

  /**
   * The calculation digest the preview returned.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getCalculationDigest() {
    return calculationDigest;
  }

  public void setCalculationDigest(Object calculationDigest) {
    this.calculationDigest = calculationDigest;
  }

  /**
   * The profile digest the preview returned.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getProfileDigest() {
    return profileDigest;
  }

  public void setProfileDigest(Object profileDigest) {
    this.profileDigest = profileDigest;
  }

  /**
   * The rule-pack digest the preview returned.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getRulePackDigest() {
    return rulePackDigest;
  }

  public void setRulePackDigest(Object rulePackDigest) {
    this.rulePackDigest = rulePackDigest;
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
   * I reviewed the totals. Must be true.
   *
   * @return raw value
   */
  @Schema(type = "boolean")
  public Object getReviewedTotals() {
    return reviewedTotals;
  }

  public void setReviewedTotals(Object reviewedTotals) {
    this.reviewedTotals = reviewedTotals;
  }

  /**
   * I reviewed the exceptions. Must be true.
   *
   * @return raw value
   */
  @Schema(type = "boolean")
  public Object getReviewedExceptions() {
    return reviewedExceptions;
  }

  public void setReviewedExceptions(Object reviewedExceptions) {
    this.reviewedExceptions = reviewedExceptions;
  }

  /**
   * I understand that approving does not pay anyone. Must be true.
   *
   * @return raw value
   */
  @Schema(type = "boolean")
  public Object getUnderstandsNoPayment() {
    return understandsNoPayment;
  }

  public void setUnderstandsNoPayment(Object understandsNoPayment) {
    this.understandsNoPayment = understandsNoPayment;
  }
}
