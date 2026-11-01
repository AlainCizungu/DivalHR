package com.divalhr.core.payroll.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts payment destination values at rest and fingerprints them (MVP-095A, D95A-3): AES-256-GCM
 * under a key derived with HKDF-SHA256 from the dedicated payment encryption key (used for nothing
 * else), and a separate HMAC-SHA256 key derived from the same material for the keyed fingerprints
 * that detect duplicates. The authenticated data binds the tenant, the employee, the destination,
 * its version, the key version and the algorithm version, so a ciphertext cannot be moved to
 * another row. Stored form: {@code 0x01 || 12-byte nonce || ciphertext||tag}. Messages and
 * exceptions never carry key or plaintext.
 */
public final class PaymentCipher {

  /** Algorithm version bound into the authenticated data. */
  public static final int ALGORITHM_VERSION = 1;

  /** The key version recorded with every sealed value (key rotation is a later story). */
  public static final int KEY_VERSION = 1;

  /** Minimum configured key length in bytes. */
  public static final int MIN_KEY_BYTES = 32;

  private static final byte[] ENCRYPTION_INFO =
      "divalhr/payment-destination/aes-256-gcm/v1".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] FINGERPRINT_INFO =
      "divalhr/payment-destination/hmac-sha256/v1".getBytes(StandardCharsets.US_ASCII);
  private static final int NONCE_BYTES = 12;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final SecretKeySpec encryptionKey;
  private final SecretKeySpec fingerprintKey;

  /**
   * Creates the cipher.
   *
   * @param configuredKey the configured key text
   */
  public PaymentCipher(String configuredKey) {
    byte[] material = configuredKey.getBytes(StandardCharsets.UTF_8);
    if (material.length < MIN_KEY_BYTES) {
      throw new IllegalStateException(
          "divalhr.payroll.payment.encryption-key must be at least " + MIN_KEY_BYTES + " bytes");
    }
    this.encryptionKey = new SecretKeySpec(hkdf(material, ENCRYPTION_INFO), "AES");
    this.fingerprintKey = new SecretKeySpec(hkdf(material, FINGERPRINT_INFO), "HmacSHA256");
  }

  private static byte[] hkdf(byte[] ikm, byte[] info) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
      byte[] prk = mac.doFinal(ikm);
      mac.init(new SecretKeySpec(prk, "HmacSHA256"));
      mac.update(info);
      mac.update((byte) 1);
      return mac.doFinal();
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static byte[] aad(UUID tenant, UUID employee, UUID destination, int sequence) {
    return ("divalhr-payment-destination\ntenant="
            + tenant
            + "\nemployee="
            + employee
            + "\ndestination="
            + destination
            + "\nsequence="
            + sequence
            + "\nkeyVersion="
            + KEY_VERSION
            + "\nalgorithm="
            + ALGORITHM_VERSION)
        .getBytes(StandardCharsets.US_ASCII);
  }

  /**
   * Seals a destination's sensitive text.
   *
   * @param plaintext the sensitive text
   * @param tenant tenant
   * @param employee employee
   * @param destination destination row
   * @param sequence destination version
   * @return the stored blob
   */
  public byte[] seal(String plaintext, UUID tenant, UUID employee, UUID destination, int sequence) {
    try {
      byte[] nonce = new byte[NONCE_BYTES];
      RANDOM.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(128, nonce));
      cipher.updateAAD(aad(tenant, employee, destination, sequence));
      byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      return ByteBuffer.allocate(1 + NONCE_BYTES + sealed.length)
          .put((byte) ALGORITHM_VERSION)
          .put(nonce)
          .put(sealed)
          .array();
    } catch (GeneralSecurityException failure) {
      throw new PaymentSealException();
    }
  }

  /**
   * Opens and authenticates a stored blob.
   *
   * @param blob the stored blob
   * @param tenant tenant
   * @param employee employee
   * @param destination destination row
   * @param sequence destination version
   * @return the sensitive text
   * @throws PaymentSealException when the key, the binding or the data is wrong
   */
  public String open(byte[] blob, UUID tenant, UUID employee, UUID destination, int sequence) {
    try {
      if (blob == null || blob.length < 1 + NONCE_BYTES + 16 || blob[0] != ALGORITHM_VERSION) {
        throw new GeneralSecurityException("shape");
      }
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          encryptionKey,
          new GCMParameterSpec(128, Arrays.copyOfRange(blob, 1, 1 + NONCE_BYTES)));
      cipher.updateAAD(aad(tenant, employee, destination, sequence));
      byte[] plain = cipher.doFinal(blob, 1 + NONCE_BYTES, blob.length - 1 - NONCE_BYTES);
      return new String(plain, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException failure) {
      throw new PaymentSealException();
    }
  }

  /**
   * A keyed fingerprint (lower-case hex HMAC-SHA256). It is used for duplicate detection and to
   * keep sensitive values out of digests; it is never returned, logged, audited or published.
   *
   * @param tenant tenant
   * @param purpose a fixed label separating the uses
   * @param value the value
   * @return the fingerprint
   */
  public String fingerprint(UUID tenant, String purpose, String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(fingerprintKey);
      mac.update(("DIVALHR-PAYMENT-FP\ntenant=" + tenant + "\npurpose=" + purpose + "\nvalue=")
          .getBytes(StandardCharsets.UTF_8));
      mac.update(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(mac.doFinal());
    } catch (GeneralSecurityException impossible) {
      throw new PaymentSealException();
    }
  }
}
