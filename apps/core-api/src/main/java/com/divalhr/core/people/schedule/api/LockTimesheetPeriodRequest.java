package com.divalhr.core.people.schedule.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-071: lock a reviewed week. Every value is raw: the command validates shape and the service
 * evaluates readiness again under the locks.
 */
@Schema(name = "LockTimesheetPeriodRequest")
public class LockTimesheetPeriodRequest extends TimesheetPeriodScopeRequest {
  private Object generationVersion;

  /**
   * The generation version the preview showed.
   *
   * @return raw value
   */
  @Schema(type = "integer", minimum = "1")
  public Object getGenerationVersion() {
    return generationVersion;
  }

  /**
   * Sets the raw generationVersion.
   *
   * @param generationVersion raw value
   */
  public void setGenerationVersion(Object generationVersion) {
    this.generationVersion = generationVersion;
  }

  private Object lockVersion;

  /**
   * The lock version the preview showed (0 before the first lock).
   *
   * @return raw value
   */
  @Schema(type = "integer", minimum = "0")
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

  private Object previewDigest;

  /**
   * The digest of the reviewed lock preview, exactly as returned (64 lowercase hexadecimal
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
