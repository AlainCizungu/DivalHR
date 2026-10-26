package com.divalhr.core.integrations.messaging.api;

import com.divalhr.core.integrations.api.StrictRequest;
import io.swagger.v3.oas.annotations.media.Schema;

/** MVP-111: one template name for a notification type and language. */
@Schema(name = "TemplateRequest")
public class TemplateRequest extends StrictRequest {

  private Object notificationType;

  private Object language;

  private Object templateName;

  /**
   * Raw value; the service types and validates it.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getNotificationType() {
    return notificationType;
  }

  /**
   * Sets the raw value.
   *
   * @param notificationType raw value
   */
  public void setNotificationType(Object notificationType) {
    this.notificationType = notificationType;
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
  @Schema(type = "string", pattern = "^[a-z][a-z0-9_]{0,511}$")
  public Object getTemplateName() {
    return templateName;
  }

  /**
   * Sets the raw value.
   *
   * @param templateName raw value
   */
  public void setTemplateName(Object templateName) {
    this.templateName = templateName;
  }
}
