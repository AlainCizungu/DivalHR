package com.divalhr.core.integrations.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wiring of the webhook capability; start-up fails on an unsafe configuration (D110-4). */
@Configuration
public class IntegrationConfiguration {

  /**
   * The system resolver.
   *
   * @return resolver
   */
  @Bean
  DnsResolver webhookDnsResolver() {
    return DnsResolver.system();
  }

  /**
   * Validated secret handling.
   *
   * @param properties settings
   * @param environment deployment environment
   * @return the secret service
   */
  @Bean
  WebhookSecrets webhookSecrets(
      IntegrationProperties properties, @Value("${divalhr.environment}") String environment) {
    properties.validate(environment);
    return new WebhookSecrets(properties);
  }
}
