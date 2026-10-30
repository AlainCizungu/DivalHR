package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-094B: the previewed statutory profile entry plus what the preview returned. Every value is
 * read as {@code Object} so that a wrong type is a field error, never a deserialization failure.
 */
@Schema(name = "CommitStatutoryProfile")
public class CommitStatutoryProfileRequest extends StrictRequest {

  private Object effectiveFrom;
  private Object classification;
  private Object cnssSubject;
  private Object irppSubject;
  private Object evidenceCode;
  private Object expectedVersion;
  private Object previewDigest;
  private Object expiresAt;
  private Object confirmed;

  /**
   * The first day the entry is in force.
   *
   * @return raw value
   */
  @Schema(type = "string", format = "date")
  public Object getEffectiveFrom() {
    return effectiveFrom;
  }

  public void setEffectiveFrom(Object effectiveFrom) {
    this.effectiveFrom = effectiveFrom;
  }

  /**
   * STANDARD_RESIDENT or UNSUPPORTED.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getClassification() {
    return classification;
  }

  public void setClassification(Object classification) {
    this.classification = classification;
  }

  /**
   * Whether the employee is subject to CNSS.
   *
   * @return raw value
   */
  @Schema(type = "boolean")
  public Object getCnssSubject() {
    return cnssSubject;
  }

  public void setCnssSubject(Object cnssSubject) {
    this.cnssSubject = cnssSubject;
  }

  /**
   * Whether the employee is subject to the IRPP salary withholding.
   *
   * @return raw value
   */
  @Schema(type = "boolean")
  public Object getIrppSubject() {
    return irppSubject;
  }

  public void setIrppSubject(Object irppSubject) {
    this.irppSubject = irppSubject;
  }

  /**
   * The evidence code, from a closed list.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getEvidenceCode() {
    return evidenceCode;
  }

  public void setEvidenceCode(Object evidenceCode) {
    this.evidenceCode = evidenceCode;
  }

  /**
   * The number of entries the preview showed.
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
