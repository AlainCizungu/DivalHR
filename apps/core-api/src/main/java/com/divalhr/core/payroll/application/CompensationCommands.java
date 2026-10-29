package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.CancelCompensationRequest;
import com.divalhr.core.payroll.api.CommitCompensationRequest;
import com.divalhr.core.payroll.api.CompensationCommandRequest;
import com.divalhr.core.payroll.domain.CompensationAction;
import com.divalhr.core.payroll.domain.CompensationCurrency;
import com.divalhr.core.payroll.domain.CompensationEvaluator;
import com.divalhr.core.payroll.domain.CompensationMoney;
import com.divalhr.core.payroll.domain.CompensationReason;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.tenancy.TenantId;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validation of the compensation requests (MVP-093A): shapes first (one field error per problem,
 * every unknown property named), then the stable amount error. Path identifiers that are malformed
 * are the same safe 404 as unknown ones. Restricted financial and HR data: no record here prints an
 * amount or an explanation.
 */
final class CompensationCommands {

  /** Shortest and longest explanation, in characters. */
  static final int MIN_EXPLANATION = 10;

  static final int MAX_EXPLANATION = 500;

  private static final Pattern UUID_SHAPE =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
  private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");
  // The grammar of payroll.compensation_text_valid (V43): no control or format characters, no
  // markup, no link and no encoded content. Java rejects at least what the database rejects.
  private static final Pattern FORBIDDEN_CHARACTERS =
      Pattern.compile(
          "[\\u0001-\\u001F\\u007F-\\u009F\\u00AD\\u200B-\\u200F\\u2028-\\u202E\\u2060-\\u2064"
              + "\\u2066-\\u206F\\uFEFF\\uE000-\\uF8FF\\uFFF9-\\uFFFB<>\\\\]");
  private static final Pattern FORBIDDEN_CONSTRUCTS =
      Pattern.compile(
          "(https?:|www\\.|://|%[0-9a-f]{2}|&#|&[a-z][a-z0-9]{1,31};)", Pattern.CASE_INSENSITIVE);

  private CompensationCommands() {}

  /**
   * A well-formed command to preview or commit.
   *
   * @param action action
   * @param effectiveFrom first day of the value
   * @param currency currency
   * @param amountText the submitted text (Restricted; echoed only to the administrator)
   * @param amount the parsed amount ({@code OK} or {@code OUT_OF_RANGE})
   * @param target origin change of the fact to correct, or {@code null}
   * @param reason correction reason, or {@code null}
   * @param explanation restricted explanation, or {@code null}
   */
  record Draft(
      CompensationAction action,
      LocalDate effectiveFrom,
      CompensationCurrency currency,
      String amountText,
      CompensationMoney.Parsed amount,
      UUID target,
      CompensationReason reason,
      String explanation) {

    CompensationEvaluator.Command command() {
      return new CompensationEvaluator.Command(action, effectiveFrom, amount, target);
    }

    Map<String, Object> canonical(Map<String, Object> into) {
      into.put("action", action.name());
      into.put("effectiveFrom", effectiveFrom.toString());
      into.put("currency", currency.name());
      into.put("amount", amountText);
      into.put("targetChangeId", target == null ? null : target.toString());
      into.put("reasonCode", reason == null ? null : reason.name());
      into.put("explanation", explanation);
      return into;
    }

    @Override
    public String toString() {
      return "Draft[" + action + "]";
    }
  }

  /**
   * A well-formed commit.
   *
   * @param draft the command
   * @param expectedVersion the compensation version the preview showed
   * @param employmentDigest the employment digest the preview returned
   * @param previewDigest the preview digest the preview returned
   * @param expiresAt the expiry the preview returned
   * @param acknowledgeRetroactive whether the retroactive effect is acknowledged
   */
  record Commit(
      Draft draft,
      int expectedVersion,
      String employmentDigest,
      String previewDigest,
      Instant expiresAt,
      boolean acknowledgeRetroactive) {

    Map<String, Object> canonical(TenantId tenant, UUID employeeId) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("employeeId", employeeId.toString());
      draft.canonical(canonical);
      canonical.put("expectedVersion", expectedVersion);
      canonical.put("employmentDigest", employmentDigest);
      canonical.put("previewDigest", previewDigest);
      canonical.put("expiresAt", expiresAt.toString());
      canonical.put("acknowledgeRetroactive", acknowledgeRetroactive);
      canonical.put("confirmed", true);
      return canonical;
    }

