package com.divalhr.core.people.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The previewed separation plus what the preview returned (MVP-022). Mirrors {@code
 * CreateSeparation}.
 */
@Schema(name = "CreateSeparation")
public class CreateSeparationRequest extends SeparationFields {

  private Object expectedVersion;
  private Object previewDigest;
  private Object acknowledgements;

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
   * Acknowledgements the preview required.
   *
   * @return raw value
   */
  @Schema(type = "array")
  public Object getAcknowledgements() {
    return acknowledgements;
  }

  /**
   * Sets: acknowledgements the preview required.
   *
   * @param acknowledgements raw value
   */
  public void setAcknowledgements(Object acknowledgements) {
    this.acknowledgements = acknowledgements;
  }
}
