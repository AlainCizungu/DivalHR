package com.divalhr.core.operations.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The assignee's completion of a task (MVP-080, D80-4). Every value is kept raw so that each defect
 * maps to a stable {@code {field, constraint}} pair; unknown properties are captured and rejected.
 * Tenant and actor are never fields.
 */
@Schema(name = "CompleteTaskRequest")
public class CompleteTaskRequest extends StrictRequest {
  private Object expectedVersion;
  private Object note;

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
   * The raw note.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getNote() {
    return note;
  }

  /**
   * Sets the raw note.
   *
   * @param note raw value
   */
  public void setNote(Object note) {
    this.note = note;
  }
}
