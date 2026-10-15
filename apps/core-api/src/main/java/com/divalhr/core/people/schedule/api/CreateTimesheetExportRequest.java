package com.divalhr.core.people.schedule.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-070E: create the export of a reviewed preview. Every value is raw: the command validates
 * shape and the service evaluates eligibility again under the locks.
 */
@Schema(name = "CreateTimesheetExportRequest")
public class CreateTimesheetExportRequest extends TimesheetExportPreviewRequest {
  private Object previewDigest;
  private Object confirm;

  /**
   * The digest of the reviewed preview, exactly as returned (64 lowercase hexadecimal characters).
   *
   * @return raw value
   */
  @Schema(type = "string", pattern = "^[0-9a-f]{64}$")
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
