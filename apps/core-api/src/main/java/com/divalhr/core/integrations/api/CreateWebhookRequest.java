package com.divalhr.core.integrations.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** MVP-110: create an outbound webhook. */
@Schema(name = "CreateWebhookRequest")
public class CreateWebhookRequest extends StrictRequest {

  private Object name;

  private Object url;

  private Object eventTypes;

  private Object confirm;

  /**
   * Raw value; the service types and validates it.
   *
   * @return raw value
   */
  @Schema(type = "string", minLength = 1, maxLength = 100)
  public Object getName() {
    return name;
  }

  /**
   * Sets the raw value.
   *
   * @param name raw value
   */
  public void setName(Object name) {
    this.name = name;
  }

  /**
   * Raw value; the service types and validates it.
   *
   * @return raw value
   */
  @Schema(type = "string", format = "uri", maxLength = 2048)
  public Object getUrl() {
    return url;
  }

  /**
   * Sets the raw value.
   *
   * @param url raw value
   */
  public void setUrl(Object url) {
    this.url = url;
  }

  /**
   * Raw value; the service types and validates it.
   *
   * @return raw value
   */
  @Schema(type = "array", implementation = String.class)
  public Object getEventTypes() {
    return eventTypes;
  }

  /**
   * Sets the raw value.
   *
   * @param eventTypes raw value
   */
  public void setEventTypes(Object eventTypes) {
    this.eventTypes = eventTypes;
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
