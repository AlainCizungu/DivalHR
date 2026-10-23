package com.divalhr.core.integrations.application;

import com.divalhr.core.integrations.api.CreateWebhookRequest;
import com.divalhr.core.integrations.api.TestDeliveryRequest;
import com.divalhr.core.integrations.api.UpdateWebhookRequest;
import com.divalhr.core.integrations.api.WebhookCommandRequest;
import com.divalhr.core.integrations.domain.WebhookEventType;
import com.divalhr.core.integrations.domain.WebhookUrl;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Validation and normalization of webhook commands. Shape problems are reported as one {@code
 * VALIDATION_FAILED} with a field and constraint per problem and never echo the submitted value;
 * the endpoint and the event list have their own stable codes (D110-14).
 */
final class WebhookCommand {

  private static final Pattern NAME_FORBIDDEN = Pattern.compile("[<>\\p{Cntrl}]|(?i:https?://)");

  private WebhookCommand() {}

  /**
   * A normalized configuration.
   *
   * @param name trimmed display name
   * @param url validated endpoint
   * @param events distinct, sorted event codes
   */
  record Configuration(String name, WebhookUrl url, List<String> events) {
    void put(Map<String, Object> canonical) {
      canonical.put("name", name);
      canonical.put("url", url.normalized());
      canonical.put("events", events);
    }
  }

  record Create(Configuration configuration) {
    Map<String, Object> canonical(String tenant) {
      Map<String, Object> c = new TreeMap<>();
      c.put("tenantId", tenant);
      configuration.put(c);
      c.put("confirm", true);
      return c;
    }
  }

  record Update(Configuration configuration, int configVersion) {
    Map<String, Object> canonical(String tenant, java.util.UUID id) {
      Map<String, Object> c = new TreeMap<>();
      c.put("tenantId", tenant);
      c.put("webhookId", id.toString());
      configuration.put(c);
      c.put("configVersion", configVersion);
      c.put("confirm", true);
      return c;
    }
  }

  record Versioned(int configVersion) {
    Map<String, Object> canonical(String tenant, java.util.UUID id, String action) {
      Map<String, Object> c = new TreeMap<>();
      c.put("tenantId", tenant);
      c.put("webhookId", id.toString());
      c.put("action", action);
      c.put("configVersion", configVersion);
      c.put("confirm", true);
      return c;
    }
  }

  static Create create(String key, CreateWebhookRequest body, Set<Integer> ports) {
    FieldErrors errors = new FieldErrors();
    key(key, errors);
    if (body == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
    }
    unknown(body.unknownProperties(), errors);
    confirm(body.getConfirm(), errors);
    String name = name(body.getName(), errors);
    errors.throwIfAny();
    return new Create(configuration(name, body.getUrl(), body.getEventTypes(), ports));
  }

  static Update update(String key, UpdateWebhookRequest body, Set<Integer> ports) {
    FieldErrors errors = new FieldErrors();
    key(key, errors);
    if (body == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
    }
    unknown(body.unknownProperties(), errors);
    confirm(body.getConfirm(), errors);
    int version = version(body.getConfigVersion(), errors);
    String name = name(body.getName(), errors);
    errors.throwIfAny();
    return new Update(configuration(name, body.getUrl(), body.getEventTypes(), ports), version);
  }

  static Versioned versioned(String key, WebhookCommandRequest body) {
    FieldErrors errors = new FieldErrors();
    key(key, errors);
    if (body == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
    }
    unknown(body.unknownProperties(), errors);
    confirm(body.getConfirm(), errors);
    int version = version(body.getConfigVersion(), errors);
    errors.throwIfAny();
    return new Versioned(version);
  }

  static void test(String key, TestDeliveryRequest body) {
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

  private static int version(Object value, FieldErrors errors) {
    if (value == null) {
      errors.add("configVersion", Constraint.REQUIRED);
      return 0;
    }
    if (value instanceof Integer i && i >= 1) {
      return i;
    }
    errors.add("configVersion", Constraint.FORMAT);
    return 0;
  }

  private static String name(Object value, FieldErrors errors) {
    if (value == null) {
      errors.add("name", Constraint.REQUIRED);
      return null;
    }
    if (!(value instanceof String raw)) {
      errors.add("name", Constraint.FORMAT);
      return null;
    }
    String trimmed = raw.strip();
    if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > 100) {
      errors.add("name", Constraint.LENGTH);
      return null;
    }
    if (NAME_FORBIDDEN.matcher(trimmed).find()) {
      errors.add("name", Constraint.FORMAT);
      return null;
    }
    return trimmed;
  }

  private static Configuration configuration(
      String name, Object url, Object events, Set<Integer> ports) {
    List<String> normalizedEvents = events(events);
    if (!(url instanceof String raw)) {
      throw new ApiException(ErrorCode.WEBHOOK_URL_INVALID, Map.of());
    }
    WebhookUrl parsed =
        WebhookUrl.parse(raw, ports)
            .orElseThrow(() -> new ApiException(ErrorCode.WEBHOOK_URL_INVALID, Map.of()));
    return new Configuration(name, parsed, normalizedEvents);
  }

  private static List<String> events(Object value) {
    if (!(value instanceof List<?> list) || list.isEmpty() || list.size() > 6) {
      throw new ApiException(ErrorCode.WEBHOOK_EVENT_TYPE_INVALID, Map.of());
    }
    Set<String> chosen = new TreeSet<>();
    Set<String> allowed = WebhookEventType.subscribableCodes();
    for (Object item : list) {
      if (!(item instanceof String code) || !allowed.contains(code) || !chosen.add(code)) {
        throw new ApiException(ErrorCode.WEBHOOK_EVENT_TYPE_INVALID, Map.of());
      }
    }
    return new ArrayList<>(chosen);
  }
}
