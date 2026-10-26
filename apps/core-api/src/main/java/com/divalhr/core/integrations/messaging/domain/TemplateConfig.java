package com.divalhr.core.integrations.messaging.domain;

import java.time.Instant;

/**
 * The Meta template an administrator configured for one notification type and language.
 *
 * @param type notification type
 * @param language template language
 * @param name Meta template name (configuration, never message content)
 * @param bodyParameters 0 or 1 body variable, known once the provider verified the template
 * @param verifiedAt when the provider confirmed it, or null
 */
public record TemplateConfig(
    NotificationType type,
    MessagingLanguage language,
    String name,
    int bodyParameters,
    Instant verifiedAt) {

  /**
   * @return a representation without the template name
   */
  @Override
  public String toString() {
    return "TemplateConfig[" + type + ", " + language + "]";
  }
}
