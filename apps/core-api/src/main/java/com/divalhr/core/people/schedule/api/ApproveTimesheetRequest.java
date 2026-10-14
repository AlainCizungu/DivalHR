package com.divalhr.core.people.schedule.api;

import com.divalhr.core.people.api.StrictRequest;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-070B/C: the routed manager approves exactly the reviewed submission (no note). Every value is
 * raw: the command validates shape and range; the manager, tenant and actor never come from the
 * request.
 */
@Schema(name = "ApproveTimesheetRequest")
public class ApproveTimesheetRequest extends StrictRequest {
  private Object revision;
  private Object generationVersion;
  private Object cycle;
  private Object submissionActionId;
  private Object confirm;

  /**
   * The reviewed revision (still the current one).
   *
   * @return raw value
   */
  @Schema(type = "integer", minimum = "1")
  public Object getRevision() {
    return revision;
  }

  /**
   * Sets the raw revision.
   *
   * @param revision raw value
   */
  public void setRevision(Object revision) {
    this.revision = revision;
  }

  /**
   * The reviewed generation version (still the current one).
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

  /**
   * The reviewed submission cycle.
   *
   * @return raw value
   */
  @Schema(type = "integer", minimum = "1")
  public Object getCycle() {
    return cycle;
  }

  /**
   * Sets the raw cycle.
   *
   * @param cycle raw value
   */
  public void setCycle(Object cycle) {
    this.cycle = cycle;
  }

  /**
   * The reviewed submission action.
   *
   * @return raw value
   */
  @Schema(type = "string", format = "uuid")
  public Object getSubmissionActionId() {
    return submissionActionId;
  }

  /**
   * Sets the raw submissionActionId.
   *
   * @param submissionActionId raw value
   */
  public void setSubmissionActionId(Object submissionActionId) {
    this.submissionActionId = submissionActionId;
  }

  /**
   * Must be {@code true}: the caller confirmed the reviewed timesheet.
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
