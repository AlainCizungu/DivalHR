package com.divalhr.core.identity.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request body for {@code createInvitation}. Plain strings so every defect maps to a stable code;
 * the tenant and every server-owned field are unknown properties and rejected.
 */
@Schema(name = "CreateInvitation")
public final class CreateInvitationRequest extends StrictRequest {

  @JsonProperty("email")
  private String email;

  @JsonProperty("role")
  private String role;

  @JsonProperty("locale")
  private String locale;

  /** Creates an empty request (used by Jackson). */
  public CreateInvitationRequest() {}

  /**
   * Creates a request (used by tests).
   *
   * @param email invitee address
   * @param role role
   * @param locale locale
   * @return request
   */
  public static CreateInvitationRequest of(String email, String role, String locale) {
    CreateInvitationRequest request = new CreateInvitationRequest();
    request.email = email;
    request.role = role;
    request.locale = locale;
    return request;
  }

  /**
   * Returns the address as submitted (confidential).
   *
   * @return address
   */
  public String getEmail() {
    return email;
  }

  /**
   * Returns the role as submitted.
   *
   * @return role
   */
  public String getRole() {
    return role;
  }

  /**
   * Returns the locale as submitted.
   *
   * @return locale
   */
  public String getLocale() {
    return locale;
  }

  @Override
  public String toString() {
    return "CreateInvitationRequest[<redacted>]";
  }
}
