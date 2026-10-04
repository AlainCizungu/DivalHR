package com.divalhr.core.people.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** Follow-up task status command (MVP-022). Mirrors {@code UpdateSeparationTask}. */
@Schema(name = "UpdateSeparationTask")
public class UpdateSeparationTaskRequest extends StrictRequest {

  private Object status;
  private Object expectedVersion;

  /**
   * OPEN, DONE or NOT_APPLICABLE.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getStatus() {
    return status;
  }

  /**
   * Sets: oPEN, DONE or NOT_APPLICABLE.
   *
   * @param status raw value
   */
  public void setStatus(Object status) {
    this.status = status;
  }

  /**
   * Task version as read.
   *
   * @return raw value
   */
  @Schema(type = "integer")
  public Object getExpectedVersion() {
    return expectedVersion;
  }

  /**
   * Sets: task version as read.
   *
   * @param expectedVersion raw value
   */
  public void setExpectedVersion(Object expectedVersion) {
    this.expectedVersion = expectedVersion;
  }
}
