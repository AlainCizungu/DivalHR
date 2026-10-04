package com.divalhr.core.people.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Change command to record (MVP-021): the previewed command plus what the preview returned. Mirrors
 * {@code CreateEmploymentChange}.
 */
@Schema(name = "CreateEmploymentChange")
public class CreateEmploymentChangeRequest extends EmploymentChangeFields {

  private Object expectedVersion;
  private Object previewDigest;
  private Object acknowledgeRetroactive;

  /**
   * Employment version from the preview.
   *
   * @return raw value
   */
  @Schema(type = "integer")
  public Object getExpectedVersion() {
    return expectedVersion;
  }

  /**
   * Sets: employment version from the preview.
   *
   * @param expectedVersion raw value
   */
  public void setExpectedVersion(Object expectedVersion) {
    this.expectedVersion = expectedVersion;
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

  /**
   * Whether a retroactive change is acknowledged.
   *
   * @return raw value
   */
  @Schema(type = "boolean")
  public Object getAcknowledgeRetroactive() {
    return acknowledgeRetroactive;
  }

  /**
   * Sets: whether a retroactive change is acknowledged.
   *
   * @param acknowledgeRetroactive raw value
   */
  public void setAcknowledgeRetroactive(Object acknowledgeRetroactive) {
    this.acknowledgeRetroactive = acknowledgeRetroactive;
  }
}
