package com.divalhr.core.integrations.messaging.application;

import com.divalhr.core.integrations.messaging.domain.DeepLink;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the WhatsApp connector ({@code divalhr.messaging}, MVP-111, ADR 0029). Every secret
 * has no default and is read from the protected secret directory: when the encryption key is blank
 * the capability is unavailable (creating, rotating or testing answers {@code 503
 * MESSAGING_SECRET_UNAVAILABLE}, the worker idles); when the app secret or the verify token is
 * blank the provider callback endpoint refuses everything. The Meta base URL is operator
 * configuration, never tenant input: in production it is exactly {@code
 * https://graph.facebook.com}; the synthetic provider is accepted only in development and test, at
 * one exact host and port.
 *
 * @param encryptionKey the dedicated messaging encryption key text
 * @param appSecret the Meta app secret verifying callback signatures
 * @param verifyToken the token Meta echoes during callback verification
 * @param graphBaseUrl the Graph API origin
 * @param graphVersion the pinned Graph API version such as {@code v22.0}
 * @param publicBaseUrl the web origin used for deep links
 * @param jitter whether retry delays are jittered (production true)
 * @param connectTimeout TCP connect deadline
 * @param requestTimeout whole-request deadline
 * @param testProvider optional development/test-only synthetic provider
 * @param callbackMaxBytes largest accepted callback body
 * @param callbackRequestsPerMinute provider-specific callback rate ceiling
 */
@ConfigurationProperties("divalhr.messaging")
public record MessagingProperties(
    String encryptionKey,
    String appSecret,
    String verifyToken,
    String graphBaseUrl,
    String graphVersion,
    String publicBaseUrl,
    Boolean jitter,
    Duration connectTimeout,
    Duration requestTimeout,
    TestProvider testProvider,
    Integer callbackMaxBytes,
    Integer callbackRequestsPerMinute) {

  /** The official Graph API origin, the only production value. */
  public static final String META_GRAPH = "https://graph.facebook.com";

  private static final Pattern VERSION = Pattern.compile("^v[0-9]{1,2}\\.[0-9]{1,2}$");

  /**
   * The synthetic Meta provider (development and test only).
   *
   * @param host exact host name
   * @param port exact port
   * @param caFile optional PEM file with the provider's throwaway CA
   */
  public record TestProvider(String host, Integer port, String caFile) {
    /**
     * @return whether a synthetic provider is configured
     */
    public boolean configured() {
      return host != null && !host.isBlank();
    }
  }

  /** Applies defaults. */
  public MessagingProperties {
    graphBaseUrl =
        graphBaseUrl == null || graphBaseUrl.isBlank() ? META_GRAPH : graphBaseUrl.strip();
    graphVersion = graphVersion == null || graphVersion.isBlank() ? "v22.0" : graphVersion.strip();
    publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.strip();
    jitter = jitter == null || jitter;
    connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
    requestTimeout = requestTimeout == null ? Duration.ofSeconds(10) : requestTimeout;
    testProvider = testProvider == null ? new TestProvider(null, null, null) : testProvider;
    callbackMaxBytes = callbackMaxBytes == null ? 65_536 : callbackMaxBytes;
    callbackRequestsPerMinute = callbackRequestsPerMinute == null ? 600 : callbackRequestsPerMinute;
  }

  /**
   * @return whether tokens and numbers can be sealed
   */
  public boolean keyConfigured() {
    return encryptionKey != null && !encryptionKey.isBlank();
  }

  /**
   * @return whether the callback endpoint has what it needs to verify Meta
   */
  public boolean callbackConfigured() {
    return appSecret != null
        && !appSecret.isBlank()
        && verifyToken != null
        && !verifyToken.isBlank();
  }

  /**
   * Validates for an environment; messages never contain a secret.
   *
   * @param environment deployment environment
   */
  public void validate(String environment) {
    String env = environment == null ? "" : environment.trim().toLowerCase(Locale.ROOT);
    boolean relaxed = "development".equals(env) || "test".equals(env);
    if (keyConfigured()) {
      if (encryptionKey.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
        throw new IllegalStateException(
            "divalhr.messaging.encryption-key must be at least 32 bytes");
      }
      if (!"development".equals(env)
          && encryptionKey.toLowerCase(Locale.ROOT).startsWith("dev-only-")) {
        throw new IllegalStateException(
            "the development messaging encryption key must not be used in environment " + env);
      }
    }
    if (appSecret != null && !appSecret.isBlank() && appSecret.length() < 16) {
      throw new IllegalStateException("divalhr.messaging.app-secret is too short");
    }
    if (verifyToken != null && !verifyToken.isBlank() && verifyToken.length() < 16) {
      throw new IllegalStateException("divalhr.messaging.verify-token is too short");
    }
    if (!VERSION.matcher(graphVersion).matches()) {
      throw new IllegalStateException(
          "divalhr.messaging.graph-version must be a pinned vNN.N version");
    }
    if (!graphBaseUrl.equals(META_GRAPH)) {
      boolean synthetic =
          relaxed
              && testProvider.configured()
              && testProvider.port() != null
              && graphBaseUrl.equals(
                  "https://"
                      + testProvider.host().toLowerCase(Locale.ROOT)
                      + ":"
                      + testProvider.port());
      if (!synthetic) {
        throw new IllegalStateException(
            "divalhr.messaging.graph-base-url must be the Meta Graph origin"
                + (relaxed ? " or the configured synthetic provider" : ""));
      }
    }
    if (testProvider.configured()) {
      if (!relaxed) {
        throw new IllegalStateException(
            "divalhr.messaging.test-provider is only allowed in development and test");
      }
      String h = testProvider.host();
      if (testProvider.port() == null
          || testProvider.port() < 1
          || testProvider.port() > 65535
          || h.contains("/")
          || h.contains("*")
          || h.contains(":")
          || !h.equals(h.toLowerCase(Locale.ROOT))) {
        throw new IllegalStateException(
            "divalhr.messaging.test-provider must be one exact host name and port");
      }
    }
    if (!publicBaseUrl.isEmpty() && DeepLink.normalizeBase(publicBaseUrl, relaxed).isEmpty()) {
      throw new IllegalStateException(
          "divalhr.messaging.public-base-url is not an acceptable origin");
    }
    if (connectTimeout.compareTo(Duration.ZERO) <= 0
        || requestTimeout.compareTo(Duration.ZERO) <= 0
        || requestTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
      throw new IllegalStateException("divalhr.messaging timeouts are out of range");
    }
    if (callbackMaxBytes < 1024 || callbackMaxBytes > 1_048_576 || callbackRequestsPerMinute < 1) {
      throw new IllegalStateException("divalhr.messaging callback limits are out of range");
    }
  }

  /**
   * @param relaxed whether development or test rules apply
   * @return the normalized deep-link base, or empty when none is configured
   */
  public java.util.Optional<String> deepLinkBase(boolean relaxed) {
    return DeepLink.normalizeBase(publicBaseUrl, relaxed);
  }

  /**
   * @return a representation without any secret
   */
  @Override
  public String toString() {
    return "MessagingProperties[key=" + (keyConfigured() ? "set" : "unset") + "]";
  }
}
