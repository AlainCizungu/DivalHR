package com.divalhr.core.identity.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request body for {@code lookupAccessReviewEntry} (MVP-012B): exactly {@code {"email"}}. Any other
 * property is rejected. The address is confidential and never echoed or logged.
 */
@Schema(name = "AccessReviewLookup")
public final class AccessReviewLookupRequest extends StrictRequest {

  @JsonProperty("email")
  private String email;

  /** Creates an empty request (used by Jackson). */
  public AccessReviewLookupRequest() {}

  /**
   * Creates a request (used by tests).
   *
   * @param email address
   * @return request
   */
  public static AccessReviewLookupRequest of(String email) {
    AccessReviewLookupRequest request = new AccessReviewLookupRequest();
    request.email = email;
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

  @Override
  public String toString() {
    return "AccessReviewLookupRequest[email=<redacted>]";
  }
}
