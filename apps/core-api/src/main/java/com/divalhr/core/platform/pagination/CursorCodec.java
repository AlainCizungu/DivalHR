package com.divalhr.core.platform.pagination;

import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.Fingerprints;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Opaque keyset cursors: {@code base64url(payload) "." base64url(HMAC-SHA256(payload))}.
 *
 * <p>The payload is {@code {"v":1,"c":code,"i":uuid,"q":binding}} where {@code binding} is the
 * SHA-256 of the {@link CursorScope}. Decoding rejects oversize input before any decoding, checks
 * the MAC in constant time before parsing, and accepts only the exact allow-listed schema. Every
 * failure is the same {@code CURSOR_INVALID}; cursors, payloads, bindings and the key are never
 * logged.
 */
@Component
public final class CursorCodec {

  /** Maximum accepted cursor length (docs/API-SPEC.yaml). */
  public static final int MAX_LENGTH = 512;

  private static final int VERSION = 1;
  private static final String ALGORITHM = "HmacSHA256";
  private static final Set<String> FIELDS = Set.of("v", "c", "i", "q");
  private static final Pattern CODE = Pattern.compile("^[A-Z0-9_-]{2,20}$");
  private static final Pattern SHAPE = Pattern.compile("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$");
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  private final SecretKeySpec key;
  private final JsonMapper json;

  /**
   * Creates the codec.
   *
   * @param properties pagination settings
   * @param environment deployment environment
   * @param json JSON mapper
   */
  public CursorCodec(
      CursorProperties properties,
      @Value("${divalhr.environment}") String environment,
      JsonMapper json) {
    this.key =
        new SecretKeySpec(
            CursorSigningKeyGuard.validate(environment, properties.cursorSigningKey()), ALGORITHM);
    this.json = json;
  }

  /**
   * Encodes the position after the last row of a page.
   *
   * @param scope query binding
   * @param position last row
   * @return opaque cursor
   */
  public String encode(CursorScope scope, KeysetPosition position) {
    Map<String, Object> payload =
        Map.of(
            "v", VERSION,
            "c", position.code(),
            "i", position.id().toString(),
            "q", Fingerprints.sha256(scope.canonical()));
    byte[] bytes = json.writeValueAsBytes(payload);
    return ENCODER.encodeToString(bytes) + "." + ENCODER.encodeToString(mac(bytes));
  }

  /**
   * Decodes and verifies a cursor for the given scope.
   *
   * @param cursor opaque cursor from the client
   * @param scope expected query binding
   * @return position to continue after
   * @throws ApiException {@link ErrorCode#CURSOR_INVALID} for any defect
   */
  public KeysetPosition decode(String cursor, CursorScope scope) {
    if (cursor == null || cursor.length() > MAX_LENGTH || !SHAPE.matcher(cursor).matches()) {
      throw invalid();
    }
    int dot = cursor.indexOf('.');
    byte[] payload;
    byte[] signature;
    try {
      payload = DECODER.decode(cursor.substring(0, dot));
      signature = DECODER.decode(cursor.substring(dot + 1));
    } catch (IllegalArgumentException malformed) {
      throw invalid();
    }
    if (!MessageDigest.isEqual(mac(payload), signature)) {
      throw invalid();
    }
    JsonNode node;
    try {
      node = json.readTree(new String(payload, StandardCharsets.UTF_8));
    } catch (RuntimeException unreadable) {
      throw invalid();
    }
    if (node == null || !node.isObject() || node.size() != FIELDS.size()) {
      throw invalid();
    }
    Iterator<String> names = node.propertyNames().iterator();
    while (names.hasNext()) {
      if (!FIELDS.contains(names.next())) {
        throw invalid();
      }
    }
    JsonNode version = node.get("v");
    JsonNode code = node.get("c");
    JsonNode id = node.get("i");
    JsonNode binding = node.get("q");
    if (!version.isInt()
        || version.asInt() != VERSION
        || !code.isString()
        || !id.isString()
        || !binding.isString()) {
      throw invalid();
    }
    byte[] expected = Fingerprints.sha256(scope.canonical()).getBytes(StandardCharsets.UTF_8);
    if (!MessageDigest.isEqual(expected, binding.asString().getBytes(StandardCharsets.UTF_8))) {
      throw invalid();
    }
    if (!CODE.matcher(code.asString()).matches()) {
      throw invalid();
    }
    try {
      return new KeysetPosition(code.asString(), UUID.fromString(id.asString()));
    } catch (IllegalArgumentException badId) {
      throw invalid();
    }
  }

  private byte[] mac(byte[] payload) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(key);
      return mac.doFinal(payload);
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static ApiException invalid() {
    return new ApiException(ErrorCode.CURSOR_INVALID, Map.of());
  }
}
