package com.divalhr.core.integrations.messaging.application;

import com.divalhr.core.integrations.messaging.api.ConnectorCommandRequest;
import com.divalhr.core.integrations.messaging.api.CreateConnectorRequest;
import com.divalhr.core.integrations.messaging.api.CreateRecipientRequest;
import com.divalhr.core.integrations.messaging.api.MessagingTestRequest;
import com.divalhr.core.integrations.messaging.api.RecipientCommandRequest;
import com.divalhr.core.integrations.messaging.api.RotateTokenRequest;
import com.divalhr.core.integrations.messaging.api.UpdateConnectorRequest;
import com.divalhr.core.integrations.messaging.api.UpdateRecipientRequest;
import com.divalhr.core.integrations.messaging.domain.ConsentSource;
import com.divalhr.core.integrations.messaging.domain.MessagingLanguage;
import com.divalhr.core.integrations.messaging.domain.NotificationType;
import com.divalhr.core.integrations.messaging.domain.PhoneNumber;
import com.divalhr.core.integrations.messaging.domain.TemplateNames;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Validation and normalization of messaging commands. Shape problems are reported as one {@code
 * VALIDATION_FAILED} with a field and constraint per problem and never echo the submitted value;
 * numbers, notification types and consent have their own stable codes (D111-15). Canonical forms
 * for idempotency carry secrets and numbers only as keyed digests, never in clear.
 */
final class MessagingCommand {

