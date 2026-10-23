package com.divalhr.core.integrations.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** MVP-110: rotate the secret, disable or enable a webhook. */
@Schema(name = "WebhookCommandRequest")
public class WebhookCommandRequest extends StrictRequest {

  private Object configVersion;

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
