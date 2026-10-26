package com.divalhr.core.integrations.messaging.api;

import com.divalhr.core.integrations.api.StrictRequest;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

/** MVP-111: register an opted-in operational recipient. */
@Schema(name = "CreateRecipientRequest")
public class CreateRecipientRequest extends StrictRequest {

  private Object label;

  private Object phoneNumber;

  private Object language;

  private Object notificationTypes;

  private Object consent;

  private Object confirm;

  /**
   * Raw value; the service types and validates it.
   *
   * @return raw value
   */
  @Schema(type = "string", minLength = 1, maxLength = 80)
  public Object getLabel() {
    return label;
  }

  /**
   * Sets the raw value.
   *
   * @param label raw value
   */
  public void setLabel(Object label) {
    this.label = label;
  }

  /**
   * Raw value; the service types and validates it.
   *
   * @return raw value
   */
  @Schema(
      type = "string",
      pattern = "^\\+[1-9][0-9]{6,14}$",
      accessMode = Schema.AccessMode.WRITE_ONLY)
  public Object getPhoneNumber() {
    return phoneNumber;
  }

  /**
   * Sets the raw value.
   *
   * @param phoneNumber raw value
   */
  public void setPhoneNumber(Object phoneNumber) {
    this.phoneNumber = phoneNumber;
  }

  /**
   * Raw value; the service types and validates it.
   *
   * @return raw value
   */
  @Schema(
      type = "string",
      allowableValues = {"en", "fr"})
  public Object getLanguage() {
    return language;
  }

  /**
   * Sets the raw value.
   *
   * @param language raw value
   */
  public void setLanguage(Object language) {
    this.language = language;
  }

  /**
   * Raw value; the service types and validates it.
   *
   * @return raw value
   */
  @ArraySchema(schema = @Schema(implementation = String.class), minItems = 1, maxItems = 5)
  public Object getNotificationTypes() {
    return notificationTypes;
  }

  /**
   * Sets the raw value.
   *
   * @param notificationTypes raw value
   */
  public void setNotificationTypes(Object notificationTypes) {
    this.notificationTypes = notificationTypes;
  }

  /**
   * Raw value; the service types and validates it.
   *
   * @return raw value
   */
  @Schema(implementation = ConsentRequest.class)
  public Object getConsent() {
    return consent;
  }

  /**
   * Sets the raw value.
   *
   * @param consent raw value
   */
  public void setConsent(Object consent) {
    this.consent = consent;
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
