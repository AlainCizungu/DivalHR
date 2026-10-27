package com.divalhr.core.operations.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The assignee's start of a task (MVP-080, D80-4). Every value is kept raw so that each defect maps
 * to a stable {@code {field, constraint}} pair; unknown properties are captured and rejected.
 * Tenant and actor are never fields.
 */
@Schema(name = "StartTaskRequest")
public class StartTaskRequest extends StrictRequest {
  private Object expectedVersion;

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
}
