package com.divalhr.core.people.leave.api;

import com.divalhr.core.people.api.StrictRequest;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A tenant administrator's revocation of a leave approval substitute appointment (MVP-041G). The
 * appointment comes from the path; tenant and actor are never fields.
 */
@Schema(name = "RevokeLeaveApprovalSubstitution")
public class RevokeLeaveApprovalSubstitutionRequest extends StrictRequest {
  private Object reasonLocale;
  private Object reason;

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
   * The reason (plain text, decision-reason grammar version 1), required.
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
