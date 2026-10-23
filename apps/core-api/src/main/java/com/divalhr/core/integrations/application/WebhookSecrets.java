package com.divalhr.core.integrations.application;

import com.divalhr.core.integrations.domain.SecretCipher;
import java.util.UUID;

/** Seals and opens webhook signing secrets; unavailable when no key is configured. */
public final class WebhookSecrets {

  private final SecretCipher cipher;

  WebhookSecrets(IntegrationProperties properties) {
    this.cipher = properties.keyConfigured() ? new SecretCipher(properties.encryptionKey()) : null;
  }

  /**
   * Creates the service around a cipher (tests).
   *
   * @param cipher cipher, or null for unavailable
   */
  public WebhookSecrets(SecretCipher cipher) {
    this.cipher = cipher;
  }

  /**
   * @return whether secrets can be created and opened
   */
  public boolean available() {
    return cipher != null;
  }

  /**
   * Seals a secret.
   *
   * @param secret raw secret
   * @param tenant tenant
   * @param webhook webhook
   * @param version secret version
   * @return ciphertext
   */
  public byte[] seal(byte[] secret, UUID tenant, UUID webhook, int version) {
    return require().encrypt(secret, tenant, webhook, version);
  }

  /**
   * Opens a secret.
   *
   * @param blob ciphertext
   * @param tenant tenant
   * @param webhook webhook
   * @param version secret version
   * @return raw secret
   */
  public byte[] open(byte[] blob, UUID tenant, UUID webhook, int version) {
    return require().decrypt(blob, tenant, webhook, version);
  }

  private SecretCipher require() {
    if (cipher == null) {
      throw new IllegalStateException("webhook encryption key is not configured");
    }
    return cipher;
  }
}
