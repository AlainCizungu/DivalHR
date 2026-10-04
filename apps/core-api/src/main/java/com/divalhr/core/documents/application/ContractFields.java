package com.divalhr.core.documents.application;

import com.divalhr.core.documents.api.StrictRequest;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Strict parsing of MVP-030 request values (raw JSON values until here). Problems are {@code
 * {field, constraint}} pairs from the fixed vocabulary; submitted values are never echoed or
 * logged.
 */
final class ContractFields {

  static final Set<String> TYPES =
      Set.of("PERMANENT", "FIXED_TERM", "APPRENTICESHIP", "INTERNSHIP", "DAILY");
  static final Set<String> LOCALES = Set.of("fr", "en");
  static final Set<String> VOID_REASONS =
      Set.of("ISSUED_IN_ERROR", "WRONG_TEMPLATE", "WRONG_DATA", "OTHER");
  static final String TEXT_VERIFIED = "TEXT_VERIFIED";

  private static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,31}$");
  private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");
  private static final Pattern UUID_TEXT =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  private static final DateTimeFormatter ISO =
      DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
  private static final LocalDate MIN = LocalDate.of(1900, 1, 1);
  private static final LocalDate MAX = LocalDate.of(2999, 12, 31);

  private ContractFields() {}

  /**
   * Requires a body and rejects properties outside the contract.
   *
   * @return whether a body was present
   */
  static boolean body(StrictRequest request, FieldErrors errors) {
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      return false;
    }
    for (String unknown : request.unknownProperties()) {
      errors.add(unknown, Constraint.UNKNOWN_PROPERTY);
    }
    return true;
  }

  /** A string of 1..max code points (min..max when given); never trimmed. */
  static String text(Object raw, String field, int min, int max, FieldErrors errors) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof String text)) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    int length = text.codePointCount(0, text.length());
    if (length < min || length > max) {
      errors.add(field, Constraint.LENGTH);
      return null;
    }
    return text;
  }

  /** A template code. */
  static String code(Object raw, String field, FieldErrors errors) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof String text) || !CODE.matcher(text).matches()) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    return text;
  }

  /** One of a closed set of codes. */
  static String oneOf(Object raw, String field, Set<String> allowed, FieldErrors errors) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof String text) || !allowed.contains(text)) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    return text;
  }

  /** A non-negative row or employment version. */
  static Long version(Object raw, String field, FieldErrors errors) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof Integer || raw instanceof Long)) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    long value = ((Number) raw).longValue();
    if (value < 0) {
      errors.add(field, Constraint.RANGE);
      return null;
    }
    return value;
  }

  /** An integer that must equal the server's pinned value (A30-4: no downgrade, no upgrade). */
  static boolean pinned(Object raw, String field, int expected, FieldErrors errors) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return false;
    }
    if (!(raw instanceof Integer || raw instanceof Long)) {
      errors.add(field, Constraint.FORMAT);
      return false;
    }
    if (((Number) raw).longValue() != expected) {
      errors.add(field, Constraint.RANGE);
      return false;
    }
    return true;
  }

  /** A lowercase hex SHA-256 digest. */
  static String digest(Object raw, String field, FieldErrors errors) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof String text) || !DIGEST.matcher(text).matches()) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    return text;
  }

  /** A UUID. */
  static UUID uuid(Object raw, String field, FieldErrors errors) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof String text) || !UUID_TEXT.matcher(text).matches()) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    return UUID.fromString(text);
  }

  /** An ISO date; {@code null} allowed when {@code optional}. */
  static LocalDate date(Object raw, String field, boolean optional, FieldErrors errors) {
    if (raw == null) {
      if (!optional) {
        errors.add(field, Constraint.REQUIRED);
      }
      return null;
    }
    if (!(raw instanceof String text)) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    LocalDate date;
    try {
      date = LocalDate.parse(text, ISO);
    } catch (DateTimeParseException malformed) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    if (date.isBefore(MIN) || date.isAfter(MAX)) {
      errors.add(field, Constraint.RANGE);
      return null;
    }
    return date;
  }

  /**
   * {@code ["TEXT_VERIFIED"]}: true; an empty list: false (the business answer is {@code 422
   * CONTRACT_TEMPLATE_ACKNOWLEDGEMENT_REQUIRED}); anything else is a field problem.
   */
  static boolean textVerified(Object raw, String field, FieldErrors errors) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return false;
    }
    if (!(raw instanceof List<?> list) || list.size() > 1) {
      errors.add(field, Constraint.FORMAT);
      return false;
    }
    if (list.isEmpty()) {
      return false;
    }
    if (!TEXT_VERIFIED.equals(list.get(0))) {
      errors.add(field, Constraint.FORMAT);
      return false;
    }
    return true;
  }

  /** A path ID; anything malformed is the caller's not-found. */
  static UUID pathId(String raw) {
    return raw != null && UUID_TEXT.matcher(raw).matches() ? UUID.fromString(raw) : null;
  }

  /** A page limit (1–50, default 25). */
  static int limit(String raw, FieldErrors errors) {
    if (raw == null) {
      return 25;
    }
    if (!raw.matches("^[0-9]{1,3}$")) {
      errors.add("limit", Constraint.FORMAT);
      return 25;
    }
    int value = Integer.parseInt(raw);
    if (value < 1 || value > 50) {
      errors.add("limit", Constraint.RANGE);
      return 25;
    }
    return value;
  }
}
