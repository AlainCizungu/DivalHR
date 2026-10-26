package com.divalhr.core.operations.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The administrator's assignment of a task (MVP-080, D80-2). Every value is kept raw so that each
 * defect maps to a stable {@code {field, constraint}} pair; unknown properties are captured and
 * rejected. Tenant and actor are never fields.
 */
@Schema(name = "CreateTaskRequest")
public class CreateTaskRequest extends StrictRequest {
  private Object assigneeEmployeeId;
  private Object title;
  private Object instructions;
  private Object priority;
  private Object dueAt;

  /**
   * The raw assigneeEmployeeId.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getAssigneeEmployeeId() {
    return assigneeEmployeeId;
  }

  /**
   * Sets the raw assigneeEmployeeId.
   *
   * @param assigneeEmployeeId raw value
   */
  public void setAssigneeEmployeeId(Object assigneeEmployeeId) {
    this.assigneeEmployeeId = assigneeEmployeeId;
  }

  /**
   * The raw title.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getTitle() {
    return title;
  }

  /**
   * Sets the raw title.
   *
   * @param title raw value
   */
  public void setTitle(Object title) {
    this.title = title;
  }

  /**
   * The raw instructions.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getInstructions() {
    return instructions;
  }

  /**
   * Sets the raw instructions.
   *
   * @param instructions raw value
   */
  public void setInstructions(Object instructions) {
    this.instructions = instructions;
  }

  /**
   * The raw priority.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getPriority() {
    return priority;
  }

  /**
   * Sets the raw priority.
   *
   * @param priority raw value
   */
  public void setPriority(Object priority) {
    this.priority = priority;
  }

  /**
   * The raw dueAt.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getDueAt() {
    return dueAt;
  }

  /**
   * Sets the raw dueAt.
   *
   * @param dueAt raw value
   */
  public void setDueAt(Object dueAt) {
    this.dueAt = dueAt;
  }
}
