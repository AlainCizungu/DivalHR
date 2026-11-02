package com.divalhr.core.payroll.domain;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs provider requests (MVP-095B, D95B-3): {@code v1=<hex>} where the value is HMAC-SHA256 over
 * {@code "v1\n" + requestId + "\n" + canonicalBytes} with the versioned signing key. The key is never
 * stored, logged or returned; {@code toString} is redacted.
 */
public final class PaymentSubmissionSigner {

  /** Minimum key length in bytes. */
  public static final int MIN_KEY_BYTES = 32;

  private final byte[] key;

  /**
   * Creates the signer.
   *
   * @param keyText the key text (at least 32 bytes in UTF-8)
   */
  public PaymentSubmissionSigner(String keyText) {
    byte[] bytes = keyText == null ? new byte[0] : keyText.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < MIN_KEY_BYTES) {
      throw new IllegalArgumentException("the submission signing key must be at least 32 bytes");
    }
    this.key = bytes;
  }

  /**
   * Signs a request.
   *
   * @param requestId the stable request ID
   * @param body the canonical bytes
   * @return {@code v1=<64 hex characters>}
   */
  public String sign(UUID requestId, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      mac.update("v1\n".getBytes(StandardCharsets.US_ASCII));
      mac.update(requestId.toString().getBytes(StandardCharsets.US_ASCII));
      mac.update((byte) '\n');
      mac.update(body);
      return "v1=" + HexFormat.of().formatHex(mac.doFinal());
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("the submission signature could not be computed");
    }
  }

  /**
   * Constant-time verification (the synthetic receiver's rule, kept here for the contract tests).
   *
   * @param requestId the request ID
   * @param body the body
   * @param presented the presented signature
   * @return {@code true} when equal
   */
  public boolean verify(UUID requestId, byte[] body, String presented) {
    if (presented == null) {
      return false;
    }
    return MessageDigest.isEqual(
        sign(requestId, body).getBytes(StandardCharsets.US_ASCII),
        presented.getBytes(StandardCharsets.US_ASCII));
  }

  @Override
  public String toString() {
    return "PaymentSubmissionSigner[redacted]";
  }
}
