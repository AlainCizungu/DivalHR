package com.divalhr.core.payroll.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wiring of the submission secrets and profiles; start-up fails on an unsafe configuration. */
@Configuration
public class PaymentSubmissionConfiguration {

  /**
   * Validated submission secrets.
   *
   * @param properties settings
   * @param environment deployment environment
   * @return the secrets
   */
  @Bean
  PaymentSubmissionSecrets paymentSubmissionSecrets(
      PaymentSubmissionProperties properties, @Value("${divalhr.environment}") String environment) {
    properties.validate(environment);
    return new PaymentSubmissionSecrets(properties);
  }

  /**
   * The operator-controlled profiles.
   *
   * @param properties settings
   * @return the profiles
   */
  @Bean
  PaymentSubmissionProfiles paymentSubmissionProfiles(PaymentSubmissionProperties properties) {
    return new PaymentSubmissionProfiles(properties);
  }
}
