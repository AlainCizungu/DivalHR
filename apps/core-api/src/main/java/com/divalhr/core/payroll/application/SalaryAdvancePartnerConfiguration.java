package com.divalhr.core.payroll.application;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring of the partner secrets and profile; start-up fails on an unsafe configuration (MVP-120B).
 */
@Configuration
public class SalaryAdvancePartnerConfiguration {

  /**
   * Validated partner secrets.
   *
   * @param properties settings
   * @param environment deployment environment
   * @return the secrets
   */
  @Bean
  SalaryAdvancePartnerSecrets salaryAdvancePartnerSecrets(
      SalaryAdvancePartnerProperties properties,
      @Value("${divalhr.environment}") String environment) {
    properties.validate(environment);
    return new SalaryAdvancePartnerSecrets(properties);
  }

  /**
   * The operator-controlled partner profile holder.
   *
   * @param properties settings
   * @return the holder (empty unless the synthetic receiver is enabled)
   */
  @Bean
  SalaryAdvancePartnerProfiles salaryAdvancePartnerProfiles(
      SalaryAdvancePartnerProperties properties) {
    return new SalaryAdvancePartnerProfiles(
        properties.synthetic().active()
            ? Optional.of(
                new SalaryAdvancePartnerProfile(
                    SalaryAdvancePartnerProfile.SYNTHETIC_TEST,
                    properties.synthetic().host(),
                    properties.synthetic().port()))
            : Optional.empty());
  }
}
