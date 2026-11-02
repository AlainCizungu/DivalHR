package com.divalhr.core.payroll.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wiring of the payment destination secrets; start-up fails on an unsafe key (D95A-3). */
@Configuration
public class PaymentConfiguration {

  /**
   * Validated payment secrets.
   *
   * @param properties settings
   * @param environment deployment environment
   * @return the secrets
   */
  @Bean
  PaymentSecrets paymentSecrets(
      PaymentProperties properties, @Value("${divalhr.environment}") String environment) {
    properties.validate(environment);
    return new PaymentSecrets(properties);
  }
}
