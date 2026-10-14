package com.divalhr.core.people.schedule.api;

import com.divalhr.core.people.api.StrictRequest;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-070B/C: the employee submits the reviewed revision of one of their own drafts. Every value is
 * raw: the command validates shape and range; the employee, tenant, actor and approval route never
 * come from the request.
 */
@Schema(name = "SubmitTimesheetRequest")
public class SubmitTimesheetRequest extends StrictRequest {
  private Object revision;
  private Object generationVersion;
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
