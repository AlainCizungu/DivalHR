package com.divalhr.core.people.schedule.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Replacement of a schedule by a previewed proposal (MVP-050B, D50B-6): the same slots and reason,
 * the preview's digest and an explicit confirmation.
 */
public class ReplaceWorkScheduleRequest extends WorkScheduleReplacementRequest {
  private Object previewDigest;
  private Object confirm;

  /**
   * The {@code previewDigest} of the replacement preview being committed.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getPreviewDigest() {
    return previewDigest;
  }

  /**
   * Sets: the preview digest.
   *
   * @param previewDigest raw value
   */
  public void setPreviewDigest(Object previewDigest) {
    this.previewDigest = previewDigest;
  }

  /**
   * Must be {@code true}: the administrator confirmed the reviewed replacement.
   *
   * @return raw value
   */
  @Schema(type = "boolean")
  public Object getConfirm() {
    return confirm;
  }

  /**
   * Sets: the confirmation.
   *
   * @param confirm raw value
   */
  public void setConfirm(Object confirm) {
    this.confirm = confirm;
  }
}
