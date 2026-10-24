package com.divalhr.core.integrations.messaging.domain;

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
 * Encrypts the WhatsApp system-user token and recipient numbers at rest (D111-4, D111-5):
 * AES-256-GCM under keys derived with HKDF-SHA256 from the dedicated {@code
 * divalhr.messaging.encryption-key}, which is used for nothing else and is separate from the
 * webhook, database, token-signing, invitation and cursor keys. Three subkeys are derived with
 * distinct {@code info} strings (encryption, phone-number digest and idempotency digest), so the
 * digest and the ciphertext keys never coincide. The authenticated data binds the provider, the
 * tenant, the connector, the credential version (or the recipient) and the algorithm version, so a
 * ciphertext cannot be moved to another row. Stored form: {@code 0x01 || 12-byte nonce ||
 * ciphertext||tag}. Failure messages never carry key, plaintext or identifiers.
 */
public final class MessagingCipher {

  /** Algorithm version bound into the authenticated data. */
  public static final int ALGORITHM_VERSION = 1;

  /** Minimum configured key length in bytes. */
  public static final int MIN_KEY_BYTES = 32;

  private static final String PROVIDER = "WHATSAPP";
  private static final int NONCE_BYTES = 12;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final SecretKeySpec encryptionKey;
  private final SecretKeySpec digestKey;
  private final SecretKeySpec idempotencyKey;

  /**
   * Creates the cipher.
   *
   * @param configuredKey the configured key text
   */
  public MessagingCipher(String configuredKey) {
    byte[] material = configuredKey.getBytes(StandardCharsets.UTF_8);
    if (material.length < MIN_KEY_BYTES) {
      throw new IllegalStateException(
          "divalhr.messaging.encryption-key must be at least " + MIN_KEY_BYTES + " bytes");
    }
    this.encryptionKey = new SecretKeySpec(hkdf(material, "encryption"), "AES");
    this.digestKey = new SecretKeySpec(hkdf(material, "phone-digest"), "HmacSHA256");
    this.idempotencyKey = new SecretKeySpec(hkdf(material, "idempotency-digest"), "HmacSHA256");
  }

  private static byte[] hkdf(byte[] ikm, String purpose) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
      byte[] prk = mac.doFinal(ikm);
      mac.init(new SecretKeySpec(prk, "HmacSHA256"));
      mac.update(
          ("divalhr/messaging/" + purpose + "/v" + ALGORITHM_VERSION)
              .getBytes(StandardCharsets.US_ASCII));
      mac.update((byte) 1);
      return mac.doFinal();
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException("messaging key derivation failed");
    }
  }

  private static byte[] credentialAad(UUID tenant, UUID connector, int credentialVersion) {
    return ("divalhr-messaging-credential\nprovider="
            + PROVIDER
            + "\ntenant="
            + tenant
            + "\nconnector="
            + connector
            + "\ncredentialVersion="
            + credentialVersion
            + "\nalgorithm="
            + ALGORITHM_VERSION)
        .getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] recipientAad(UUID tenant, UUID connector, UUID recipient) {
    return ("divalhr-messaging-recipient\nprovider="
            + PROVIDER
            + "\ntenant="
            + tenant
            + "\nconnector="
            + connector
            + "\nrecipient="
            + recipient
            + "\nalgorithm="
            + ALGORITHM_VERSION)
        .getBytes(StandardCharsets.US_ASCII);
  }

  /**
   * Seals the access token.
   *
   * @param token the plaintext token
   * @param tenant tenant id
   * @param connector connector id
   * @param credentialVersion credential version
   * @return the stored blob
   */
  public byte[] sealCredential(String token, UUID tenant, UUID connector, int credentialVersion) {
    return seal(
        token.getBytes(StandardCharsets.UTF_8),
        credentialAad(tenant, connector, credentialVersion));
  }

  /**
   * Opens the access token.
   *
   * @param blob the stored blob
   * @param tenant tenant id
   * @param connector connector id
   * @param credentialVersion credential version
   * @return the plaintext token
   * @throws IllegalStateException when the key, the binding or the data is wrong
   */
  public String openCredential(byte[] blob, UUID tenant, UUID connector, int credentialVersion) {
    return new String(
        open(blob, credentialAad(tenant, connector, credentialVersion)), StandardCharsets.UTF_8);
  }

  /**
   * Seals a recipient number.
   *
   * @param number the number
   * @param tenant tenant id
   * @param connector connector id
   * @param recipient recipient id
   * @return the stored blob
   */
  public byte[] sealNumber(PhoneNumber number, UUID tenant, UUID connector, UUID recipient) {
    return seal(
        number.e164().getBytes(StandardCharsets.US_ASCII),
        recipientAad(tenant, connector, recipient));
  }

  /**
   * Opens a recipient number.
   *
   * @param blob the stored blob
   * @param tenant tenant id
   * @param connector connector id
   * @param recipient recipient id
   * @return the number
   * @throws IllegalStateException when the key, the binding or the data is wrong
   */
  public PhoneNumber openNumber(byte[] blob, UUID tenant, UUID connector, UUID recipient) {
    String text =
        new String(
            open(blob, recipientAad(tenant, connector, recipient)), StandardCharsets.US_ASCII);
    return PhoneNumber.parse(text)
        .orElseThrow(() -> new IllegalStateException("stored number is unreadable"));
  }

  /**
   * The domain-separated keyed digest used for per-connector number uniqueness.
   *
   * @param number the number
   * @param tenant tenant id
   * @param connector connector id
   * @return a 32-byte digest
   */
  public byte[] digest(PhoneNumber number, UUID tenant, UUID connector) {
    return hmac(
        digestKey,
        ("divalhr-messaging-phone\ntenant="
                + tenant
                + "\nconnector="
                + connector
                + "\n"
                + number.e164())
            .getBytes(StandardCharsets.US_ASCII));
  }

  /**
   * A keyed fingerprint of a secret value for the idempotency canonical form, so the idempotency
   * store never holds the token itself (D111-4).
   *
   * @param value the secret value
   * @return a hex digest
   */
  public String idempotencyDigest(String value) {
    return HexFormat.of().formatHex(hmac(idempotencyKey, value.getBytes(StandardCharsets.UTF_8)));
  }

  private static byte[] hmac(SecretKeySpec key, byte[] data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(key);
      return mac.doFinal(data);
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException("messaging digest failed");
    }
  }

  private byte[] seal(byte[] plaintext, byte[] aad) {
    try {
      byte[] nonce = new byte[NONCE_BYTES];
      RANDOM.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(128, nonce));
      cipher.updateAAD(aad);
      byte[] sealed = cipher.doFinal(plaintext);
      return ByteBuffer.allocate(1 + NONCE_BYTES + sealed.length)
          .put((byte) ALGORITHM_VERSION)
          .put(nonce)
          .put(sealed)
          .array();
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("messaging encryption failed");
    }
  }

  private byte[] open(byte[] blob, byte[] aad) {
    try {
      if (blob == null || blob.length < 1 + NONCE_BYTES + 16 || blob[0] != ALGORITHM_VERSION) {
        throw new GeneralSecurityException("shape");
      }
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          encryptionKey,
          new GCMParameterSpec(128, Arrays.copyOfRange(blob, 1, 1 + NONCE_BYTES)));
      cipher.updateAAD(aad);
      return cipher.doFinal(blob, 1 + NONCE_BYTES, blob.length - 1 - NONCE_BYTES);
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("messaging decryption failed");
    }
  }
}
