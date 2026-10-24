package com.divalhr.core.integrations.messaging.api;

import com.divalhr.core.integrations.api.StrictRequest;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

/** MVP-111: change the Meta identifiers and template names of a connector that is not active. */
@Schema(name = "UpdateConnectorRequest")
public class UpdateConnectorRequest extends StrictRequest {

  private Object configVersion;

  private Object wabaId;

  private Object phoneNumberId;

  private Object displayNumber;

  private Object templates;

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
  @Schema(type = "string", pattern = "^[0-9]{5,20}$")
  public Object getWabaId() {
    return wabaId;
  }

  /**
   * Sets the raw value.
   *
   * @param wabaId raw value
   */
  public void setWabaId(Object wabaId) {
    this.wabaId = wabaId;
  }

  /**
   * Raw value; the service types and validates it.
   *
   * @return raw value
   */
  @Schema(type = "string", pattern = "^[0-9]{5,20}$")
  public Object getPhoneNumberId() {
    return phoneNumberId;
  }

  /**
   * Sets the raw value.
   *
   * @param phoneNumberId raw value
   */
  public void setPhoneNumberId(Object phoneNumberId) {
    this.phoneNumberId = phoneNumberId;
  }

  /**
   * Raw value; the service types and validates it.
   *
   * @return raw value
   */
  @Schema(type = "string", pattern = "^\\+[1-9][0-9]{6,14}$")
  public Object getDisplayNumber() {
    return displayNumber;
  }

  /**
   * Sets the raw value.
   *
   * @param displayNumber raw value
   */
  public void setDisplayNumber(Object displayNumber) {
    this.displayNumber = displayNumber;
  }

  /**
   * Raw value; the service types and validates it.
   *
   * @return raw value
   */
  @ArraySchema(schema = @Schema(implementation = TemplateRequest.class), maxItems = 12)
  public Object getTemplates() {
    return templates;
  }

  /**
   * Sets the raw value.
   *
   * @param templates raw value
   */
  public void setTemplates(Object templates) {
    this.templates = templates;
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
