package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-093A: what the cancellation preview returned, confirmed. Every value is read as {@code
 * Object} so that a wrong type is a field error, never a deserialization failure.
 */
@Schema(name = "CancelCompensationChange")
public class CancelCompensationRequest extends StrictRequest {

  private Object expectedVersion;
  private Object employmentDigest;
  private Object cancellationDigest;
  private Object expiresAt;
  private Object confirmed;

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
   * The cancellation digest the preview returned.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getCancellationDigest() {
    return cancellationDigest;
  }

  public void setCancellationDigest(Object cancellationDigest) {
    this.cancellationDigest = cancellationDigest;
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
