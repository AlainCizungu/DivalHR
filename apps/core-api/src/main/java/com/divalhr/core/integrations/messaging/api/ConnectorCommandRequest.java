package com.divalhr.core.integrations.messaging.api;

import com.divalhr.core.integrations.api.StrictRequest;
import io.swagger.v3.oas.annotations.media.Schema;

/** MVP-111: verify, enable or disable the connector. */
@Schema(name = "ConnectorCommandRequest")
public class ConnectorCommandRequest extends StrictRequest {

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
