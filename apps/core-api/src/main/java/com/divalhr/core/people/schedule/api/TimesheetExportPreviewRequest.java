package com.divalhr.core.people.schedule.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-070E: one legal entity, one of its sites and one generated site-local week. Every value is
 * raw: the command validates shape and range and never trusts a tenant, employee, boundary or
 * eligibility from the request.
 */
@Schema(name = "TimesheetExportPreviewRequest")
public class TimesheetExportPreviewRequest extends TimesheetPeriodRequest {
  private Object legalEntityId;

  /**
   * The legal entity (any malformed, unknown or foreign value, or one that does not own the site,
   * is the same safe {@code 404}).
   *
   * @return raw value
   */
  @Schema(type = "string", format = "uuid")
  public Object getLegalEntityId() {
    return legalEntityId;
  }

  /**
   * Sets the raw legalEntityId.
   *
   * @param legalEntityId raw value
   */
  public void setLegalEntityId(Object legalEntityId) {
    this.legalEntityId = legalEntityId;
  }
}
