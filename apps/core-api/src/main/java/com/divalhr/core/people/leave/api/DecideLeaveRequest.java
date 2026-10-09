package com.divalhr.core.people.leave.api;

import com.divalhr.core.people.api.StrictRequest;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A decision on a pending leave request (MVP-041B), by its manager or by a tenant administrator.
 * Every value is kept raw so that each defect maps to a stable {@code {field, constraint}} pair;
 * unknown properties are captured and rejected. The request comes from the path; tenant, route,
 * manager and actor are never fields.
 */
@Schema(name = "DecideLeaveRequest")
public class DecideLeaveRequest extends StrictRequest {
  private Object decision;
  private Object reasonLocale;
  private Object reason;

  /**
   * {@code APPROVED} or {@code REJECTED}.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getDecision() {
    return decision;
  }

  /**
   * Sets: {@code APPROVED} or {@code REJECTED}.
   *
   * @param decision raw value
   */
  public void setDecision(Object decision) {
    this.decision = decision;
  }

  /**
   * {@code en} or {@code fr}: the language the reason is written in.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getReasonLocale() {
    return reasonLocale;
  }

  /**
   * Sets: the reason's language.
   *
   * @param reasonLocale raw value
   */
  public void setReasonLocale(Object reasonLocale) {
    this.reasonLocale = reasonLocale;
  }

  /**
   * The reason (plain text, 2 to 500 characters), required for either outcome.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getReason() {
    return reason;
  }

  /**
   * Sets: the reason.
   *
   * @param reason raw value
   */
  public void setReason(Object reason) {
    this.reason = reason;
  }
}
