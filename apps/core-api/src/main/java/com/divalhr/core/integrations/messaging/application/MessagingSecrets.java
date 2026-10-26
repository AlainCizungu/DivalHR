package com.divalhr.core.integrations.messaging.application;

import com.divalhr.core.integrations.messaging.domain.MessagingCipher;
import com.divalhr.core.integrations.messaging.domain.PhoneNumber;
import java.util.UUID;
import java.util.function.Function;

/** Seals and opens the access token and recipient numbers; unavailable without a key. */
public final class MessagingSecrets {

  private final MessagingCipher cipher;

  MessagingSecrets(MessagingProperties properties) {
    this.cipher =
        properties.keyConfigured() ? new MessagingCipher(properties.encryptionKey()) : null;
  }

  /**
   * Creates the service around a cipher (tests).
   *
   * @param cipher cipher, or null for unavailable
   */
  public MessagingSecrets(MessagingCipher cipher) {
    this.cipher = cipher;
  }

  /**
   * @return whether tokens and numbers can be sealed and opened
   */
  public boolean available() {
    return cipher != null;
  }

  byte[] sealToken(String token, UUID tenant, UUID connector, int version) {
    return require().sealCredential(token, tenant, connector, version);
  }

  String openToken(byte[] blob, UUID tenant, UUID connector, int version) {
    return require().openCredential(blob, tenant, connector, version);
  }

  byte[] sealNumber(PhoneNumber number, UUID tenant, UUID connector, UUID recipient) {
    return require().sealNumber(number, tenant, connector, recipient);
  }

  PhoneNumber openNumber(byte[] blob, UUID tenant, UUID connector, UUID recipient) {
    return require().openNumber(blob, tenant, connector, recipient);
  }

  byte[] digest(PhoneNumber number, UUID tenant, UUID connector) {
    return require().digest(number, tenant, connector);
  }

  Function<String, String> idempotencyDigest() {
    MessagingCipher required = require();
    return required::idempotencyDigest;
  }

  private MessagingCipher require() {
    if (cipher == null) {
      throw new IllegalStateException("messaging encryption key is not configured");
    }
    return cipher;
  }
}
