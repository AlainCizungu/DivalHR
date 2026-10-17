package com.divalhr.core.people.schedule.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-071: unlock a locked week after its reviewed preview. Every value is raw: the command
 * validates shape and the service evaluates the lock again under the locks.
 */
@Schema(name = "UnlockTimesheetPeriodRequest")
public class UnlockTimesheetPeriodRequest extends TimesheetPeriodUnlockPreviewRequest {
  private Object lockVersion;

  /**
   * The current lock version the preview showed.
   *
   * @return raw value
   */
  @Schema(type = "integer", minimum = "1")
  public Object getLockVersion() {
    return lockVersion;
  }

  /**
   * Sets the raw lockVersion.
   *
   * @param lockVersion raw value
   */
  public void setLockVersion(Object lockVersion) {
    this.lockVersion = lockVersion;
  }

  private Object lockActionId;

  /**
   * The current LOCKED action the preview showed.
   *
   * @return raw value
   */
  @Schema(type = "string", format = "uuid")
  public Object getLockActionId() {
    return lockActionId;
  }

  /**
   * Sets the raw lockActionId.
   *
   * @param lockActionId raw value
   */
  public void setLockActionId(Object lockActionId) {
    this.lockActionId = lockActionId;
  }

  private Object previewDigest;

  /**
   * The digest of the reviewed unlock preview, exactly as returned (64 lowercase hexadecimal
   * characters).
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

  private Object confirm;

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
