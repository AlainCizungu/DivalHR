package com.divalhr.core.integrations.messaging.api;

import com.divalhr.core.integrations.api.StrictRequest;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-111: change a recipient's label, language, notifications or consent record. The number cannot
 * change.
 */
@Schema(name = "UpdateRecipientRequest")
public class UpdateRecipientRequest extends StrictRequest {

  private Object version;

  private Object label;

  private Object language;

  private Object notificationTypes;

  private Object consent;

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