    @Override
    public String toString() {
      return "Commit[" + draft + "]";
    }
  }

  /**
   * A well-formed cancellation.
   *
   * @param changeId the scheduled change to cancel
   * @param expectedVersion the compensation version the preview showed
   * @param employmentDigest the employment digest the preview returned
   * @param cancellationDigest the cancellation digest the preview returned
   * @param expiresAt the expiry the preview returned
   */
  record Cancel(
      UUID changeId,
      int expectedVersion,
      String employmentDigest,
      String cancellationDigest,
      Instant expiresAt) {

    Map<String, Object> canonical(TenantId tenant, UUID employeeId) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("employeeId", employeeId.toString());
      canonical.put("changeId", changeId.toString());
      canonical.put("expectedVersion", expectedVersion);
      canonical.put("employmentDigest", employmentDigest);
      canonical.put("cancellationDigest", cancellationDigest);
      canonical.put("expiresAt", expiresAt.toString());
      canonical.put("confirmed", true);
      return canonical;
    }

    @Override
    public String toString() {
      return "Cancel[" + changeId + "]";
    }
  }

  // ------------------------------------------------------------------------------------------
  // Entry points
  // ------------------------------------------------------------------------------------------

  /**
   * A path identifier: malformed is the same 404 as unknown.
   *
   * @param raw path value
   * @return the identifier
   */
  static UUID id(String raw) {
    if (raw == null || !UUID_SHAPE.matcher(raw).matches()) {
      throw notFound();
    }
    return UUID.fromString(raw.toLowerCase(Locale.ROOT));
  }

  static ApiException notFound() {
    return new ApiException(ErrorCode.COMPENSATION_NOT_FOUND, Map.of());
  }

  static Draft draft(CompensationCommandRequest request) {
    FieldErrors errors = new FieldErrors();
    Draft draft = draft(request, errors);
    errors.throwIfAny();
    return amountChecked(draft);
  }

  static Commit commit(String idempotencyKey, CommitCompensationRequest request) {
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
    Draft draft =
        draft(
            request.getAction(),
            request.getEffectiveFrom(),
            request.getCurrency(),
            request.getAmount(),
            request.getTargetChangeId(),
            request.getReasonCode(),
            request.getExplanation(),
            errors);
    Integer version = version(request.getExpectedVersion(), errors);
    String employment = digest(request.getEmploymentDigest(), "employmentDigest", errors);
    String preview = digest(request.getPreviewDigest(), "previewDigest", errors);
    Instant expiresAt = instant(request.getExpiresAt(), "expiresAt", errors);
    Boolean acknowledged =
        flag(request.getAcknowledgeRetroactive(), "acknowledgeRetroactive", errors);
    confirmed(request.getConfirmed(), errors);
    errors.throwIfAny();
    return new Commit(amountChecked(draft), version, employment, preview, expiresAt, acknowledged);
  }

  static Cancel cancel(String idempotencyKey, UUID changeId, CancelCompensationRequest request) {
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
    Integer version = version(request.getExpectedVersion(), errors);
    String employment = digest(request.getEmploymentDigest(), "employmentDigest", errors);
    String cancellation = digest(request.getCancellationDigest(), "cancellationDigest", errors);
    Instant expiresAt = instant(request.getExpiresAt(), "expiresAt", errors);
    confirmed(request.getConfirmed(), errors);
    errors.throwIfAny();
    return new Cancel(changeId, version, employment, cancellation, expiresAt);
  }

  /**
   * The stable error of a malformed amount.
   *
   * @return the exception
   */
  static ApiException invalidAmount() {
    return new ApiException(ErrorCode.COMPENSATION_INVALID_AMOUNT, Map.of());
  }

  // ------------------------------------------------------------------------------------------
  // Parts
  // ------------------------------------------------------------------------------------------

  private static Draft draft(CompensationCommandRequest request, FieldErrors errors) {
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!request.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    return draft(
        request.getAction(),
        request.getEffectiveFrom(),
        request.getCurrency(),
        request.getAmount(),
        request.getTargetChangeId(),
        request.getReasonCode(),
        request.getExplanation(),
        errors);
  }

  private static Draft draft(
      Object rawAction,
      Object rawFrom,
      Object rawCurrency,
      Object rawAmount,
      Object rawTarget,
      Object rawReason,
      Object rawExplanation,
      FieldErrors errors) {
    CompensationAction action = action(rawAction, errors);
    LocalDate from = date(rawFrom, "effectiveFrom", errors);
    CompensationCurrency currency = currency(rawCurrency, errors);
    String amount = null;
    if (rawAmount == null) {
      errors.add("amount", Constraint.REQUIRED);
    } else if (rawAmount instanceof String text) {
      amount = text;
    } else {
      errors.add("amount", Constraint.FORMAT);
    }
    boolean correction = action == CompensationAction.CORRECTED;
    UUID target = null;
    CompensationReason reason = null;
    String explanation = null;
    if (action != null && correction) {
      target = target(rawTarget, errors);
      reason = reason(rawReason, errors);
      explanation = explanation(rawExplanation, errors);
    } else if (action != null) {
      absent(rawTarget, "targetChangeId", errors);
      absent(rawReason, "reasonCode", errors);
      absent(rawExplanation, "explanation", errors);
    }
    if (action == null || from == null || currency == null || amount == null) {
      return null;
    }
    // The amount is parsed after every shape check; a malformed one is a stable amount error.
    return new Draft(
        action,
        from,
        currency,
        amount,
        CompensationMoney.parse(currency, amount),
        target,
        reason,
        explanation);
  }

  private static Draft amountChecked(Draft draft) {
    if (draft == null) {
      throw new IllegalStateException("a draft needs its shape checked first");
    }
    if (draft.amount().status() == CompensationMoney.Status.MALFORMED) {
      throw invalidAmount();
    }
    return draft;
  }

  private static CompensationAction action(Object raw, FieldErrors errors) {
    if (raw == null) {
      errors.add("action", Constraint.REQUIRED);
      return null;
    }
    if (raw instanceof String text) {
      for (CompensationAction action : CompensationAction.values()) {
        if (action.isCommand() && action.name().equals(text)) {
          return action;
        }
      }
    }
    errors.add("action", Constraint.FORMAT);
    return null;
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

  private static UUID target(Object raw, FieldErrors errors) {
    if (raw == null) {
      errors.add("targetChangeId", Constraint.REQUIRED);
      return null;
    }
    if (raw instanceof String text && UUID_SHAPE.matcher(text).matches()) {
      return UUID.fromString(text.toLowerCase(Locale.ROOT));
    }
    errors.add("targetChangeId", Constraint.FORMAT);
    return null;
  }

  private static CompensationReason reason(Object raw, FieldErrors errors) {
    if (raw == null) {
      errors.add("reasonCode", Constraint.REQUIRED);
      return null;
    }
    if (raw instanceof String text) {
      for (CompensationReason reason : CompensationReason.values()) {
        if (reason.name().equals(text)) {
          return reason;
        }
      }
    }
    errors.add("reasonCode", Constraint.FORMAT);
    return null;
  }

  private static String explanation(Object raw, FieldErrors errors) {
    if (raw == null) {
      errors.add("explanation", Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof String text)) {
      errors.add("explanation", Constraint.FORMAT);
      return null;
    }
    int length = text.codePointCount(0, text.length());
    if (length < MIN_EXPLANATION || length > MAX_EXPLANATION) {
      errors.add("explanation", Constraint.LENGTH);
      return null;
    }
    if (!restricted(text)) {
      errors.add("explanation", Constraint.FORMAT);
      return null;
    }
    return text;
  }

  /**
   * Whether a text satisfies the restricted-text grammar (normalized, no edge whitespace, no
   * control or format character, markup, link or encoded content).
   *
   * @param text the text
   * @return true when legal
   */
  static boolean restricted(String text) {
    if (!Normalizer.isNormalized(text, Normalizer.Form.NFC)) {
      return false;
    }
    int first = text.codePointAt(0);
    int last = text.codePointBefore(text.length());
    if (space(first) || space(last)) {
      return false;
    }
    return !FORBIDDEN_CHARACTERS.matcher(text).find() && !FORBIDDEN_CONSTRUCTS.matcher(text).find();
  }

  private static boolean space(int codePoint) {
    return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
  }

  private static void absent(Object raw, String field, FieldErrors errors) {
    if (raw != null) {
      errors.add(field, Constraint.FORMAT);
    }
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
      LocalDate day = LocalDate.parse(text);
      if (day.getYear() < 1900) {
        errors.add(field, Constraint.RANGE);
        return null;
      }
      return day;
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

  private static Integer version(Object raw, FieldErrors errors) {
    if (raw == null) {
      errors.add("expectedVersion", Constraint.REQUIRED);
      return null;
    }
    if (raw instanceof Integer || raw instanceof Long) {
      long value = ((Number) raw).longValue();
      if (value >= 0 && value <= Integer.MAX_VALUE) {
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

  private static Boolean flag(Object raw, String field, FieldErrors errors) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof Boolean value)) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    return value;
  }

  private static void confirmed(Object raw, FieldErrors errors) {
    Boolean value = flag(raw, "confirmed", errors);
    if (value != null && !value) {
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
