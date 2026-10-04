package com.divalhr.core.documents.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The previewed contract plus what the preview returned (MVP-030). Mirrors {@code IssueContract}.
 * Values stay raw JSON values until the service validates them; they are never logged.
 */
@Schema(name = "IssueContract")
public class IssueContractRequest extends StrictRequest {

  private Object templateVersionId;
  private Object startDate;
  private Object endDate;
  private Object expectedEmploymentVersion;
  private Object previewDigest;

  /**
   * Approved template version.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getTemplateVersionId() {
    return templateVersionId;
  }

  /**
   * Sets: approved template version.
   *
   * @param templateVersionId raw value
   */
  public void setTemplateVersionId(Object templateVersionId) {
    this.templateVersionId = templateVersionId;
  }

  /**
   * Contract start (ISO date).
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getStartDate() {
    return startDate;
  }

  /**
   * Sets: contract start (ISO date).
   *
   * @param startDate raw value
   */
  public void setStartDate(Object startDate) {
    this.startDate = startDate;
  }

  /**
   * Contract end (ISO date), or null.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getEndDate() {
    return endDate;
  }

  /**
   * Sets: contract end (ISO date), or null.
   *
   * @param endDate raw value
   */
  public void setEndDate(Object endDate) {
    this.endDate = endDate;
  }

  /**
   * Employment version from the preview.
   *
   * @return raw value
   */
  @Schema(type = "integer")
  public Object getExpectedEmploymentVersion() {
    return expectedEmploymentVersion;
  }

  /**
   * Sets: employment version from the preview.
   *
   * @param expectedEmploymentVersion raw value
   */
  public void setExpectedEmploymentVersion(Object expectedEmploymentVersion) {
    this.expectedEmploymentVersion = expectedEmploymentVersion;
  }

  /**
   * Digest from the preview.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getPreviewDigest() {
    return previewDigest;
  }

  /**
   * Sets: digest from the preview.
   *
   * @param previewDigest raw value
   */
  public void setPreviewDigest(Object previewDigest) {
    this.previewDigest = previewDigest;
  }
}
