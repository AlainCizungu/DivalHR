package com.divalhr.core.integrations.messaging.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wiring of the WhatsApp capability; start-up fails on an unsafe configuration (D111-8). */
@Configuration
public class MessagingConfiguration {

  /**
   * Validated secret handling.
   *
   * @param properties settings
   * @param environment deployment environment
   * @return the secret service
   */
  @Bean
  MessagingSecrets messagingSecrets(
      MessagingProperties properties, @Value("${divalhr.environment}") String environment) {
    properties.validate(environment);
    return new MessagingSecrets(properties);
  }
}
