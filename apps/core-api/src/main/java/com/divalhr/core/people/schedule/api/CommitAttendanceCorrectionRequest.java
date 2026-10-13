package com.divalhr.core.people.schedule.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-062: the commit of a previewed REPLACE_TIMES correction: the identical proposal, the
 * previewed target version, the preview digest and the explicit confirmation.
 */
@Schema(name = "CommitAttendanceCorrectionRequest")
public class CommitAttendanceCorrectionRequest extends AttendanceCorrectionRequest {
  private Object targetVersion;
  private Object previewDigest;
  private Object confirm;

  /**
   * The previewed target version: the record's correction count, or 0 for a missing record.
   *
   * @return raw value
   */
  @Schema(type = "integer", minimum = "0")
  public Object getTargetVersion() {
    return targetVersion;
  }

  /**
   * Sets the raw targetVersion.
   *
   * @param targetVersion raw value
   */
  public void setTargetVersion(Object targetVersion) {
    this.targetVersion = targetVersion;
  }

  /**
   * The {@code previewDigest} of the reviewed preview.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getPreviewDigest() {
    return previewDigest;
  }

  /**
   * Sets the raw previewDigest.
   *
   * @param previewDigest raw value
   */
  public void setPreviewDigest(Object previewDigest) {
    this.previewDigest = previewDigest;
  }

  /**
   * Must be {@code true}: the administrator confirmed the reviewed preview.
   *
   * @return raw value
   */
  @Schema(type = "boolean")
  public Object getConfirm() {
    return confirm;
  }

  /**
   * Sets the raw confirm.
   *
   * @param confirm raw value
   */
  public void setConfirm(Object confirm) {
    this.confirm = confirm;
  }
}
