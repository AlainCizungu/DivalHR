package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.SalaryAdvanceBodies.CancelPolicy;
import com.divalhr.core.payroll.api.SalaryAdvanceBodies.CommitPolicy;
import com.divalhr.core.payroll.api.SalaryAdvanceBodies.CommitRequest;
import com.divalhr.core.payroll.api.SalaryAdvanceBodies.PolicyCommand;
import com.divalhr.core.payroll.api.SalaryAdvanceBodies.RefuseCommand;
import com.divalhr.core.payroll.api.SalaryAdvanceBodies.RequestCommand;
import com.divalhr.core.payroll.api.SalaryAdvanceBodies.VersionedCommand;
import com.divalhr.core.payroll.domain.CompensationCurrency;
import com.divalhr.core.payroll.domain.CompensationMoney;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validation of the salary-advance request bodies (MVP-120A). Every value arrives as {@code Object}
 * so that a wrong type is a field error. Field errors name our fields and a fixed constraint; a
 * submitted value, an amount or a note is never echoed. A well-formed command is a small record
 * whose {@code canonical} form (key-sorted, no personal text) scopes its idempotency key.
 */
final class SalaryAdvanceCommands {

  /** Page sizes of the request lists. */
  static final int DEFAULT_LIMIT = 25;

  static final int MAX_LIMIT = 100;

  /** The longest internal note. */
  static final int MAX_NOTE = 500;

