package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.CommitPayrollPolicyRequest;
import com.divalhr.core.payroll.api.CommitPayrollRunRequest;
import com.divalhr.core.payroll.api.PayrollPolicyCommandRequest;
import com.divalhr.core.payroll.api.PayrollRunCommandRequest;
import com.divalhr.core.payroll.domain.CompensationCurrency;
import com.divalhr.core.payroll.domain.PayrollPayDay;
import com.divalhr.core.payroll.domain.PayrollPeriod;
import com.divalhr.core.payroll.domain.PayrollProration;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validation of the payroll policy and run requests (MVP-094A): shapes first (one field error per
 * problem, every unknown property named), then the stable domain errors the service raises with the
 * business date. Path identifiers that are malformed are the same safe 404 as unknown ones.
 * Restricted financial data: no record here prints an amount, a number or a digest.
 */
final class PayrollCommands {

  /** Largest page of lines of a run. */
  static final int MAX_LINE_LIMIT = 200;

  /** Default page of lines of a run. */
  static final int DEFAULT_LINE_LIMIT = 50;

  /** Largest page of runs. */
  static final int MAX_RUN_LIMIT = 100;

  /** Default page of runs. */
  static final int DEFAULT_RUN_LIMIT = 25;

  private static final Pattern UUID_SHAPE =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
  private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");
  private static final Pattern LIMIT = Pattern.compile("^[0-9]{1,3}$");

  private PayrollCommands() {}

  /**
   * A well-formed policy command.
   *
   * @param effectiveFrom first day in force
   * @param payDay pay day
   * @param method proration method
   */
  record PolicyDraft(LocalDate effectiveFrom, PayrollPayDay payDay, PayrollProration method) {

    Map<String, Object> canonical(Map<String, Object> into) {
      into.put("effectiveFrom", effectiveFrom.toString());
      into.put("payDayKind", payDay.kind().name());
      into.put("payDay", payDay.day() == null ? null : payDay.day());
      into.put("prorationMethod", method.name());
      return into;
    }
  }

