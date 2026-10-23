package com.divalhr.core.integrations.domain;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs deliveries (D110-5). The signed bytes are exactly {@code <timestamp> "\n" <raw UTF-8 body>}
 * and the signature is {@code v1=<lower-case hex HMAC-SHA256>}. During a rotation overlap two
 * signatures are sent, comma separated, current first.
 */
public final class WebhookSigner {

  private WebhookSigner() {}

  /**
   * One signature.
   *
   * @param secret the raw signing secret bytes
   * @param timestamp Unix seconds, the value of the timestamp header
   * @param body the exact request body bytes
   * @return {@code v1=<hex>}
   */
  public static String sign(byte[] secret, long timestamp, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      mac.update(Long.toString(timestamp).getBytes(StandardCharsets.US_ASCII));
      mac.update((byte) '\n');
      return "v1=" + HexFormat.of().formatHex(mac.doFinal(body));
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  /**
   * The signature header value for one or two secrets.
   *
   * @param secrets current secret first, then the previous one while it is still valid
   * @param timestamp Unix seconds
   * @param body the exact request body bytes
   * @return comma-separated signatures
   */
  public static String header(List<byte[]> secrets, long timestamp, byte[] body) {
    return secrets.stream().map(s -> sign(s, timestamp, body)).collect(Collectors.joining(","));
  }
}
