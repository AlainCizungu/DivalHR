package com.divalhr.core.integrations.messaging.api;

import com.divalhr.core.integrations.api.StrictRequest;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-111: replace the connector's access token. The new token is verified with the provider first;
 * a failed verification leaves the current token unchanged.
 */
@Schema(name = "RotateTokenRequest")
public class RotateTokenRequest extends StrictRequest {

  private Object configVersion;

  private Object accessToken;

  private Object confirm;

  /**
   * Raw value; the service types and validates it.
   *
   * @return raw value
   */
  @Schema(type = "integer", format = "int32", minimum = "1")
  public Object getConfigVersion() {
    return configVersion;
  }

  /**
   * Sets the raw value.
   *
   * @param configVersion raw value
   */
  public void setConfigVersion(Object configVersion) {
    this.configVersion = configVersion;
  }

  /**
   * Raw value; the service types and validates it.
   *
   * @return raw value
   */
  @Schema(
      type = "string",
      minLength = 20,
      maxLength = 1024,
      accessMode = Schema.AccessMode.WRITE_ONLY)
  public Object getAccessToken() {
    return accessToken;
  }

  /**
   * Sets the raw value.
   *
   * @param accessToken raw value
   */
  public void setAccessToken(Object accessToken) {
    this.accessToken = accessToken;
  }

  /**
   * Raw value; the service types and validates it.
   *
   * @return raw value
   */
  @Schema(type = "boolean")
  public Object getConfirm() {
    return confirm;
  }

  /**
   * Sets the raw value.
   *
   * @param confirm raw value
   */
  public void setConfirm(Object confirm) {
    this.confirm = confirm;
  }
}
