package com.divalhr.core.integrations.messaging.api;

import com.divalhr.core.integrations.api.StrictRequest;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-111: connect the tenant's one WhatsApp Business Cloud API connector (as a draft). The access
 * token is accepted here and in the rotation request only, and is never returned, stored in the
 * idempotency store, audited or logged.
 */
@Schema(name = "CreateConnectorRequest")
public class CreateConnectorRequest extends StrictRequest {

  private Object wabaId;

  private Object phoneNumberId;

  private Object displayNumber;

  private Object accessToken;

  private Object templates;

  private Object confirm;

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
