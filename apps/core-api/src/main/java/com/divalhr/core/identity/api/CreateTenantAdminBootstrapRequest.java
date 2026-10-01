package com.divalhr.core.identity.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request body for {@code createTenantAdminBootstrap} (MVP-014). Plain strings so every defect maps
 * to a stable code; the role is fixed by the operation, so {@code role}, the tenant and every
 * server-owned field are unknown properties and rejected.
 */
@Schema(name = "CreateTenantAdminBootstrap")
public final class CreateTenantAdminBootstrapRequest extends StrictRequest {

  @JsonProperty("email")
  private String email;

  @JsonProperty("locale")
  private String locale;

  /** Creates an empty request (used by Jackson). */
  public CreateTenantAdminBootstrapRequest() {}

  /**
   * Creates a request (used by tests).
   *
   * @param email invitee address
   * @param locale locale
   * @return request
   */
  public static CreateTenantAdminBootstrapRequest of(String email, String locale) {
    CreateTenantAdminBootstrapRequest request = new CreateTenantAdminBootstrapRequest();
    request.email = email;
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
   * Returns the locale as submitted.
   *
   * @return locale
   */
  public String getLocale() {
    return locale;
  }

  @Override
  public String toString() {
    return "CreateTenantAdminBootstrapRequest[<redacted>]";
  }
}
