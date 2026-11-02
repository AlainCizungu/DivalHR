package com.divalhr.core.payroll.application;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The payment destination secrets (MVP-095A, D95A-3): one dedicated key from which the encryption
 * key and the fingerprint key are derived. It has no default, is read from the protected secret
 * directory and is used for nothing else. {@code toString} is redacted.
 *
 * @param encryptionKey the key text (at least 32 bytes), or {@code null}/blank when not configured
 */
@ConfigurationProperties("divalhr.payroll.payment")
public record PaymentProperties(String encryptionKey) {

  /**
   * Whether the key is configured.
   *
   * @return {@code true} when a key is present
   */
  public boolean keyConfigured() {
    return encryptionKey != null && !encryptionKey.isBlank();
  }

  /**
   * Refuses an unsafe key at start-up.
   *
   * @param environment the deployment environment
   */
  public void validate(String environment) {
    String env = environment == null ? "" : environment.trim().toLowerCase(Locale.ROOT);
    if (!keyConfigured()) {
      return;
    }
    if (encryptionKey.getBytes(StandardCharsets.UTF_8).length < 32) {
      throw new IllegalStateException(
          "divalhr.payroll.payment.encryption-key must be at least 32 bytes");
    }
    if (!"development".equals(env)
        && encryptionKey.toLowerCase(Locale.ROOT).startsWith("dev-only-")) {
      throw new IllegalStateException(
          "the development payment encryption key must not be used in environment " + env);
    }
  }

  @Override
  public String toString() {
    return "PaymentProperties[redacted]";
  }
}
