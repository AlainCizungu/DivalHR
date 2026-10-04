package com.divalhr.core.identity.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of {@code createEmployeeAccessLink} (MVP-022): exactly {@code {"membershipId"}}. Raw values
 * are validated by the service, so a wrong JSON type is a field error, never a binding failure.
 */
@Schema(name = "CreateEmployeeAccessLink")
public class CreateEmployeeAccessLinkRequest extends StrictRequest {

  private Object membershipId;

  /**
   * The membership ID as submitted.
   *
   * @return raw value
   */
  @Schema(type = "string", format = "uuid")
  public Object getMembershipId() {
    return membershipId;
  }

  /**
   * Sets the membership ID.
   *
   * @param membershipId raw value
   */
  public void setMembershipId(Object membershipId) {
    this.membershipId = membershipId;
  }
}
