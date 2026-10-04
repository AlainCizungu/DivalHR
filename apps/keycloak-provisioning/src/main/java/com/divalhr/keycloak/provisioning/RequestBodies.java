package com.divalhr.keycloak.provisioning;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Reads and validates request input. Called only after the caller is authorized (A2). Never echoes
 * input: every failure is one of the stable codes of the contract.
 */
public final class RequestBodies {

  /** Maximum body size in bytes, counted while streaming (A2). */
  public static final int MAX_BYTES = 2048;

  private static final Pattern UUID_TEXT =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
  private static final int MAX_EMAIL = 254;
  private static final int MAX_LOCAL = 64;
  // Same rules as the Core API's EmailAddress, applied to its canonical output only.
  private static final Pattern LOCAL =
      Pattern.compile("^[a-z0-9!#$%&'*+/=?^_`{|}~-]+(\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*$");
  private static final Pattern LABEL = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");
  private static final Set<String> LOCALES = Set.of("fr", "en");

  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .disable(JsonParser.Feature.ALLOW_COMMENTS)
          .build();

  private RequestBodies() {}

  /** A request input failure with its stable code and HTTP status. */
  public static final class Rejected extends Exception {
    private static final long serialVersionUID = 1L;

    /** Stable code. */
    private final String code;

    /** HTTP status. */
    private final int status;

    Rejected(String code, int status) {
      super(code, null, false, false);
      this.code = code;
      this.status = status;
    }

    /**
     * Stable code.
     *
     * @return code
     */
    public String code() {
      return code;
    }

    /**
     * HTTP status.
     *
     * @return status
     */
    public int status() {
      return status;
    }
  }

  /** A validated create request. */
  public record Provision(String email, InvitationRole role, UUID tenantId, String locale) {}

  /**
   * Validates a canonical lowercase UUID path value (tenant or subject, MVP-022).
   *
   * @param value path value
   * @return the ID
   * @throws Rejected 400 {@code INVALID_REQUEST}
   */
  public static UUID canonicalId(String value) throws Rejected {
    return invitationId(value);
  }

  /**
   * Validates an invitation ID path value.
   *
   * @param value path value
   * @return the ID
   * @throws Rejected 400 {@code INVALID_REQUEST}
   */
  public static UUID invitationId(String value) throws Rejected {
    if (value == null || !UUID_TEXT.matcher(value).matches()) {
      throw invalid();
    }
    return UUID.fromString(value);
  }

  /**
   * Reads at most {@link #MAX_BYTES} bytes, then parses exactly one JSON object.
   *
   * @param contentType request media type, as sent
   * @param contentLength declared length, or -1
   * @param body request stream
   * @return the object
   * @throws Rejected on any violation
   */
  public static JsonNode readObject(String contentType, long contentLength, InputStream body)
      throws Rejected {
    if (!isJson(contentType)) {
      throw new Rejected("UNSUPPORTED_MEDIA_TYPE", 415);
    }
    if (contentLength > MAX_BYTES) {
      throw new Rejected("REQUEST_TOO_LARGE", 413);
    }
    byte[] bytes = readBounded(body);
    JsonNode node;
    try {
      node = JSON.readTree(bytes);
    } catch (JsonProcessingException malformed) {
      throw invalid();
    } catch (IOException io) {
      throw invalid();
    }
    if (node == null || !node.isObject()) {
      throw invalid();
    }
    return node;
  }

  /**
   * Validates a create request: exactly the four allowed fields.
   *
   * @param node parsed object
   * @return the request
   * @throws Rejected on any violation
   */
  public static Provision provision(JsonNode node) throws Rejected {
    requireExactFields(node, Set.of("email", "role", "tenantId", "locale"));
    String email = text(node, "email");
    if (!isCanonicalEmail(email)) {
      throw invalid();
    }
    InvitationRole role =
        InvitationRole.fromWire(text(node, "role")).orElseThrow(RequestBodies::invalid);
    String tenant = text(node, "tenantId");
    if (!UUID_TEXT.matcher(tenant).matches()) {
      throw invalid();
    }
    String locale = text(node, "locale");
    if (!LOCALES.contains(locale)) {
      throw invalid();
    }
    return new Provision(email, role, UUID.fromString(tenant), locale);
  }

  /**
   * Requires an empty body (credential setup takes none). Reads at most one byte.
   *
   * @param contentLength declared length, or -1
   * @param body request stream, possibly null
   * @throws Rejected when any content is declared or present
   */
  public static void requireEmpty(long contentLength, InputStream body) throws Rejected {
    if (contentLength > 0) {
      throw invalid();
    }
    if (body == null) {
      return;
    }
    try {
      if (body.read() != -1) {
        throw invalid();
      }
    } catch (IOException io) {
      throw invalid();
    }
  }

  /**
   * Whether an address is already in the Core API's canonical form. A non-canonical value is
   * rejected, never re-normalized.
   *
   * @param email candidate
   * @return true when canonical
   */
  public static boolean isCanonicalEmail(String email) {
    if (email == null || email.length() < 3 || email.length() > MAX_EMAIL) {
      return false;
    }
    if (!email.equals(email.toLowerCase(Locale.ROOT))) {
      return false;
    }
    int at = email.indexOf('@');
    if (at <= 0 || at != email.lastIndexOf('@') || at == email.length() - 1) {
      return false;
    }
    String local = email.substring(0, at);
    if (local.length() > MAX_LOCAL || !LOCAL.matcher(local).matches()) {
      return false;
    }
    String[] labels = email.substring(at + 1).split("\\.", -1);
    if (labels.length < 2) {
      return false;
    }
    for (String label : labels) {
      if (!LABEL.matcher(label).matches()) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether a correlation ID is safe to record (a canonical UUID).
   *
   * @param value header value
   * @return the value when safe
   */
  public static Optional<String> correlationId(String value) {
    return value != null && UUID_TEXT.matcher(value).matches()
        ? Optional.of(value)
        : Optional.empty();
  }

  private static boolean isJson(String contentType) {
    if (contentType == null) {
      return false;
    }
    String[] parts = contentType.split(";");
    if (!"application/json".equals(parts[0].trim().toLowerCase(Locale.ROOT))) {
      return false;
    }
    for (int i = 1; i < parts.length; i++) {
      String parameter = parts[i].trim().toLowerCase(Locale.ROOT).replace(" ", "");
      if (!parameter.equals("charset=utf-8")) {
        return false;
      }
    }
    return true;
  }

  private static byte[] readBounded(InputStream body) throws Rejected {
    if (body == null) {
      throw invalid();
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[512];
    try {
      int total = 0;
      int read;
      while ((read = body.read(buffer)) != -1) {
        total += read;
        if (total > MAX_BYTES) {
          throw new Rejected("REQUEST_TOO_LARGE", 413);
        }
        out.write(buffer, 0, read);
      }
    } catch (IOException io) {
      throw invalid();
    }
    return out.toByteArray();
  }

  private static void requireExactFields(JsonNode node, Set<String> allowed) throws Rejected {
    Set<String> present = new TreeSet<>();
    Iterator<String> names = node.fieldNames();
    names.forEachRemaining(present::add);
    if (!present.equals(allowed)) {
      throw invalid();
    }
  }

  private static String text(JsonNode node, String field) throws Rejected {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual()) {
      throw invalid();
    }
    return value.textValue();
  }

  private static Rejected invalid() {
    return new Rejected("INVALID_REQUEST", 400);
  }
}
