package com.divalhr.core.platform.security;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Prevents development-only identity configuration from being trusted outside development and test
 * environments (Issue #27):
 *
 * <ul>
 *   <li>The OIDC issuer must be an absolute {@code http} or {@code https} URI with a host and no
 *       user information, query or fragment in every environment.
 *   <li>Staging and production require the normalized scheme to be exactly {@code https}. The
 *       {@code divalhr-dev} realm accepts plain HTTP ({@code sslRequired: none}) for the local
 *       Compose stack only, so it must never be the identity provider of a shared environment.
 *   <li>The {@code divalhr-dev} realm and its published seed users are refused outside development
 *       and test.
 * </ul>
 *
 * <p>Failures stop start-up. Messages name the rule, never the configured value.
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
    String scheme = issuerScheme(issuer);
    if (!SEED_ALLOWED.contains(env)) {
      if (!"https".equals(scheme)) {
        throw new IllegalStateException(
            "divalhr.security.issuer must use https in environment " + env);
      }
      if (issuer.contains(DEV_REALM_MARKER)) {
        throw new IllegalStateException(
            "The development-only realm 'divalhr-dev' must not be trusted in environment " + env);
      }
    }
  }

  /**
   * Parses the issuer and returns its lower-cased scheme.
   *
   * @throws IllegalStateException when the issuer is not an absolute http(s) URI with a host and
   *     without user information, query or fragment
   */
  private static String issuerScheme(String issuer) {
    if (issuer == null) {
      throw invalidIssuer();
    }
    URI uri;
    try {
      uri = new URI(issuer);
    } catch (URISyntaxException invalid) {
      throw invalidIssuer();
    }
    if (!uri.isAbsolute() || uri.isOpaque()) {
      throw invalidIssuer();
    }
    String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
    if (!"http".equals(scheme) && !"https".equals(scheme)) {
      throw invalidIssuer();
    }
    if (uri.getHost() == null
        || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null) {
      throw invalidIssuer();
    }
    return scheme;
  }

  private static IllegalStateException invalidIssuer() {
    return new IllegalStateException(
        "divalhr.security.issuer must be an absolute http(s) URI with a host and without user"
            + " information, query or fragment");
  }
}
