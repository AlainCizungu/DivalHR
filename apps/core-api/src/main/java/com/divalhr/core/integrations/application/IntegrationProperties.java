package com.divalhr.core.integrations.application;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Webhook settings ({@code divalhr.integrations}, MVP-110). The encryption key has no default: when
 * it is blank the capability is unavailable (creating, rotating or testing answers {@code 503
 * WEBHOOK_SECRET_UNAVAILABLE}, the worker idles). A configured key must be at least 32 bytes and a
 * {@code dev-only-} value is refused outside development. The test receiver exception names one
 * exact host and port and is refused outside development and test.
 *
 * @param encryptionKey the dedicated secret-encryption key text
 * @param extraPorts HTTPS ports allowed besides 443
 * @param jitter whether retry delays are jittered (production true)
 * @param connectTimeout TCP connect deadline
 * @param requestTimeout whole-attempt deadline
 * @param testReceiver optional hr-dev/test-only destination exception
 */
@ConfigurationProperties("divalhr.integrations")
public record IntegrationProperties(
    String encryptionKey,
    List<Integer> extraPorts,
    Boolean jitter,
    Duration connectTimeout,
    Duration requestTimeout,
    TestReceiver testReceiver) {

  /**
   * The single permitted private destination for a controlled synthetic receiver.
   *
   * @param host exact receiver host name
   * @param port exact receiver port
   * @param caFile optional PEM file with the receiver's CA, trusted in addition to the defaults
   */
  public record TestReceiver(String host, Integer port, String caFile) {
    /**
     * @return whether an exception is configured
     */
    public boolean configured() {
      return host != null && !host.isBlank();
    }
  }

  /** Applies defaults. */
  public IntegrationProperties {
    extraPorts = extraPorts == null ? List.of() : List.copyOf(extraPorts);
    jitter = jitter == null || jitter;
    connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
    requestTimeout = requestTimeout == null ? Duration.ofSeconds(10) : requestTimeout;
    testReceiver = testReceiver == null ? new TestReceiver(null, null, null) : testReceiver;
  }

  /**
   * @return whether secrets can be encrypted
   */
  public boolean keyConfigured() {
    return encryptionKey != null && !encryptionKey.isBlank();
  }

  /**
   * Validates for an environment; messages never contain the key.
   *
   * @param environment deployment environment
   */
  public void validate(String environment) {
    String env = environment == null ? "" : environment.trim().toLowerCase(Locale.ROOT);
    if (keyConfigured()) {
      if (encryptionKey.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
        throw new IllegalStateException(
            "divalhr.integrations.encryption-key must be at least 32 bytes");
      }
      if (!"development".equals(env)
          && encryptionKey.toLowerCase(Locale.ROOT).startsWith("dev-only-")) {
        throw new IllegalStateException(
            "the development webhook encryption key must not be used in environment " + env);
      }
    }
    for (int port : extraPorts) {
      if (port < 1 || port > 65535 || port == 443) {
        throw new IllegalStateException("divalhr.integrations.extra-ports holds an invalid port");
      }
    }
    if (connectTimeout.compareTo(Duration.ZERO) <= 0
        || requestTimeout.compareTo(Duration.ZERO) <= 0
        || requestTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
      throw new IllegalStateException("divalhr.integrations timeouts are out of range");
    }
    if (testReceiver.configured()) {
      boolean relaxed = "development".equals(env) || "test".equals(env);
      if (!relaxed) {
        throw new IllegalStateException(
            "divalhr.integrations.test-receiver is only allowed in development and test");
      }
      if (testReceiver.port() == null || testReceiver.port() < 1 || testReceiver.port() > 65535) {
        throw new IllegalStateException("divalhr.integrations.test-receiver.port is required");
      }
      String h = testReceiver.host();
      if (h.contains("/")
          || h.contains("*")
          || h.contains(":")
          || !h.equals(h.toLowerCase(Locale.ROOT))) {
        throw new IllegalStateException(
            "divalhr.integrations.test-receiver.host must be one exact host name");
      }
    }
  }

  /**
   * @return a representation without the key
   */
  @Override
  public String toString() {
    return "IntegrationProperties[key=" + (keyConfigured() ? "set" : "unset") + "]";
  }
}
