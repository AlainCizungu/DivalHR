package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-095A: the destination, the evidence its preview returned and the confirmation. Every value is
 * read as {@code Object} so that a wrong type is a field error, never a deserialization failure.
 * Restricted financial and HR data: nothing here is ever logged.
 */
@Schema(name = "CommitPaymentDestination")
public class CommitPaymentDestinationRequest extends StrictRequest {

  private Object method;
  private Object countryCode;
  private Object providerCode;
  private Object accountNumber;
  private Object holderName;
  private Object branchCode;
  private Object phoneNumber;
  private Object effectiveFrom;
  private Object reasonCode;
  private Object explanation;
  private Object expectedVersion;
  private Object previewDigest;
  private Object expiresAt;
  private Object reviewedDetails;

  /**
   * The method value.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getMethod() {
    return method;
  }

  public void setMethod(Object method) {
    this.method = method;
  }

  /**
   * The countryCode value.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getCountryCode() {
    return countryCode;
  }

  public void setCountryCode(Object countryCode) {
    this.countryCode = countryCode;
  }

  /**
   * The providerCode value.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getProviderCode() {
    return providerCode;
  }

  public void setProviderCode(Object providerCode) {
    this.providerCode = providerCode;
  }

  /**
   * The accountNumber value.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getAccountNumber() {
    return accountNumber;
  }

  public void setAccountNumber(Object accountNumber) {
    this.accountNumber = accountNumber;
  }

  /**
   * The holderName value.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getHolderName() {
    return holderName;
  }

  public void setHolderName(Object holderName) {
    this.holderName = holderName;
  }

  /**
   * The branchCode value.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getBranchCode() {
    return branchCode;
  }

  public void setBranchCode(Object branchCode) {
    this.branchCode = branchCode;
  }

  /**
   * The phoneNumber value.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getPhoneNumber() {
    return phoneNumber;
  }

  public void setPhoneNumber(Object phoneNumber) {
    this.phoneNumber = phoneNumber;
  }

  /**
   * The effectiveFrom value.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getEffectiveFrom() {
    return effectiveFrom;
  }

  public void setEffectiveFrom(Object effectiveFrom) {
    this.effectiveFrom = effectiveFrom;
  }

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
   * The explanation value.
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
   * The reviewedDetails value.
   *
   * @return raw value
   */
  @Schema(type = "boolean")
  public Object getReviewedDetails() {
    return reviewedDetails;
  }

  public void setReviewedDetails(Object reviewedDetails) {
    this.reviewedDetails = reviewedDetails;
  }
}
