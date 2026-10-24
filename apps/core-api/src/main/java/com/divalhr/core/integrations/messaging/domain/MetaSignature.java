package com.divalhr.core.integrations.messaging.domain;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies Meta's {@code X-Hub-Signature-256} over the exact raw request bytes with the platform
 * app secret (D111-10). The comparison is constant time, the header must have the exact {@code
 * sha256=} + 64 lower- or upper-case hex shape, and nothing about a mismatch is reported beyond
 * "false".
 */
public final class MetaSignature {

  private static final String PREFIX = "sha256=";

  private MetaSignature() {}

  /**
   * Computes the header value for a body (used by the synthetic provider and tests).
   *
   * @param appSecret the app secret
   * @param body the exact bytes
   * @return {@code sha256=<hex>}
   */
  public static String sign(String appSecret, byte[] body) {
    return PREFIX + HexFormat.of().formatHex(hmac(appSecret, body));
  }

  /**
   * Verifies a header.
   *
   * @param appSecret the app secret; blank means nothing verifies
   * @param body the exact raw bytes
   * @param header the header value, or null
   * @return whether the signature is valid
   */
  public static boolean verify(String appSecret, byte[] body, String header) {
    if (appSecret == null || appSecret.isBlank() || header == null) {
      return false;
    }
    if (header.length() != PREFIX.length() + 64 || !header.startsWith(PREFIX)) {
      return false;
    }
    byte[] provided;
    try {
      provided =
          HexFormat.of()
              .parseHex(header.substring(PREFIX.length()).toLowerCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException malformed) {
      return false;
    }
    return MessageDigest.isEqual(hmac(appSecret, body), provided);
  }

  private static byte[] hmac(String secret, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return mac.doFinal(body);
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException("signature computation failed");
    }
  }
}
