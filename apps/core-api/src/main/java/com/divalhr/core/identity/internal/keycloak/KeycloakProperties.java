package com.divalhr.core.identity.internal.keycloak;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Identity-provider provisioning settings ({@code divalhr.identity-provider}). The client secret
 * comes only from the environment or a secret store; outside development a {@code dev-only-} value
 * is refused. Outside development and test, the admin URL must use HTTPS.
 *
 * @param adminBaseUrl Keycloak base URL reachable from the Core API, e.g. {@code
 *     https://id.example.com}
 * @param realm realm name
 * @param clientId provisioner client (client-credentials only)
 * @param clientSecret provisioner client secret
 * @param webClientId public web client used for the "choose your password" link
 * @param webRedirectUri where the "choose your password" link returns to
 * @param actionLifespan lifetime of that link
 * @param connectTimeout connect timeout
 * @param readTimeout read timeout
 */
@ConfigurationProperties("divalhr.identity-provider")
public record KeycloakProperties(
    String adminBaseUrl,
    String realm,
    String clientId,
    String clientSecret,
    String webClientId,
    String webRedirectUri,
    Duration actionLifespan,
    Duration connectTimeout,
    Duration readTimeout) {

  /** Applies defaults. */
  public KeycloakProperties {
    clientId = clientId == null ? "divalhr-core-provisioner" : clientId;
    webClientId = webClientId == null ? "divalhr-web" : webClientId;
    actionLifespan = actionLifespan == null ? Duration.ofHours(24) : actionLifespan;
    connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
    readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
  }

  /**
   * Validates the settings for an environment; messages never contain the secret.
   *
   * @param environment deployment environment
   */
  public void validate(String environment) {
    String env = environment == null ? "" : environment.trim().toLowerCase(Locale.ROOT);
    require(adminBaseUrl != null && !adminBaseUrl.isBlank(), "admin-base-url is required");
    require(realm != null && realm.matches("^[A-Za-z0-9._-]{1,64}$"), "realm is required");
    require(clientSecret != null && clientSecret.length() >= 16, "client-secret is required");
    require(webRedirectUri != null && !webRedirectUri.isBlank(), "web-redirect-uri is required");
    URI admin = URI.create(adminBaseUrl);
    require(
        admin.getHost() != null
            && ("https".equals(admin.getScheme())
                || ("http".equals(admin.getScheme())
                    && ("development".equals(env) || "test".equals(env)))),
        "admin-base-url must be https outside development and test");
    require(
        "development".equals(env) || !clientSecret.toLowerCase(Locale.ROOT).startsWith("dev-only-"),
        "the development provisioner secret must not be used in environment " + env);
    require(
        !actionLifespan.isNegative() && actionLifespan.compareTo(Duration.ofDays(7)) <= 0,
        "action-lifespan must be at most 7 days");
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException("divalhr.identity-provider." + message);
    }
  }

  @Override
  public String toString() {
    return "KeycloakProperties[realm="
        + realm
        + ", clientId="
        + clientId
        + ", clientSecret=<redacted>]";
  }
}
