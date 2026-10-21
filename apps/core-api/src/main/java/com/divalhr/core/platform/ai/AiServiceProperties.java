package com.divalhr.core.platform.ai;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The internal AI service and the Keycloak client that authenticates Core to it ({@code
 * divalhr.ai-service}, MVP-101, D101-5). Nothing here has a secret default: when no value is set
 * the capability is simply unavailable (the endpoints answer {@code 503
 * OPERATIONAL_SUMMARY_UNAVAILABLE} and no request leaves Core). When any value is set, all are
 * required and validated: HTTPS outside development and test, a client secret of at least 16
 * characters, and no {@code dev-only-} secret outside development.
 *
 * @param baseUrl AI service origin reachable from Core, e.g. {@code https://ai.internal}
 * @param tokenUrl the Keycloak realm token endpoint
 * @param clientId the dedicated Core AI client (client-credentials only)
 * @param clientSecret the client secret
 * @param connectTimeout connect timeout
 * @param readTimeout the whole-request deadline of one AI call (the AI service's model deadline is
 *     4 seconds; this leaves room for rendering and the network)
 */
@ConfigurationProperties("divalhr.ai-service")
public record AiServiceProperties(
    String baseUrl,
    String tokenUrl,
    String clientId,
    String clientSecret,
    Duration connectTimeout,
    Duration readTimeout) {

  /** Applies defaults. */
  public AiServiceProperties {
    clientId = clientId == null || clientId.isBlank() ? "divalhr-core-ai" : clientId;
    connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
    readTimeout = readTimeout == null ? Duration.ofSeconds(7) : readTimeout;
  }

  /**
   * Whether any value is set.
   *
   * @return true when the capability is meant to be on
   */
  public boolean isConfigured() {
    return notBlank(baseUrl) || notBlank(tokenUrl) || notBlank(clientSecret);
  }

  /**
   * Validates a configured capability for an environment; messages never contain the secret.
   *
   * @param environment deployment environment
   */
  public void validate(String environment) {
    String env = environment == null ? "" : environment.trim().toLowerCase(Locale.ROOT);
    boolean relaxed = "development".equals(env) || "test".equals(env);
    require(notBlank(baseUrl), "base-url is required");
    require(notBlank(tokenUrl), "token-url is required");
    require(clientSecret != null && clientSecret.length() >= 16, "client-secret is required");
    require(secure(baseUrl, relaxed), "base-url must be https outside development and test");
    require(secure(tokenUrl, relaxed), "token-url must be https outside development and test");
    require(
        "development".equals(env) || !clientSecret.toLowerCase(Locale.ROOT).startsWith("dev-only-"),
        "the development client secret must not be used in environment " + env);
    require(
        connectTimeout.compareTo(Duration.ZERO) > 0 && readTimeout.compareTo(Duration.ZERO) > 0,
        "timeouts must be positive");
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static boolean secure(String value, boolean relaxed) {
    try {
      URI uri = URI.create(value);
      return uri.getHost() != null
          && ("https".equals(uri.getScheme()) || (relaxed && "http".equals(uri.getScheme())));
    } catch (IllegalArgumentException malformed) {
      return false;
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException("divalhr.ai-service." + message);
    }
  }

  @Override
  public String toString() {
    return "AiServiceProperties[clientId=" + clientId + ", clientSecret=<redacted>]";
  }
}
