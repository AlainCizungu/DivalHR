package com.divalhr.core.integrations.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts webhook signing secrets at rest (D110-4): AES-256-GCM under a key derived with
 * HKDF-SHA256 from the dedicated {@code divalhr.integrations.encryption-key} (text of at least 32
 * bytes, used for nothing else). The authenticated data binds the tenant, the webhook, the secret
 * version and the algorithm version, so a ciphertext cannot be moved to another row. Stored form:
 * {@code 0x01 || 12-byte nonce || ciphertext||tag}. Messages never carry key or plaintext.
 */
public final class SecretCipher {

  /** Algorithm version bound into the authenticated data. */
  public static final int ALGORITHM_VERSION = 1;

  /** Minimum configured key length in bytes. */
  public static final int MIN_KEY_BYTES = 32;

  private static final byte[] INFO =
      "divalhr/webhook-secret/aes-256-gcm/v1".getBytes(StandardCharsets.US_ASCII);
  private static final int NONCE_BYTES = 12;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final SecretKeySpec key;

  /**
   * Creates the cipher.
   *
   * @param configuredKey the configured key text
   */
  public SecretCipher(String configuredKey) {
    byte[] material = configuredKey.getBytes(StandardCharsets.UTF_8);
    if (material.length < MIN_KEY_BYTES) {
      throw new IllegalStateException(
          "divalhr.integrations.encryption-key must be at least " + MIN_KEY_BYTES + " bytes");
    }
    this.key = new SecretKeySpec(hkdf(material), "AES");
  }

  private static byte[] hkdf(byte[] ikm) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
      byte[] prk = mac.doFinal(ikm);
      mac.init(new SecretKeySpec(prk, "HmacSHA256"));
      mac.update(INFO);
      mac.update((byte) 1);
      return mac.doFinal();
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static byte[] aad(UUID tenant, UUID webhook, int secretVersion) {
    return ("divalhr-webhook-secret\ntenant="
            + tenant
            + "\nwebhook="
            + webhook
            + "\nsecretVersion="
            + secretVersion
            + "\nalgorithm="
            + ALGORITHM_VERSION)
        .getBytes(StandardCharsets.US_ASCII);
  }

  /**
   * Encrypts a secret.
   *
   * @param plaintext the raw secret bytes
   * @param tenant tenant id
   * @param webhook webhook id
   * @param secretVersion secret version
   * @return the stored blob
   */
  public byte[] encrypt(byte[] plaintext, UUID tenant, UUID webhook, int secretVersion) {
    try {
      byte[] nonce = new byte[NONCE_BYTES];
      RANDOM.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
      cipher.updateAAD(aad(tenant, webhook, secretVersion));
      byte[] sealed = cipher.doFinal(plaintext);
      return ByteBuffer.allocate(1 + NONCE_BYTES + sealed.length)
          .put((byte) ALGORITHM_VERSION)
          .put(nonce)
          .put(sealed)
          .array();
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("secret encryption failed");
    }
  }

  /**
   * Decrypts a stored secret.
   *
   * @param blob the stored blob
   * @param tenant tenant id
   * @param webhook webhook id
   * @param secretVersion secret version
   * @return the raw secret bytes
   * @throws IllegalStateException when the key, the binding or the data is wrong
   */
  public byte[] decrypt(byte[] blob, UUID tenant, UUID webhook, int secretVersion) {
    try {
      if (blob == null || blob.length < 1 + NONCE_BYTES + 16 || blob[0] != ALGORITHM_VERSION) {
        throw new GeneralSecurityException("shape");
      }
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          key,
          new GCMParameterSpec(128, Arrays.copyOfRange(blob, 1, 1 + NONCE_BYTES)));
      cipher.updateAAD(aad(tenant, webhook, secretVersion));
      return cipher.doFinal(blob, 1 + NONCE_BYTES, blob.length - 1 - NONCE_BYTES);
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("secret decryption failed");
    }
  }

  /**
   * @return a fresh random 32-byte signing secret
   */
  public static byte[] newSigningSecret() {
    byte[] secret = new byte[32];
    RANDOM.nextBytes(secret);
    return secret;
  }
}
