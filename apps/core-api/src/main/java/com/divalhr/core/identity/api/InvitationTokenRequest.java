package com.divalhr.core.identity.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/** Body of the anonymous inspect and accept operations. The token never appears in a URL. */
@Schema(name = "InvitationTokenRequest")
public final class InvitationTokenRequest extends StrictRequest {

  @JsonProperty("token")
  private String token;

  /** Creates an empty request (used by Jackson). */
  public InvitationTokenRequest() {}

  /**
   * Creates a request (used by tests).
   *
   * @param token token
   * @return request
   */
  public static InvitationTokenRequest of(String token) {
    InvitationTokenRequest request = new InvitationTokenRequest();
    request.token = token;
    return request;
  }

  /**
   * Returns the token as submitted (secret).
   *
   * @return token
   */
  public String getToken() {
    return token;
  }

  @Override
  public String toString() {
    return "InvitationTokenRequest[<redacted>]";
  }
}
