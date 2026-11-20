package com.divalhr.core.integrations.partner;

import com.divalhr.core.integrations.partner.PartnerDomain.ApiAvailability;
import com.divalhr.core.integrations.partner.PartnerDomain.Capability;
import com.divalhr.core.integrations.partner.PartnerDomain.Category;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Strict validation of a partner-application body (D120D-6, D120D-7). Unknown properties, wrong
 * types, values outside the closed catalogues and over-long text are refused with field names and
 * constraint codes only: a value is never echoed. The honeypot is handled before this runs.
 */
final class PartnerSubmissionParser {

  /** The only accepted properties (the hidden honeypot included). */
  static final Set<String> PROPERTIES =
      Set.of(
          "legalName",
          "tradingName",
          "countryOfRegistration",
          "countriesOfOperation",
          "registrationReference",
          "primaryCategory",
          "additionalCategories",
          "requestedCapabilities",
          "website",
          "contactName",
          "contactRole",
          "businessEmail",
          "businessPhone",
          "supportedLanguages",
          "apiAvailability",
          "regulatorName",
          "licenceReference",
          "serviceSummary",
          "privacyContactEmail",
          "acknowledgment",
          "privacyNoticeVersion",
          "consentVersion",
          "locale",
          "faxNumber");

  private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");
  private static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9._-]{1,40}$");
  private static final Pattern PHONE = Pattern.compile("^\\+[1-9][0-9]{6,14}$");
  private static final Pattern EMAIL =
      Pattern.compile("^[A-Za-z0-9._%+'-]{1,64}@[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$");
  private static final Set<String> LANGUAGES = Set.of("en", "fr");

  private final List<Map<String, String>> fields = new ArrayList<>();
  private boolean acknowledged;

  private PartnerSubmissionParser() {}

  /**
   * Validates a parsed JSON object.
   *
   * @param root the parsed body
   * @return the validated submission
   * @throws ApiException {@link ErrorCode#PARTNER_APPLICATION_INVALID} or {@link
   *     ErrorCode#PARTNER_APPLICATION_CONSENT_REQUIRED}
   */
  static PartnerSubmission parse(JsonNode root) {
    return new PartnerSubmissionParser().run(root);
  }

  private PartnerSubmission run(JsonNode root) {
    if (root == null || !root.isObject()) {
      fail("body", Constraint.FORMAT);
    }
    for (String name : root.propertyNames()) {
      if (!PROPERTIES.contains(name)) {
        fields.add(Map.of("field", "body", "constraint", Constraint.UNKNOWN_PROPERTY.name()));
        break;
      }
    }
    String legalName = text(root, "legalName", 2, 200, true, false);
    String tradingName = text(root, "tradingName", 2, 200, false, false);
    String country = pattern(root, "countryOfRegistration", COUNTRY, true);
    List<String> operation = strings(root, "countriesOfOperation", 1, 30, COUNTRY, true);
    String registration = text(root, "registrationReference", 2, 100, true, false);
    Category primary = enumValue(root, "primaryCategory", Category.class, true);
    List<Category> additional =
        enums(root, "additionalCategories", Category.class, 0, 3, false);
    List<Capability> capabilities =
        enums(root, "requestedCapabilities", Capability.class, 1, 12, true);
    if (primary != null && additional.contains(primary)) {
      add("additionalCategories", Constraint.DUPLICATE);
    }
    String website = website(root);
    String contactName = text(root, "contactName", 2, 150, true, false);
    String contactRole = text(root, "contactRole", 2, 120, true, false);
    String email = email(root, "businessEmail", true);
    String phone = phone(root);
    List<String> languages = languages(root);
    ApiAvailability api = enumValue(root, "apiAvailability", ApiAvailability.class, true);
    boolean regulated = primary != null && primary.regulated();
    String regulator = text(root, "regulatorName", 2, 200, regulated, false);
    String licence = text(root, "licenceReference", 2, 100, regulated, false);
    String summary = text(root, "serviceSummary", 20, 1000, true, true);
    String privacyEmail = email(root, "privacyContactEmail", true);
    String notice = pattern(root, "privacyNoticeVersion", VERSION, true);
    String consent = pattern(root, "consentVersion", VERSION, true);
    String locale = language(root, "locale");
    acknowledgment(root);
    if (!fields.isEmpty()) {
      throw new ApiException(
          ErrorCode.PARTNER_APPLICATION_INVALID, Map.of("fields", List.copyOf(fields)));
    }
    if (!acknowledged) {
      throw new ApiException(ErrorCode.PARTNER_APPLICATION_CONSENT_REQUIRED, Map.of());
    }
    return new PartnerSubmission(
        legalName,
        tradingName,
        country,
        operation,
        registration,
        primary,
        additional,
        capabilities,
        website,
        contactName,
        contactRole,
        email,
        phone,
        languages,
        api,
        regulator,
        licence,
        summary,
        privacyEmail,
        notice,
        consent,
        locale);
  }

  private void fail(String field, Constraint constraint) {
    add(field, constraint);
    throw new ApiException(
        ErrorCode.PARTNER_APPLICATION_INVALID, Map.of("fields", List.copyOf(fields)));
  }

  private void add(String field, Constraint constraint) {
    fields.add(Map.of("field", field, "constraint", constraint.name()));
  }

  private String text(
      JsonNode root, String name, int min, int max, boolean required, boolean multiline) {
    JsonNode node = root.get(name);
    if (node == null || node.isNull()) {
      if (required) {
        add(name, Constraint.REQUIRED);
      }
      return null;
    }
    if (!node.isString()) {
      add(name, Constraint.FORMAT);
      return null;
    }
    String value = node.asString().strip();
    if (value.isEmpty() && !required) {
      return null;
    }
    if (value.length() < min || value.length() > max) {
      add(name, Constraint.LENGTH);
      return null;
    }
    if (hasControl(value, multiline)) {
      add(name, Constraint.FORMAT);
      return null;
    }
    return value;
  }

  private static boolean hasControl(String value, boolean multiline) {
    return value
        .chars()
        .anyMatch(
            c ->
                Character.isISOControl(c)
                    && !(multiline && (c == '\n' || c == '\r' || c == '\t')));
  }

  private String pattern(JsonNode root, String name, Pattern pattern, boolean required) {
    JsonNode node = root.get(name);
    if (node == null || node.isNull()) {
      if (required) {
        add(name, Constraint.REQUIRED);
      }
      return null;
    }
    if (!node.isString() || !pattern.matcher(node.asString()).matches()) {
      add(name, Constraint.FORMAT);
      return null;
    }
    return node.asString();
  }

  private List<String> strings(
      JsonNode root, String name, int min, int max, Pattern pattern, boolean required) {
    JsonNode node = root.get(name);
    if (node == null || node.isNull()) {
      if (required) {
        add(name, Constraint.REQUIRED);
      }
      return List.of();
    }
    if (!node.isArray() || node.size() < min || node.size() > max) {
      add(name, node.isArray() ? Constraint.LENGTH : Constraint.FORMAT);
      return List.of();
    }
    Set<String> values = new LinkedHashSet<>();
    for (JsonNode item : node) {
      if (!item.isString() || !pattern.matcher(item.asString()).matches()) {
        add(name, Constraint.FORMAT);
        return List.of();
      }
      if (!values.add(item.asString())) {
        add(name, Constraint.DUPLICATE);
        return List.of();
      }
    }
    return List.copyOf(values);
  }

  private <E extends Enum<E>> E enumValue(
      JsonNode root, String name, Class<E> type, boolean required) {
    JsonNode node = root.get(name);
    if (node == null || node.isNull()) {
      if (required) {
        add(name, Constraint.REQUIRED);
      }
      return null;
    }
    E value = node.isString() ? PartnerDomain.parse(type, node.asString()) : null;
    if (value == null) {
      add(name, Constraint.FORMAT);
    }
    return value;
  }

  private <E extends Enum<E>> List<E> enums(
      JsonNode root, String name, Class<E> type, int min, int max, boolean required) {
    JsonNode node = root.get(name);
    if (node == null || node.isNull()) {
      if (required) {
        add(name, Constraint.REQUIRED);
      }
      return List.of();
    }
    if (!node.isArray() || node.size() < min || node.size() > max) {
      add(name, node.isArray() ? Constraint.LENGTH : Constraint.FORMAT);
      return List.of();
    }
    List<E> values = new ArrayList<>();
    for (JsonNode item : node) {
      E value = item.isString() ? PartnerDomain.parse(type, item.asString()) : null;
      if (value == null) {
        add(name, Constraint.FORMAT);
        return List.of();
      }
      if (values.contains(value)) {
        add(name, Constraint.DUPLICATE);
        return List.of();
      }
      values.add(value);
    }
    return values;
  }

  private String website(JsonNode root) {
    String value = text(root, "website", 10, 300, true, false);
    if (value == null) {
      return null;
    }
    try {
      URI uri = new URI(value);
      if (!"https".equals(uri.getScheme())
          || uri.getHost() == null
          || value.chars().anyMatch(Character::isWhitespace)
          || uri.getUserInfo() != null) {
        add("website", Constraint.FORMAT);
        return null;
      }
    } catch (URISyntaxException malformed) {
      add("website", Constraint.FORMAT);
      return null;
    }
    return value;
  }

  private String email(JsonNode root, String name, boolean required) {
    String value = text(root, name, 3, 254, required, false);
    if (value != null && !EMAIL.matcher(value).matches()) {
      add(name, Constraint.FORMAT);
      return null;
    }
    return value;
  }

  private String phone(JsonNode root) {
    JsonNode node = root.get("businessPhone");
    if (node == null || node.isNull()) {
      return null;
    }
    String value = node.isString() ? node.asString().strip() : null;
    if (value == null || (!value.isEmpty() && !PHONE.matcher(value).matches())) {
      add("businessPhone", Constraint.FORMAT);
      return null;
    }
    return value.isEmpty() ? null : value;
  }

  private List<String> languages(JsonNode root) {
    JsonNode node = root.get("supportedLanguages");
    if (node == null || node.isNull()) {
      add("supportedLanguages", Constraint.REQUIRED);
      return List.of();
    }
    if (!node.isArray() || node.size() < 1 || node.size() > 2) {
      add("supportedLanguages", node.isArray() ? Constraint.LENGTH : Constraint.FORMAT);
      return List.of();
    }
    Set<String> values = new LinkedHashSet<>();
    for (JsonNode item : node) {
      if (!item.isString() || !LANGUAGES.contains(item.asString())) {
        add("supportedLanguages", Constraint.FORMAT);
        return List.of();
      }
      if (!values.add(item.asString())) {
        add("supportedLanguages", Constraint.DUPLICATE);
        return List.of();
      }
    }
    return List.copyOf(values);
  }

  private String language(JsonNode root, String name) {
    JsonNode node = root.get(name);
    if (node == null || node.isNull()) {
      add(name, Constraint.REQUIRED);
      return null;
    }
    if (!node.isString() || !LANGUAGES.contains(node.asString())) {
      add(name, Constraint.FORMAT);
      return null;
    }
    return node.asString();
  }

  private void acknowledgment(JsonNode root) {
    JsonNode node = root.get("acknowledgment");
    if (node == null || node.isNull()) {
      add("acknowledgment", Constraint.REQUIRED);
    } else if (!node.isBoolean()) {
      add("acknowledgment", Constraint.FORMAT);
    } else {
      acknowledged = node.booleanValue();
    }
  }
}
