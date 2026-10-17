package com.divalhr.core.people.schedule.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-071: one legal entity, one of its sites and one generated site-local week (the lock preview's
 * body). Every value is raw: the command validates shape and the service evaluates readiness again
 * under the locks; tenant, actor, scope and lock state never come from the request.
 */
@Schema(name = "TimesheetPeriodScopeRequest")
public class TimesheetPeriodScopeRequest extends TimesheetPeriodRequest {
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
