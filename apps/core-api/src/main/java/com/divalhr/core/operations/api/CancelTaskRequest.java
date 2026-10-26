package com.divalhr.core.operations.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The administrator's cancellation of an unfinished task (MVP-080, D80-4). Every value is kept raw
 * so that each defect maps to a stable {@code {field, constraint}} pair; unknown properties are
 * captured and rejected. Tenant and actor are never fields.
 */
@Schema(name = "CancelTaskRequest")
public class CancelTaskRequest extends StrictRequest {
  private Object expectedVersion;
  private Object reason;
  private Object explanation;

  /**
   * The raw expectedVersion.
   *
   * @return raw value
   */
  @Schema(type = "integer")
  public Object getExpectedVersion() {
    return expectedVersion;
  }

  /**
   * Sets the raw expectedVersion.
   *
   * @param expectedVersion raw value
   */
  public void setExpectedVersion(Object expectedVersion) {
    this.expectedVersion = expectedVersion;
  }

  /**
   * The raw reason.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getReason() {
    return reason;
  }

  /**
   * Sets the raw reason.
   *
   * @param reason raw value
   */
  public void setReason(Object reason) {
    this.reason = reason;
  }

  /**
   * The raw explanation.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getExplanation() {
    return explanation;
  }

  /**
   * Sets the raw explanation.
   *
   * @param explanation raw value
   */
  public void setExplanation(Object explanation) {
    this.explanation = explanation;
  }
}
