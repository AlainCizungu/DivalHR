package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.domain.PaymentCipher;
import com.divalhr.core.payroll.domain.PaymentSealException;
import java.util.UUID;

/**
 * The payment destination cipher, or nothing when the key is not configured (MVP-095A). Without
 * the key every operation that needs it fails closed with a stable 503 and nothing is stored.
 */
public final class PaymentSecrets {

  private final PaymentCipher cipher;

  PaymentSecrets(PaymentProperties properties) {
    this.cipher = properties.keyConfigured() ? new PaymentCipher(properties.encryptionKey()) : null;
  }

  /**
   * Wraps a cipher (tests).
   *
   * @param cipher the cipher, or {@code null}
   */
  public PaymentSecrets(PaymentCipher cipher) {
    this.cipher = cipher;
  }

  /**
   * Whether the key is available.
   *
   * @return {@code true} when destinations can be sealed and opened
   */
  public boolean available() {
    return cipher != null;
  }

  byte[] seal(String text, UUID tenant, UUID employee, UUID destination, int sequence) {
    return require().seal(text, tenant, employee, destination, sequence);
  }

  String open(byte[] blob, UUID tenant, UUID employee, UUID destination, int sequence) {
    return require().open(blob, tenant, employee, destination, sequence);
  }

  String fingerprint(UUID tenant, String purpose, String value) {
    return require().fingerprint(tenant, purpose, value);
  }

  private PaymentCipher require() {
    if (cipher == null) {
      throw new PaymentSealException();
    }
    return cipher;
  }
}
