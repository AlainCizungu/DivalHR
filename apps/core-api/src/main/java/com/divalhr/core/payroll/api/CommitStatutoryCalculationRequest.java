package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-094B: the evidence a statutory calculation preview returned. Every value is read as {@code
 * Object} so that a wrong type is a field error, never a deserialization failure.
 */
@Schema(name = "CommitStatutoryCalculation")
public class CommitStatutoryCalculationRequest extends StrictRequest {

  private Object expectedVersion;
  private Object sourceRunDigest;
  private Object profileDigest;
  private Object rulePackDigest;
  private Object calculationDigest;
  private Object expiresAt;
  private Object confirmed;

  /**
   * The chain version the preview showed.
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
   * The source run digest the preview returned.
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
   * The rule pack digest the preview returned.
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
