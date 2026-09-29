package com.divalhr.core.platform.security;

import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Prevents development-only identity configuration (the {@code divalhr-dev} realm and its published
 * seed users) from being trusted outside development and test environments.
 */
@Component
public final class DevelopmentSeedGuard {

  static final Set<String> ENVIRONMENTS = Set.of("development", "test", "staging", "production");
  static final Set<String> SEED_ALLOWED = Set.of("development", "test");
  static final String DEV_REALM_MARKER = "/realms/divalhr-dev";

  /**
   * Validates configuration at start-up.
   *
   * @param environment deployment environment
   * @param properties security properties
   */
  public DevelopmentSeedGuard(
      @Value("${divalhr.environment}") String environment, SecurityProperties properties) {
    check(environment, properties.issuer());
  }

  static void check(String environment, String issuer) {
    String env = environment == null ? "" : environment.trim().toLowerCase(Locale.ROOT);
    if (!ENVIRONMENTS.contains(env)) {
      throw new IllegalStateException(
          "divalhr.environment must be one of " + ENVIRONMENTS + " but was '" + environment + "'");
    }
    if (!SEED_ALLOWED.contains(env) && issuer.contains(DEV_REALM_MARKER)) {
      throw new IllegalStateException(
          "The development-only realm 'divalhr-dev' must not be trusted in environment " + env);
    }
  }
}
