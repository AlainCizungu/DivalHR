package com.divalhr.core.integrations.partner.privacy;

import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Strict parsing helpers of the LAUNCH-001 privacy API. A refusal names only our field and a fixed
 * constraint; a submitted value is never echoed or logged.
 */
final class PrivacyCommands {

  /** Crockford base-32 alphabet of the opaque references. */
  private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

  private static final SecureRandom RANDOM = new SecureRandom();

  /** Reference patterns, one prefix per resource. */
  static final Pattern POLICY = Pattern.compile("^PL-[0-9A-HJKMNP-TV-Z]{16}$");

  static final Pattern REQUEST = Pattern.compile("^PQ-[0-9A-HJKMNP-TV-Z]{16}$");
  static final Pattern HOLD = Pattern.compile("^PH-[0-9A-HJKMNP-TV-Z]{16}$");
  static final Pattern PREVIEW = Pattern.compile("^PV-[0-9A-HJKMNP-TV-Z]{16}$");
  static final Pattern REFERRAL = Pattern.compile("^PR-[0-9A-HJKMNP-TV-Z]{16}$");
  static final Pattern DIGITS = Pattern.compile("^[0-9]{1,3}$");

  private PrivacyCommands() {}

  static String newReference(String prefix) {
    StringBuilder reference = new StringBuilder(prefix);
    for (int i = 0; i < 16; i++) {
      reference.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    }
    return reference.toString();
  }

  static ApiException fail(String field, String constraint) {
    return new ApiException(
        ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(problem(field, constraint))));
  }

  static Map<String, String> problem(String field, String constraint) {
    return Map.of("field", field, "constraint", constraint);
  }

  static void throwIfAny(List<Map<String, String>> problems) {
    if (!problems.isEmpty()) {
      throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.copyOf(problems)));
    }
  }

  static void key(String key) {
    if (key == null || key.isEmpty()) {
      throw fail(IdempotencyKeys.HEADER, "REQUIRED");
    }
    if (!IdempotencyKeys.isWellFormed(key)) {
      throw fail(IdempotencyKeys.HEADER, "FORMAT");
    }
  }

  /** Reads a bounded JSON object from the raw request. */
  static JsonNode read(HttpServletRequest request, JsonMapper json, int maxBytes) {
    if (!isJson(request.getContentType())) {
      throw fail("Content-Type", "FORMAT");
    }
    if (request.getContentLengthLong() > maxBytes) {
      throw fail("body", "LENGTH");
    }
    byte[] raw;
    try {
      raw = request.getInputStream().readNBytes(maxBytes + 1);
    } catch (IOException unreadable) {
      throw fail("body", "FORMAT");
    }
    if (raw.length > maxBytes) {
      throw fail("body", "LENGTH");
    }
    if (raw.length == 0) {
      throw fail("body", "REQUIRED");
    }
    JsonNode tree;
    try {
      tree = json.readTree(raw);
    } catch (RuntimeException malformed) {
      throw fail("body", "FORMAT");
    }
    if (tree == null || !tree.isObject()) {
      throw fail("body", "FORMAT");
    }
    return tree;
  }

  private static boolean isJson(String contentType) {
    if (contentType == null || contentType.isBlank()) {
      return false;
    }
    try {
      MediaType parsed = MediaType.parseMediaType(contentType);
      return parsed.getType().equalsIgnoreCase("application")
          && parsed.getSubtype().equalsIgnoreCase("json");
    } catch (InvalidMediaTypeException invalid) {
      return false;
    }
  }

  /** Records an unknown property once. */
  static void onlyKeys(JsonNode body, Set<String> allowed, List<Map<String, String>> problems) {
    for (String name : body.propertyNames()) {
      if (!allowed.contains(name)) {
        problems.add(problem("body", "UNKNOWN_PROPERTY"));
        return;
      }
    }
  }

  static String string(
      JsonNode body, String field, int min, int max, List<Map<String, String>> problems) {
    JsonNode node = body.get(field);
    if (node == null || node.isNull()) {
      problems.add(problem(field, "REQUIRED"));
      return null;
    }
    if (!node.isString()) {
      problems.add(problem(field, "FORMAT"));
      return null;
    }
    String value = node.asString();
    if (value.length() < min || value.length() > max) {
      problems.add(problem(field, "LENGTH"));
      return null;
    }
    return value;
  }

  static String pattern(
      JsonNode body, String field, Pattern pattern, List<Map<String, String>> problems) {
    String value = string(body, field, 1, 64, problems);
    if (value != null && !pattern.matcher(value).matches()) {
      problems.add(problem(field, "FORMAT"));
      return null;
    }
    return value;
  }

  static Boolean bool(JsonNode body, String field, List<Map<String, String>> problems) {
    JsonNode node = body.get(field);
    if (node == null || node.isNull()) {
      problems.add(problem(field, "REQUIRED"));
      return null;
    }
    if (!node.isBoolean()) {
      problems.add(problem(field, "FORMAT"));
      return null;
    }
    return node.asBoolean();
  }

  static Integer integer(
      JsonNode body, String field, int min, int max, List<Map<String, String>> problems) {
    JsonNode node = body.get(field);
    if (node == null || node.isNull()) {
      problems.add(problem(field, "REQUIRED"));
      return null;
    }
    if (!node.isIntegralNumber() || !node.canConvertToInt()) {
      problems.add(problem(field, "FORMAT"));
      return null;
    }
    int value = node.asInt();
    if (value < min || value > max) {
      problems.add(problem(field, "RANGE"));
      return null;
    }
    return value;
  }

  /** A date that may be an explicit JSON null (allowed only when {@code nullable}). */
  static LocalDate date(
      JsonNode body, String field, boolean nullable, List<Map<String, String>> problems) {
    JsonNode node = body.get(field);
    if (node == null) {
      problems.add(problem(field, "REQUIRED"));
      return null;
    }
    if (node.isNull()) {
      if (!nullable) {
        problems.add(problem(field, "REQUIRED"));
      }
      return null;
    }
    if (!node.isString()) {
      problems.add(problem(field, "FORMAT"));
      return null;
    }
    try {
      return LocalDate.parse(node.asString());
    } catch (DateTimeParseException malformed) {
      problems.add(problem(field, "FORMAT"));
      return null;
    }
  }

  static <E extends Enum<E>> E enumeration(
      JsonNode body, String field, Class<E> type, List<Map<String, String>> problems) {
    String value = string(body, field, 1, 64, problems);
    if (value == null) {
      return null;
    }
    try {
      return Enum.valueOf(type, value);
    } catch (IllegalArgumentException unknown) {
      problems.add(problem(field, "FORMAT"));
      return null;
    }
  }

  static int limit(String raw, int fallback, int max) {
    if (raw == null || raw.isEmpty()) {
      return fallback;
    }
    if (!DIGITS.matcher(raw).matches()) {
      throw fail("limit", "FORMAT");
    }
    int value = Integer.parseInt(raw);
    if (value < 1 || value > max) {
      throw fail("limit", "RANGE");
    }
    return value;
  }

  static List<Map<String, String>> problems() {
    return new ArrayList<>();
  }
}
