package com.divalhr.core.people.schedule.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-070A: refresh a generated week's drafts as new revisions. Every value is raw: the command
 * validates shape and range and never trusts a tenant, employee or boundary from the request.
 */
@Schema(name = "RefreshTimesheetsRequest")
public class RefreshTimesheetsRequest extends TimesheetPeriodRequest {
  private Object version;
  private Object previewDigest;
  private Object confirm;

  /**
   * The previewed generation version.
   *
   * @return raw value
   */
  @Schema(type = "integer", minimum = "1")
  public Object getVersion() {
    return version;
  }

  /**
   * Sets the raw version.
   *
   * @param version raw value
   */
  public void setVersion(Object version) {
    this.version = version;
  }

  /**
   * The {@code previewDigest} of the reviewed refresh preview.
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
