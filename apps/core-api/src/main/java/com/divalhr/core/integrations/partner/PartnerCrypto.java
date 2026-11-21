package com.divalhr.core.integrations.partner;

import com.divalhr.core.integrations.application.IntegrationProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Encryption of confidential partner-application data and keyed digests (MVP-120D, ADR 0043). Two
 * independent subkeys are derived by HKDF-SHA256 from the configured integrations key with distinct
 * info strings: an AES-256-GCM key for contact data and notes (the application and check are bound
 * as associated data) and an HMAC key for the deduplication and rate-limit digests. The key never
 * reaches the database; nothing here logs or returns plaintext.
 */
@Component
public class PartnerCrypto {

  private static final byte[] ENCRYPTION_INFO =
      "divalhr/partner-application/aes-256-gcm/v1".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] DIGEST_INFO =
      "divalhr/partner-application/digest-hmac-sha256/v1".getBytes(StandardCharsets.US_ASCII);
  private static final int NONCE_BYTES = 12;
  private static final int VERSION = 1;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final SecretKeySpec encryptionKey;
  private final SecretKeySpec digestKey;

  /**
   * Creates the service; without a configured key it is unavailable and fails closed.
   *
   * @param properties integrations settings (the shared encryption key)
   */
  public PartnerCrypto(IntegrationProperties properties) {
    if (properties.keyConfigured()) {
      byte[] material = properties.encryptionKey().getBytes(StandardCharsets.UTF_8);
      this.encryptionKey = new SecretKeySpec(hkdf(material, ENCRYPTION_INFO), "AES");
      this.digestKey = new SecretKeySpec(hkdf(material, DIGEST_INFO), "HmacSHA256");
    } else {
      this.encryptionKey = null;
      this.digestKey = null;
    }
  }

  /**
   * Whether a key is configured.
   *
   * @return true when partner data can be sealed
   */
  public boolean available() {
    return encryptionKey != null;
  }

  /**
   * Seals plaintext.
   *
   * @param plaintext the bytes
   * @param associatedData binding context (never secret)
   * @return version, nonce and sealed bytes
   */
  public byte[] seal(byte[] plaintext, String associatedData) {
    try {
      byte[] nonce = new byte[NONCE_BYTES];
      RANDOM.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, require(encryptionKey), new GCMParameterSpec(128, nonce));
      cipher.updateAAD(associatedData.getBytes(StandardCharsets.US_ASCII));
      byte[] sealed = cipher.doFinal(plaintext);
      return ByteBuffer.allocate(1 + NONCE_BYTES + sealed.length)
          .put((byte) VERSION)
          .put(nonce)
          .put(sealed)
          .array();
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("partner data encryption failed");
    }
  }

  /**
   * Opens a sealed blob.
   *
   * @param blob sealed bytes
   * @param associatedData the same binding context
   * @return plaintext
   */
  public byte[] open(byte[] blob, String associatedData) {
    try {
      if (blob == null || blob.length < 1 + NONCE_BYTES + 16 || blob[0] != VERSION) {
        throw new GeneralSecurityException("shape");
      }
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          require(encryptionKey),
          new GCMParameterSpec(128, Arrays.copyOfRange(blob, 1, 1 + NONCE_BYTES)));
      cipher.updateAAD(associatedData.getBytes(StandardCharsets.US_ASCII));
      return cipher.doFinal(blob, 1 + NONCE_BYTES, blob.length - 1 - NONCE_BYTES);
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("partner data decryption failed");
    }
  }

  /**
   * A keyed one-way digest, domain-separated by label.
   *
   * @param label purpose, for example {@code email}
   * @param normalized the normalized value
   * @return 64 lowercase hex characters
   */
  public String digest(String label, String normalized) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(require(digestKey));
      mac.update(label.getBytes(StandardCharsets.US_ASCII));
      mac.update((byte) 0);
      return HexFormat.of().formatHex(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static SecretKeySpec require(SecretKeySpec key) {
    if (key == null) {
      throw new IllegalStateException("partner encryption key is not configured");
    }
    return key;
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
}