  /**
   * A well-formed policy commit.
   *
   * @param draft the policy
   * @param expectedVersion the policy version the preview showed
   * @param previewDigest the preview digest the preview returned
   * @param expiresAt the expiry the preview returned
   */
  record PolicyCommit(
      PolicyDraft draft, int expectedVersion, String previewDigest, Instant expiresAt) {

    Map<String, Object> canonical(TenantId tenant, UUID legalEntityId) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("legalEntityId", legalEntityId.toString());
      draft.canonical(canonical);
      canonical.put("expectedVersion", expectedVersion);
      canonical.put("previewDigest", previewDigest);
      canonical.put("expiresAt", expiresAt.toString());
      canonical.put("confirmed", true);
      return canonical;
    }
  }

  /**
   * A well-formed run command.
   *
   * @param period the month
   * @param currency the currency
   */
  record RunDraft(PayrollPeriod period, CompensationCurrency currency) {}

  /**
   * A well-formed run commit.
   *
   * @param draft the month and currency
   * @param expectedVersion the chain version the preview showed
   * @param inputDigest the input digest the preview returned
   * @param previewDigest the preview digest the preview returned
   * @param expiresAt the expiry the preview returned
   */
  record RunCommit(
      RunDraft draft,
      int expectedVersion,
      String inputDigest,
      String previewDigest,
      Instant expiresAt) {

    Map<String, Object> canonical(TenantId tenant, UUID legalEntityId) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("legalEntityId", legalEntityId.toString());
      canonical.put("period", draft.period().text());
      canonical.put("currency", draft.currency().name());
      canonical.put("expectedVersion", expectedVersion);
      canonical.put("inputDigest", inputDigest);
      canonical.put("previewDigest", previewDigest);
      canonical.put("expiresAt", expiresAt.toString());
      canonical.put("confirmed", true);
      return canonical;
    }
  }

  // ------------------------------------------------------------------------------------------
  // Identifiers and pages
  // ------------------------------------------------------------------------------------------

  /**
   * A path identifier: malformed is the same answer as unknown.
   *
   * @param raw path value
   * @param notFound the 404 code of the operation
   * @return the identifier
   */
  static UUID id(String raw, ErrorCode notFound) {
    if (raw == null || !UUID_SHAPE.matcher(raw).matches()) {
      throw new ApiException(notFound, Map.of());
    }
    return UUID.fromString(raw.toLowerCase(Locale.ROOT));
  }

  /**
   * Validates a page size.
   *
   * @param raw the raw value, or {@code null}
   * @param fallback the default
   * @param max the largest
   * @param errors collector
   * @return the size
   */
  static int limit(String raw, int fallback, int max, FieldErrors errors) {
    if (raw == null) {
      return fallback;
    }
    if (LIMIT.matcher(raw).matches()) {
      int value = Integer.parseInt(raw);
      if (value >= 1 && value <= max) {
        return value;
      }
    }
    errors.add("limit", Constraint.RANGE);
    return fallback;
  }

  // ------------------------------------------------------------------------------------------
  // Entry points
  // ------------------------------------------------------------------------------------------

  static PolicyDraft policyDraft(PayrollPolicyCommandRequest request) {
    FieldErrors errors = new FieldErrors();
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!request.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    PolicyDraft draft =
        policy(
            request.getEffectiveFrom(), request.getPayDay(), request.getProrationMethod(), errors);
    errors.throwIfAny();
    return draft;
  }

  static PolicyCommit policyCommit(String idempotencyKey, CommitPayrollPolicyRequest request) {
    FieldErrors errors = new FieldErrors();
    key(idempotencyKey, errors);
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!request.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    PolicyDraft draft =
        policy(
            request.getEffectiveFrom(), request.getPayDay(), request.getProrationMethod(), errors);
    Integer version = version(request.getExpectedVersion(), 60, errors);
    String digest = digest(request.getPreviewDigest(), "previewDigest", errors);
    Instant expiresAt = instant(request.getExpiresAt(), "expiresAt", errors);
    confirmed(request.getConfirmed(), errors);
    errors.throwIfAny();
    return new PolicyCommit(draft, version, digest, expiresAt);
  }

  static RunDraft runDraft(PayrollRunCommandRequest request) {
    FieldErrors errors = new FieldErrors();
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!request.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    PayrollPeriod period = period(request.getPeriod(), errors);
    CompensationCurrency currency = currency(request.getCurrency(), errors);
    errors.throwIfAny();
    return new RunDraft(period, currency);
  }

  static RunCommit runCommit(String idempotencyKey, CommitPayrollRunRequest request) {
    FieldErrors errors = new FieldErrors();
    key(idempotencyKey, errors);
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!request.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    PayrollPeriod period = period(request.getPeriod(), errors);
    CompensationCurrency currency = currency(request.getCurrency(), errors);
    Integer version = version(request.getExpectedVersion(), 25, errors);
    String input = digest(request.getInputDigest(), "inputDigest", errors);
    String preview = digest(request.getPreviewDigest(), "previewDigest", errors);
    Instant expiresAt = instant(request.getExpiresAt(), "expiresAt", errors);
    confirmed(request.getConfirmed(), errors);
    errors.throwIfAny();
    return new RunCommit(new RunDraft(period, currency), version, input, preview, expiresAt);
  }

  // ------------------------------------------------------------------------------------------
  // Parts
  // ------------------------------------------------------------------------------------------

  private static PolicyDraft policy(
      Object rawFrom, Object rawPayDay, Object rawMethod, FieldErrors errors) {
    LocalDate from = date(rawFrom, "effectiveFrom", errors);
    PayrollPayDay payDay = payDay(rawPayDay, errors);
    PayrollProration method = method(rawMethod, errors);
    if (from == null || payDay == null || method == null) {
      return null;
    }
    return new PolicyDraft(from, payDay, method);
  }

  private static PayrollPayDay payDay(Object raw, FieldErrors errors) {
    if (raw == null) {
      errors.add("payDay", Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof Map<?, ?> map)) {
      errors.add("payDay", Constraint.FORMAT);
      return null;
    }
    for (Object key : map.keySet()) {
      if (!"kind".equals(key) && !"day".equals(key)) {
        errors.add("payDay", Constraint.UNKNOWN_PROPERTY);
        return null;
      }
    }
    Object kind = map.get("kind");
    Object day = map.get("day");
    if ("LAST_DAY".equals(kind) && day == null && map.containsKey("day")) {
      return PayrollPayDay.lastDay();
    }
    if ("DAY".equals(kind) && (day instanceof Integer || day instanceof Long)) {
      long value = ((Number) day).longValue();
      if (value >= 1 && value <= 28) {
        return PayrollPayDay.ofDay((int) value);
      }
      errors.add("payDay", Constraint.RANGE);
      return null;
    }
    errors.add("payDay", Constraint.FORMAT);
    return null;
  }

  private static PayrollProration method(Object raw, FieldErrors errors) {
    if (raw == null) {
      errors.add("prorationMethod", Constraint.REQUIRED);
      return null;
    }
    if (raw instanceof String text) {
      for (PayrollProration method : PayrollProration.values()) {
        if (method.name().equals(text)) {
          return method;
        }
      }
    }
    errors.add("prorationMethod", Constraint.FORMAT);
    return null;
  }

  private static PayrollPeriod period(Object raw, FieldErrors errors) {
    if (raw == null) {
      errors.add("period", Constraint.REQUIRED);
      return null;
    }
    Optional<PayrollPeriod> period =
        raw instanceof String text ? PayrollPeriod.parse(text) : Optional.empty();
    if (period.isEmpty()) {
      errors.add("period", Constraint.FORMAT);
      return null;
    }
    return period.get();
  }

  private static CompensationCurrency currency(Object raw, FieldErrors errors) {
    if (raw == null) {
      errors.add("currency", Constraint.REQUIRED);
      return null;
    }
    if (raw instanceof String text) {
      for (CompensationCurrency currency : CompensationCurrency.values()) {
        if (currency.name().equals(text)) {
          return currency;
        }
      }
    }
    errors.add("currency", Constraint.FORMAT);
    return null;
  }

  private static LocalDate date(Object raw, String field, FieldErrors errors) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof String text) || !DATE.matcher(text).matches()) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    try {
      return LocalDate.parse(text);
    } catch (DateTimeParseException invalid) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
  }

  private static Instant instant(Object raw, String field, FieldErrors errors) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (raw instanceof String text && text.length() <= 40) {
      try {
        return Instant.parse(text);
      } catch (DateTimeParseException invalid) {
        errors.add(field, Constraint.FORMAT);
        return null;
      }
    }
    errors.add(field, Constraint.FORMAT);
    return null;
  }

  private static Integer version(Object raw, int max, FieldErrors errors) {
    if (raw == null) {
      errors.add("expectedVersion", Constraint.REQUIRED);
      return null;
    }
    if (raw instanceof Integer || raw instanceof Long) {
      long value = ((Number) raw).longValue();
      if (value >= 0 && value <= max) {
        return (int) value;
      }
      errors.add("expectedVersion", Constraint.RANGE);
      return null;
    }
    errors.add("expectedVersion", Constraint.FORMAT);
    return null;
  }

  private static String digest(Object raw, String field, FieldErrors errors) {
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

  private static void confirmed(Object raw, FieldErrors errors) {
    if (raw == null) {
      errors.add("confirmed", Constraint.REQUIRED);
    } else if (!(raw instanceof Boolean value)) {
      errors.add("confirmed", Constraint.FORMAT);
    } else if (!value) {
      errors.add("confirmed", Constraint.REQUIRED);
    }
  }

  private static void key(String idempotencyKey, FieldErrors errors) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      errors.add(IdempotencyKeys.HEADER, Constraint.REQUIRED);
    } else if (!IdempotencyKeys.isWellFormed(idempotencyKey)) {
      errors.add(IdempotencyKeys.HEADER, Constraint.FORMAT);
    }
  }
}
