package com.divalhr.core.identity.internal.mail;

import java.time.Duration;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbound mail for invitations ({@code divalhr.mail}). Credentials come only from the environment
 * or a secret store. Outside development and test, TLS (STARTTLS or SMTPS) is required.
 *
 * @param host SMTP host
 * @param port SMTP port
 * @param username SMTP user (optional)
 * @param password SMTP password (optional)
 * @param from sender address
 * @param fromName sender display name
 * @param starttls require STARTTLS
 * @param ssl use SMTPS
 * @param timeout connect, read and write timeout
 */
@ConfigurationProperties("divalhr.mail")
public record MailProperties(
    String host,
    Integer port,
    String username,
    String password,
    String from,
    String fromName,
    Boolean starttls,
    Boolean ssl,
    Duration timeout) {

  /** Applies defaults. */
  public MailProperties {
    port = port == null ? 587 : port;
    fromName = fromName == null ? "DivalHR" : fromName;
    starttls = starttls == null ? Boolean.TRUE : starttls;
    ssl = ssl == null ? Boolean.FALSE : ssl;
    timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
  }

  /**
   * Validates the settings for an environment; messages never contain credentials.
   *
   * @param environment deployment environment
   */
  public void validate(String environment) {
    String env = environment == null ? "" : environment.trim().toLowerCase(Locale.ROOT);
    require(host != null && !host.isBlank(), "host is required");
    require(port >= 1 && port <= 65535, "port must be 1..65535");
    require(from != null && from.contains("@"), "from is required");
    require(
        Boolean.TRUE.equals(starttls)
            || Boolean.TRUE.equals(ssl)
            || "development".equals(env)
            || "test".equals(env),
        "TLS (starttls or ssl) is required outside development and test");
    require(
        !timeout.isNegative() && timeout.compareTo(Duration.ofSeconds(60)) <= 0,
        "timeout must be at most 60s");
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException("divalhr.mail." + message);
    }
  }

  @Override
  public String toString() {
    return "MailProperties[host=" + host + ", port=" + port + ", password=<redacted>]";
  }
}
