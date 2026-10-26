package com.divalhr.core.integrations.messaging.api;

import com.divalhr.core.integrations.api.StrictRequest;
import io.swagger.v3.oas.annotations.media.Schema;

/** MVP-111: disable or enable a recipient. */
@Schema(name = "RecipientCommandRequest")
public class RecipientCommandRequest extends StrictRequest {

  private Object version;

  private Object confirm;

  /**
   * Raw value; the service types and validates it.
   *
   * @return raw value
   */
  @Schema(type = "integer", format = "int32", minimum = "1")
  public Object getVersion() {
    return version;
  }

  /**
   * Sets the raw value.
   *
   * @param version raw value
   */
  public void setVersion(Object version) {
    this.version = version;
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
