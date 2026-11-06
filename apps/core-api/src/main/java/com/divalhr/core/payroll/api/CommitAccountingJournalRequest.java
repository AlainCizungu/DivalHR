package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-096: the evidence a journal preview returned and the confirmation. Every value is read as
 * {@code Object} so that a wrong type is a field error, never a deserialization failure. Restricted
 * financial data: nothing here is ever logged.
 */
@Schema(name = "CommitAccountingJournal")
public class CommitAccountingJournalRequest extends StrictRequest {

  private Object expectedPackageVersion;

  private Object expectedMappingVersion;

  private Object previewDigest;

  private Object expiresAt;

  private Object understandExportOnly;

  /**
   * The expectedPackageVersion value.
   *
   * @return raw value
   */
  @Schema(type = "integer")
  public Object getExpectedPackageVersion() {
    return expectedPackageVersion;
  }

  public void setExpectedPackageVersion(Object expectedPackageVersion) {
    this.expectedPackageVersion = expectedPackageVersion;
  }

  /**
   * The expectedMappingVersion value.
   *
   * @return raw value
   */
  @Schema(type = "integer")
  public Object getExpectedMappingVersion() {
    return expectedMappingVersion;
  }

  public void setExpectedMappingVersion(Object expectedMappingVersion) {
    this.expectedMappingVersion = expectedMappingVersion;
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
   * The understandExportOnly value.
   *
   * @return raw value
   */
  @Schema(type = "boolean")
  public Object getUnderstandExportOnly() {
    return understandExportOnly;
  }

  public void setUnderstandExportOnly(Object understandExportOnly) {
    this.understandExportOnly = understandExportOnly;
  }
}
