package com.divalhr.core.integrations.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** MVP-110: send one test delivery. */
@Schema(name = "TestDeliveryRequest")
public class TestDeliveryRequest extends StrictRequest {

  private Object confirm;

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