  private static final Pattern DIGITS = Pattern.compile("^[0-9]{5,20}$");
  private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9._~+/=-]{20,1024}$");
  private static final Pattern LABEL_FORBIDDEN = Pattern.compile("[<>\\p{Cntrl}]|(?i:https?://)");
  private static final Instant EARLIEST_CONSENT = Instant.parse("2000-01-01T00:00:00Z");
  private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

  private MessagingCommand() {}

  /**
   * A template name chosen for one notification type and language.
   *
   * @param type notification type (including the connector test)
   * @param language language
   * @param name Meta template name
   */
  record TemplateInput(NotificationType type, MessagingLanguage language, String name) {}

  /**
   * The administrator's consent attestation.
   *
   * @param source how the opt-in was obtained
   * @param confirmedAt when it was confirmed
   */
  record Consent(ConsentSource source, Instant confirmedAt) {}

  record CreateConnector(
      String wabaId,
      String phoneNumberId,
      PhoneNumber displayNumber,
      String token,
      List<TemplateInput> templates) {
    Map<String, Object> canonical(String tenant, Function<String, String> digest) {
      Map<String, Object> c = new TreeMap<>();
      c.put("tenantId", tenant);
      c.put("wabaId", wabaId);
      c.put("phoneNumberId", phoneNumberId);
      c.put("displayNumberDigest", digest.apply(displayNumber.e164()));
      c.put("tokenDigest", digest.apply(token));
      c.put("templates", templateCanonical(templates));
      c.put("confirm", true);
      return c;
    }
  }

  record UpdateConnector(
      int configVersion,
      String wabaId,
      String phoneNumberId,
      PhoneNumber displayNumber,
      List<TemplateInput> templates) {
    Map<String, Object> canonical(String tenant, Function<String, String> digest) {
      Map<String, Object> c = new TreeMap<>();
      c.put("tenantId", tenant);
      c.put("configVersion", configVersion);
      c.put("wabaId", wabaId);
      c.put("phoneNumberId", phoneNumberId);
      c.put(
          "displayNumberDigest", displayNumber == null ? null : digest.apply(displayNumber.e164()));
      c.put("templates", templateCanonical(templates));
      c.put("confirm", true);
      return c;
    }
  }

  record Versioned(int version) {
    Map<String, Object> canonical(String tenant, String subject, String action) {
      Map<String, Object> c = new TreeMap<>();
      c.put("tenantId", tenant);
      c.put("target", subject);
      c.put("action", action);
      c.put("version", version);
      c.put("confirm", true);
      return c;
    }
  }

  record Rotate(int configVersion, String token) {
    Map<String, Object> canonical(String tenant, Function<String, String> digest) {
      Map<String, Object> c = new TreeMap<>();
      c.put("tenantId", tenant);
      c.put("action", "rotate-token");
      c.put("configVersion", configVersion);
      c.put("tokenDigest", digest.apply(token));
      c.put("confirm", true);
      return c;
    }
  }

  record CreateRecipient(
      String label,
      PhoneNumber number,
      MessagingLanguage language,
      List<NotificationType> types,
      Consent consent) {
    Map<String, Object> canonical(String tenant, Function<String, String> digest) {
      Map<String, Object> c = new TreeMap<>();
      c.put("tenantId", tenant);
      c.put("label", label);
      c.put("numberDigest", digest.apply(number.e164()));
      c.put("language", language.code());
      c.put("types", types.stream().map(Enum::name).toList());
      c.put("consentSource", consent.source().name());
      c.put("consentConfirmedAt", consent.confirmedAt().toString());
      c.put("confirm", true);
      return c;
    }
  }

  record UpdateRecipient(
      int version,
      String label,
      MessagingLanguage language,
      List<NotificationType> types,
      Consent consent) {
    Map<String, Object> canonical(String tenant, UUID recipient) {
      Map<String, Object> c = new TreeMap<>();
      c.put("tenantId", tenant);
      c.put("recipientId", recipient.toString());
      c.put("version", version);
      c.put("label", label);
      c.put("language", language.code());
      c.put("types", types.stream().map(Enum::name).toList());
      c.put("consentSource", consent == null ? null : consent.source().name());
      c.put("consentConfirmedAt", consent == null ? null : consent.confirmedAt().toString());
      c.put("confirm", true);
      return c;
    }
  }

  private static List<Map<String, String>> templateCanonical(List<TemplateInput> templates) {
    List<Map<String, String>> out = new ArrayList<>();
    for (TemplateInput t : templates) {
      Map<String, String> m = new TreeMap<>();
      m.put("type", t.type().name());
      m.put("language", t.language().code());
      m.put("name", t.name());
      out.add(m);
    }
    return out;
  }

  // ------------------------------------------------------------------------------------------
  // Parsers
  // ------------------------------------------------------------------------------------------

  static CreateConnector createConnector(String key, CreateConnectorRequest body) {
    FieldErrors errors = new FieldErrors();
    key(key, errors);
    if (body == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
    }
    unknown(body.unknownProperties(), errors);
    confirm(body.getConfirm(), errors);
    String waba = digits("wabaId", body.getWabaId(), errors);
    String phoneId = digits("phoneNumberId", body.getPhoneNumberId(), errors);
    PhoneNumber display = displayNumber(body.getDisplayNumber(), errors);
    String token = token(body.getAccessToken(), errors);
    List<TemplateInput> templates = templates(body.getTemplates(), true, errors);
    errors.throwIfAny();
    return new CreateConnector(waba, phoneId, display, token, templates);
  }

  static UpdateConnector updateConnector(String key, UpdateConnectorRequest body) {
    FieldErrors errors = new FieldErrors();
    key(key, errors);
    if (body == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
    }
    unknown(body.unknownProperties(), errors);
    confirm(body.getConfirm(), errors);
    int version = version("configVersion", body.getConfigVersion(), errors);
    String waba = body.getWabaId() == null ? null : digits("wabaId", body.getWabaId(), errors);
    String phoneId =
        body.getPhoneNumberId() == null
            ? null
            : digits("phoneNumberId", body.getPhoneNumberId(), errors);
    PhoneNumber display =
        body.getDisplayNumber() == null ? null : displayNumber(body.getDisplayNumber(), errors);
    List<TemplateInput> templates = templates(body.getTemplates(), false, errors);
    errors.throwIfAny();
    return new UpdateConnector(version, waba, phoneId, display, templates);
  }

  static Versioned connectorCommand(String key, ConnectorCommandRequest body) {
    FieldErrors errors = new FieldErrors();
    key(key, errors);
    if (body == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
    }
    unknown(body.unknownProperties(), errors);
    confirm(body.getConfirm(), errors);
    int version = version("configVersion", body.getConfigVersion(), errors);
    errors.throwIfAny();
    return new Versioned(version);
  }

  static Rotate rotate(String key, RotateTokenRequest body) {
    FieldErrors errors = new FieldErrors();
    key(key, errors);
    if (body == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
    }
    unknown(body.unknownProperties(), errors);
    confirm(body.getConfirm(), errors);
    int version = version("configVersion", body.getConfigVersion(), errors);
    String token = token(body.getAccessToken(), errors);
    errors.throwIfAny();
    return new Rotate(version, token);
  }

  static CreateRecipient createRecipient(String key, CreateRecipientRequest body, Instant now) {
    FieldErrors errors = new FieldErrors();
    key(key, errors);
    if (body == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
    }
    unknown(body.unknownProperties(), errors);
    confirm(body.getConfirm(), errors);
    String label = label(body.getLabel(), errors);
    MessagingLanguage language = language(body.getLanguage(), errors);
    errors.throwIfAny();
    PhoneNumber number =
        PhoneNumber.parse(body.getPhoneNumber())
            .orElseThrow(() -> new ApiException(ErrorCode.MESSAGING_PHONE_INVALID, Map.of()));
    List<NotificationType> types = types(body.getNotificationTypes());
    Consent consent = consent(body.getConsent(), now, true);
    return new CreateRecipient(label, number, language, types, consent);
  }

  static UpdateRecipient updateRecipient(String key, UpdateRecipientRequest body, Instant now) {
    FieldErrors errors = new FieldErrors();
    key(key, errors);
    if (body == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
    }
    unknown(body.unknownProperties(), errors);
    confirm(body.getConfirm(), errors);
    int version = version("version", body.getVersion(), errors);
    String label = label(body.getLabel(), errors);
    MessagingLanguage language = language(body.getLanguage(), errors);
    errors.throwIfAny();
    List<NotificationType> types = types(body.getNotificationTypes());
    Consent consent = body.getConsent() == null ? null : consent(body.getConsent(), now, false);
    return new UpdateRecipient(version, label, language, types, consent);
  }

  static Versioned recipientCommand(String key, RecipientCommandRequest body) {
    FieldErrors errors = new FieldErrors();
    key(key, errors);
    if (body == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
    }
    unknown(body.unknownProperties(), errors);
    confirm(body.getConfirm(), errors);
    int version = version("version", body.getVersion(), errors);
    errors.throwIfAny();
    return new Versioned(version);
  }

  static void test(String key, MessagingTestRequest body) {
    FieldErrors errors = new FieldErrors();
    key(key, errors);
    if (body == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
    }
    unknown(body.unknownProperties(), errors);
    confirm(body.getConfirm(), errors);
    errors.throwIfAny();
  }

  // ------------------------------------------------------------------------------------------
  // Field rules
  // ------------------------------------------------------------------------------------------

  private static void key(String key, FieldErrors errors) {
    if (key == null || key.isBlank()) {
      errors.add(IdempotencyKeys.HEADER, Constraint.REQUIRED);
    } else if (!IdempotencyKeys.isWellFormed(key)) {
      errors.add(IdempotencyKeys.HEADER, Constraint.FORMAT);
    }
  }

  private static void unknown(Set<String> unknown, FieldErrors errors) {
    if (!unknown.isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
  }

  private static void confirm(Object value, FieldErrors errors) {
    if (!Boolean.TRUE.equals(value)) {
      errors.add("confirm", value == null ? Constraint.REQUIRED : Constraint.FORMAT);
    }
  }

  private static int version(String field, Object value, FieldErrors errors) {
    if (value == null) {
      errors.add(field, Constraint.REQUIRED);
      return 0;
    }
    if (value instanceof Integer i && i >= 1) {
      return i;
    }
    errors.add(field, Constraint.FORMAT);
    return 0;
  }

  private static String digits(String field, Object value, FieldErrors errors) {
    if (value == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (!(value instanceof String text) || !DIGITS.matcher(text).matches()) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    return text;
  }

  private static PhoneNumber displayNumber(Object value, FieldErrors errors) {
    if (value == null) {
      errors.add("displayNumber", Constraint.REQUIRED);
      return null;
    }
    return PhoneNumber.parse(value)
        .orElseGet(
            () -> {
              errors.add("displayNumber", Constraint.FORMAT);
              return null;
            });
  }

  private static String token(Object value, FieldErrors errors) {
    if (value == null) {
      errors.add("accessToken", Constraint.REQUIRED);
      return null;
    }
    if (!(value instanceof String text) || !TOKEN.matcher(text).matches()) {
      errors.add("accessToken", Constraint.FORMAT);
      return null;
    }
    return text;
  }

  private static String label(Object value, FieldErrors errors) {
    if (value == null) {
      errors.add("label", Constraint.REQUIRED);
      return null;
    }
    if (!(value instanceof String raw)) {
      errors.add("label", Constraint.FORMAT);
      return null;
    }
    String trimmed = raw.strip();
    if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > 80) {
      errors.add("label", Constraint.LENGTH);
      return null;
    }
    if (LABEL_FORBIDDEN.matcher(trimmed).find()) {
      errors.add("label", Constraint.FORMAT);
      return null;
    }
    return trimmed;
  }

  private static MessagingLanguage language(Object value, FieldErrors errors) {
    if (value == null) {
      errors.add("language", Constraint.REQUIRED);
      return null;
    }
    return MessagingLanguage.of(value)
        .orElseGet(
            () -> {
              errors.add("language", Constraint.FORMAT);
              return null;
            });
  }

  private static List<TemplateInput> templates(Object value, boolean required, FieldErrors errors) {
    if (value == null) {
      if (required) {
        errors.add("templates", Constraint.REQUIRED);
      }
      return List.of();
    }
    if (!(value instanceof List<?> list) || list.size() > 12) {
      errors.add("templates", Constraint.FORMAT);
      return List.of();
    }
    List<TemplateInput> out = new ArrayList<>();
    Set<String> seen = new TreeSet<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> map)
          || map.size() != 3
          || !map.keySet().containsAll(Set.of("notificationType", "language", "templateName"))) {
        errors.add("templates", Constraint.FORMAT);
        return List.of();
      }
      NotificationType type =
          map.get("notificationType") instanceof String code
              ? NotificationType.of(code).orElse(null)
              : null;
      MessagingLanguage language = MessagingLanguage.of(map.get("language")).orElse(null);
      Object name = map.get("templateName");
      if (type == null
          || language == null
          || !TemplateNames.valid(name)
          || !seen.add(type.name() + "/" + language.code())) {
        errors.add("templates", Constraint.FORMAT);
        return List.of();
      }
      out.add(new TemplateInput(type, language, (String) name));
    }
    return out;
  }

  private static List<NotificationType> types(Object value) {
    if (!(value instanceof List<?> list) || list.isEmpty() || list.size() > 5) {
      throw new ApiException(ErrorCode.MESSAGING_NOTIFICATION_TYPE_INVALID, Map.of());
    }
    Set<NotificationType> chosen = new TreeSet<>();
    for (Object item : list) {
      NotificationType type =
          item instanceof String code ? NotificationType.of(code).orElse(null) : null;
      if (type == null || !type.selectable() || !chosen.add(type)) {
        throw new ApiException(ErrorCode.MESSAGING_NOTIFICATION_TYPE_INVALID, Map.of());
      }
    }
    return new ArrayList<>(chosen);
  }

  /** Any gap in the attestation is the same stable code (D111-5, D111-15). */
  private static Consent consent(Object value, Instant now, boolean required) {
    ApiException missing = new ApiException(ErrorCode.MESSAGING_CONSENT_REQUIRED, Map.of());
    if (value == null) {
      if (required) {
        throw missing;
      }
      return null;
    }
    if (!(value instanceof Map<?, ?> map)
        || map.size() != 3
        || !map.keySet().containsAll(Set.of("source", "confirmedAt", "attested"))
        || !Boolean.TRUE.equals(map.get("attested"))) {
      throw missing;
    }
    ConsentSource source = ConsentSource.of(map.get("source")).orElseThrow(() -> missing);
    Instant confirmedAt;
    try {
      confirmedAt = Instant.parse(map.get("confirmedAt") instanceof String s ? s : "invalid");
    } catch (DateTimeParseException malformed) {
      throw missing;
    }
    if (confirmedAt.isBefore(EARLIEST_CONSENT) || confirmedAt.isAfter(now.plus(CLOCK_SKEW))) {
      throw missing;
    }
    return new Consent(source, confirmedAt);
  }
}