  private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
  private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");
  private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9-]{2,31}$");
  static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9._-]{1,32}$");
  private static final Pattern UUID_SHAPE =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  private static final LocalDate EARLIEST = LocalDate.of(2000, 1, 1);
  private static final LocalDate LATEST = LocalDate.of(2999, 12, 31);
  private static final Set<String> MONEY_KEYS = Set.of("currency", "amount");
  private static final Set<String> CONSENT_KEYS = Set.of("version", "language", "affirmed");
  private static final Set<String> POLICY_KEYS =
      Set.of(
          "currency",
          "code",
          "effectiveFrom",
          "effectiveTo",
          "enabled",
          "minTenureMonths",
          "maxPercent",
          "absoluteMax",
          "minRequest",
          "maxOpenRequests",
          "waitingDays",
          "consentVersion",
          "partnerCode");
  private static final Set<String> REFUSAL_REASONS =
      Set.of(
          "POLICY_NO_LONGER_APPLIES",
          "EMPLOYMENT_ENDING",
          "PAYROLL_REFERENCE_INVALID",
          "OPEN_REQUEST_LIMIT",
          "EMPLOYER_NOT_PARTICIPATING",
          "OTHER_POLICY_REASON");

  private SalaryAdvanceCommands() {}

  /**
   * A well-formed policy.
   *
   * @param currency currency
   * @param code stable code
   * @param effectiveFrom first day in force
   * @param effectiveTo last day in force, or {@code null}
   * @param enabled whether employees may request
   * @param minTenureMonths minimum completed months
   * @param maxPercent whole percent
   * @param absoluteMaxMinor optional absolute maximum
   * @param minRequestMinor minimum request
   * @param maxOpenRequests open limit
   * @param waitingDays waiting days
   * @param consentVersion consent version
   * @param partnerCode partner code
   */
  record PolicyDraft(
      CompensationCurrency currency,
      String code,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      boolean enabled,
      int minTenureMonths,
      int maxPercent,
      Long absoluteMaxMinor,
      long minRequestMinor,
      int maxOpenRequests,
      int waitingDays,
      String consentVersion,
      String partnerCode) {

    Map<String, Object> canonical(Map<String, Object> into) {
      into.put("currency", currency.name());
      into.put("code", code);
      into.put("effectiveFrom", effectiveFrom.toString());
      into.put("effectiveTo", effectiveTo == null ? null : effectiveTo.toString());
      into.put("enabled", enabled);
      into.put("minTenureMonths", minTenureMonths);
      into.put("maxPercent", maxPercent);
      into.put("absoluteMaxMinor", absoluteMaxMinor);
      into.put("minRequestMinor", minRequestMinor);
      into.put("maxOpenRequests", maxOpenRequests);
      into.put("waitingDays", waitingDays);
      into.put("consentVersion", consentVersion);
      into.put("partnerCode", partnerCode);
      return into;
    }
  }

  /**
   * A well-formed policy commit.
   *
   * @param draft the policy
   * @param expectedVersion the chain version the preview showed
   * @param previewDigest the digest the preview returned
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
   * A well-formed policy cancellation.
   *
   * @param expectedSequence the sequence of the version being cancelled
   */
  record PolicyCancel(int expectedSequence) {

    Map<String, Object> canonical(TenantId tenant, UUID legalEntityId, UUID policyId) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("legalEntityId", legalEntityId.toString());
      canonical.put("policyId", policyId.toString());
      canonical.put("expectedVersion", expectedSequence);
      canonical.put("confirmed", true);
      return canonical;
    }
  }

  /**
   * A well-formed requested amount.
   *
   * @param currency currency
   * @param amountMinor the amount in minor units
   */
  record RequestDraft(CompensationCurrency currency, long amountMinor) {}

  /**
   * A well-formed request commit. The canonical form holds the amount only as an opaque keyed
   * digest component through {@link #canonical}; idempotency records hold the canonical text's
   * fingerprint, never an amount in the clear.
   *
   * @param draft currency and amount
   * @param previewDigest the digest the preview returned
   * @param expiresAt the expiry the preview returned
   * @param consentVersion the version the employee affirmed
   * @param language {@code en} or {@code fr}
   */
  record RequestCommit(
      RequestDraft draft,
      String previewDigest,
      Instant expiresAt,
      String consentVersion,
      String language) {

    Map<String, Object> canonical(TenantId tenant) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("currency", draft.currency().name());
      canonical.put("amountMinor", draft.amountMinor());
      canonical.put("previewDigest", previewDigest);
      canonical.put("expiresAt", expiresAt.toString());
      canonical.put("consentVersion", consentVersion);
      canonical.put("language", language);
      canonical.put("affirmed", true);
      return canonical;
    }
  }

  /**
   * A well-formed withdrawal or employer confirmation.
   *
   * @param expectedVersion the request version the caller saw
   */
  record VersionedAction(int expectedVersion) {

    Map<String, Object> canonical(TenantId tenant, UUID requestId) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("requestId", requestId.toString());
      canonical.put("expectedVersion", expectedVersion);
      canonical.put("confirmed", true);
      return canonical;
    }
  }

  /**
   * A well-formed employer refusal.
   *
   * @param expectedVersion the request version the caller saw
   * @param reasonCode the closed reason
   * @param note the internal note, or {@code null}
   */
  record Refusal(int expectedVersion, String reasonCode, String note) {

    Map<String, Object> canonical(TenantId tenant, UUID requestId) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("requestId", requestId.toString());
      canonical.put("expectedVersion", expectedVersion);
      canonical.put("reasonCode", reasonCode);
      canonical.put("noteLength", note == null ? null : note.length());
      canonical.put("noteDigest", note == null ? null : DigestsSupport.sha256(note));
      canonical.put("confirmed", true);
      return canonical;
    }
  }

  /** A small seam so the canonical form fingerprints the note instead of storing its text. */
  private static final class DigestsSupport {
    static String sha256(String text) {
      return com.divalhr.core.platform.idempotency.Fingerprints.sha256(text);
    }
  }

  // ------------------------------------------------------------------------------------------
  // Identifiers and pages
  // ------------------------------------------------------------------------------------------

  /**
   * A path identifier: malformed is the same answer as unknown.
   *
   * @param raw path value
   * @param notFound the 404 code
   * @return the identifier
   */
  static UUID id(String raw, ErrorCode notFound) {
    if (raw == null || !UUID_SHAPE.matcher(raw).matches()) {
      throw new ApiException(notFound, Map.of());
    }
    return UUID.fromString(raw.toLowerCase(java.util.Locale.ROOT));
  }

  /**
   * Validates a page size.
   *
   * @param raw raw value, or {@code null}
   * @param errors collector
   * @return the size
   */
  static int limit(String raw, FieldErrors errors) {
    return PayrollCommands.limit(raw, DEFAULT_LIMIT, MAX_LIMIT, errors);
  }

  /**
   * Validates a status filter.
   *
   * @param raw raw value, or {@code null}
   * @param errors collector
   * @return the status, or {@code null}
   */
  static String statusFilter(String raw, FieldErrors errors) {
    if (raw == null) {
      return null;
    }
    if (Set.of("SUBMITTED", "EMPLOYER_CONFIRMED", "EMPLOYER_REFUSED", "WITHDRAWN").contains(raw)) {
      return raw;
    }
    errors.add("status", Constraint.FORMAT);
    return null;
  }

  /**
   * Validates a currency filter.
   *
   * @param raw raw value, or {@code null}
   * @param errors collector
   * @return the currency, or {@code null}
   */
  static CompensationCurrency currencyFilter(String raw, FieldErrors errors) {
    if (raw == null) {
      return null;
    }
    for (CompensationCurrency currency : CompensationCurrency.values()) {
      if (currency.name().equals(raw)) {
        return currency;
      }
    }
    errors.add("currency", Constraint.FORMAT);
    return null;
  }

  // ------------------------------------------------------------------------------------------
  // Entry points
  // ------------------------------------------------------------------------------------------

  static PolicyDraft policyDraft(PolicyCommand body) {
    FieldErrors errors = new FieldErrors();
    if (body == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!body.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    PolicyDraft draft = policy(body.toMap(), errors);
    errors.throwIfAny();
    return draft;
  }

  static PolicyCommit policyCommit(String idempotencyKey, CommitPolicy body) {
    FieldErrors errors = new FieldErrors();
    key(idempotencyKey, errors);
    if (body == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!body.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    PolicyDraft draft = null;
    Map<String, Object> nested = object(body.getPolicy(), "policy", POLICY_KEYS, errors);
    if (nested != null) {
      draft = policy(nested, errors);
    }
    Integer version = integer(body.getExpectedVersion(), "expectedVersion", 0, 25, errors);
    String digest = digest(body.getPreviewDigest(), "previewDigest", errors);
    Instant expiresAt = instant(body.getExpiresAt(), "expiresAt", errors);
    confirmed(body.getConfirmed(), "confirmed", errors);
    errors.throwIfAny();
    return new PolicyCommit(draft, version, digest, expiresAt);
  }

  static PolicyCancel policyCancel(String idempotencyKey, CancelPolicy body) {
    FieldErrors errors = new FieldErrors();
    key(idempotencyKey, errors);
    if (body == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!body.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    Integer version = integer(body.getExpectedVersion(), "expectedVersion", 1, 25, errors);
    confirmed(body.getConfirmed(), "confirmed", errors);
    errors.throwIfAny();
    return new PolicyCancel(version);
  }

  static RequestDraft requestDraft(RequestCommand body) {
    FieldErrors errors = new FieldErrors();
    if (body == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!body.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    CompensationCurrency currency = currency(body.getCurrency(), "currency", errors);
    Long minor = amount(body.getAmount(), "amount", currency, errors);
    errors.throwIfAny();
    return new RequestDraft(currency, minor);
  }

  static RequestCommit requestCommit(String idempotencyKey, CommitRequest body) {
    FieldErrors errors = new FieldErrors();
    key(idempotencyKey, errors);
    if (body == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!body.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    CompensationCurrency currency = currency(body.getCurrency(), "currency", errors);
    Long minor = amount(body.getAmount(), "amount", currency, errors);
    String digest = digest(body.getPreviewDigest(), "previewDigest", errors);
    Instant expiresAt = instant(body.getExpiresAt(), "expiresAt", errors);
    String version = null;
    String language = null;
    boolean affirmed = false;
    Map<String, Object> consent = object(body.getConsent(), "consent", CONSENT_KEYS, errors);
    if (consent != null) {
      version = text(consent.get("version"), "consent.version", VERSION, errors);
      language = language(consent.get("language"), errors);
      Object raw = consent.get("affirmed");
      if (raw == null) {
        errors.add("consent.affirmed", Constraint.REQUIRED);
      } else if (!(raw instanceof Boolean value)) {
        errors.add("consent.affirmed", Constraint.FORMAT);
      } else {
        affirmed = value;
      }
    }
    errors.throwIfAny();
    if (!affirmed) {
      // The affirmation is explicit and never pre-checked: its absence is its own stable answer.
      throw new ApiException(ErrorCode.SALARY_ADVANCE_CONSENT_REQUIRED, Map.of());
    }
    return new RequestCommit(
        new RequestDraft(currency, minor), digest, expiresAt, version, language);
  }

  static VersionedAction versioned(String idempotencyKey, VersionedCommand body) {
    FieldErrors errors = new FieldErrors();
    key(idempotencyKey, errors);
    if (body == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!body.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    Integer version = integer(body.getExpectedVersion(), "expectedVersion", 1, 3, errors);
    confirmed(body.getConfirmed(), "confirmed", errors);
    errors.throwIfAny();
    return new VersionedAction(version);
  }

  static Refusal refusal(String idempotencyKey, RefuseCommand body) {
    FieldErrors errors = new FieldErrors();
    key(idempotencyKey, errors);
    if (body == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!body.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    Integer version = integer(body.getExpectedVersion(), "expectedVersion", 1, 3, errors);
    String reason = null;
    Object rawReason = body.getReasonCode();
    if (rawReason == null) {
      errors.add("reasonCode", Constraint.REQUIRED);
    } else if (rawReason instanceof String text && REFUSAL_REASONS.contains(text)) {
      reason = text;
    } else {
      errors.add("reasonCode", Constraint.FORMAT);
    }
    String note = note(body.getNote(), errors);
    confirmed(body.getConfirmed(), "confirmed", errors);
    errors.throwIfAny();
    return new Refusal(version, reason, note);
  }

  // ------------------------------------------------------------------------------------------
  // Parts
  // ------------------------------------------------------------------------------------------

  private static PolicyDraft policy(Map<String, Object> values, FieldErrors errors) {
    CompensationCurrency currency = currency(values.get("currency"), "currency", errors);
    String code = text(values.get("code"), "code", CODE, errors);
    LocalDate from = date(values.get("effectiveFrom"), "effectiveFrom", false, errors);
    LocalDate to = date(values.get("effectiveTo"), "effectiveTo", true, errors);
    Boolean enabled = bool(values.get("enabled"), "enabled", errors);
    Integer tenure = integer(values.get("minTenureMonths"), "minTenureMonths", 0, 60, errors);
    Integer percent = integer(values.get("maxPercent"), "maxPercent", 1, 50, errors);
    Long absolute = null;
    if (values.get("absoluteMax") != null) {
      absolute = amount(values.get("absoluteMax"), "absoluteMax", currency, errors);
    }
    Long minimum = amount(values.get("minRequest"), "minRequest", currency, errors);
    Integer open = integer(values.get("maxOpenRequests"), "maxOpenRequests", 1, 3, errors);
    Integer waiting = integer(values.get("waitingDays"), "waitingDays", 0, 90, errors);
    String consent = text(values.get("consentVersion"), "consentVersion", VERSION, errors);
    String partner = null;
    Object rawPartner = values.get("partnerCode");
    if (rawPartner == null) {
      errors.add("partnerCode", Constraint.REQUIRED);
    } else if ("SYNTHETIC-TEST".equals(rawPartner)) {
      partner = "SYNTHETIC-TEST";
    } else {
      errors.add("partnerCode", Constraint.FORMAT);
    }
    if (!errors.isEmpty()) {
      return null;
    }
    return new PolicyDraft(
        currency, code, from, to, enabled, tenure, percent, absolute, minimum, open, waiting,
        consent, partner);
  }

  static Map<String, Object> object(
      Object raw, String field, Set<String> allowed, FieldErrors errors) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof Map<?, ?> map)) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    Map<String, Object> copy = new TreeMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String name) || !allowed.contains(name)) {
        errors.add(field, Constraint.UNKNOWN_PROPERTY);
        return null;
      }
      copy.put(name, entry.getValue());
    }
    return copy;
  }

  private static CompensationCurrency currency(Object raw, String field, FieldErrors errors) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (raw instanceof String text) {
      for (CompensationCurrency currency : CompensationCurrency.values()) {
        if (currency.name().equals(text)) {
          return currency;
        }
      }
    }
    errors.add(field, Constraint.FORMAT);
    return null;
  }

  /** Reads a {@code CompensationMoney} object whose currency must equal the command currency. */
  private static Long amount(
      Object raw, String field, CompensationCurrency currency, FieldErrors errors) {
    Map<String, Object> money = object(raw, field, MONEY_KEYS, errors);
    if (money == null) {
      return null;
    }
    CompensationCurrency stated = currency(money.get("currency"), field + ".currency", errors);
    Object text = money.get("amount");
    if (text == null) {
      errors.add(field + ".amount", Constraint.REQUIRED);
      return null;
    }
    if (!(text instanceof String digits) || stated == null) {
      errors.add(field + ".amount", Constraint.FORMAT);
      return null;
    }
    if (currency != null && stated != currency) {
      // A different currency inside the amount is its own stable, privacy-neutral answer.
      throw new ApiException(ErrorCode.SALARY_ADVANCE_CURRENCY_MISMATCH, Map.of());
    }
    CompensationMoney.Parsed parsed = CompensationMoney.parse(stated, digits);
    return switch (parsed.status()) {
      case OK -> parsed.money().minor();
      case MALFORMED -> {
        errors.add(field + ".amount", Constraint.FORMAT);
        yield null;
      }
      case OUT_OF_RANGE -> {
        errors.add(field + ".amount", Constraint.RANGE);
        yield null;
      }
    };
  }

  static String text(Object raw, String field, Pattern pattern, FieldErrors errors) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof String value) || !pattern.matcher(value).matches()) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    return value;
  }

  static String language(Object raw, FieldErrors errors) {
    if (raw == null) {
      errors.add("consent.language", Constraint.REQUIRED);
      return null;
    }
    if ("en".equals(raw) || "fr".equals(raw)) {
      return (String) raw;
    }
    errors.add("consent.language", Constraint.FORMAT);
    return null;
  }

  private static LocalDate date(Object raw, String field, boolean optional, FieldErrors errors) {
    if (raw == null) {
      if (!optional) {
        errors.add(field, Constraint.REQUIRED);
      }
      return null;
    }
    if (!(raw instanceof String text) || !DATE.matcher(text).matches()) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    try {
      LocalDate date = LocalDate.parse(text);
      if (date.isBefore(EARLIEST) || date.isAfter(LATEST)) {
        errors.add(field, Constraint.RANGE);
        return null;
      }
      return date;
    } catch (DateTimeParseException invalid) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
  }

  static Instant instant(Object raw, String field, FieldErrors errors) {
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

  static Integer integer(Object raw, String field, int min, int max, FieldErrors errors) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (raw instanceof Integer || raw instanceof Long) {
      long value = ((Number) raw).longValue();
      if (value >= min && value <= max) {
        return (int) value;
      }
      errors.add(field, Constraint.RANGE);
      return null;
    }
    errors.add(field, Constraint.FORMAT);
    return null;
  }

  static Boolean bool(Object raw, String field, FieldErrors errors) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (raw instanceof Boolean value) {
      return value;
    }
    errors.add(field, Constraint.FORMAT);
    return null;
  }

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

  static void confirmed(Object raw, String field, FieldErrors errors) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
    } else if (!(raw instanceof Boolean value)) {
      errors.add(field, Constraint.FORMAT);
    } else if (!value) {
      errors.add(field, Constraint.REQUIRED);
    }
  }

  private static String note(Object raw, FieldErrors errors) {
    if (raw == null) {
      return null;
    }
    if (!(raw instanceof String text)) {
      errors.add("note", Constraint.FORMAT);
      return null;
    }
    if (text.isEmpty() || text.length() > MAX_NOTE) {
      errors.add("note", Constraint.LENGTH);
      return null;
    }
    for (int i = 0; i < text.length(); i++) {
      if (Character.isISOControl(text.charAt(i))) {
        errors.add("note", Constraint.FORMAT);
        return null;
      }
    }
    return text;
  }

  static void key(String idempotencyKey, FieldErrors errors) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      errors.add(IdempotencyKeys.HEADER, Constraint.REQUIRED);
    } else if (!IdempotencyKeys.isWellFormed(idempotencyKey)) {
      errors.add(IdempotencyKeys.HEADER, Constraint.FORMAT);
    }
  }
}
