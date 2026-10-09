package com.divalhr.core.people.leave.application;

import com.divalhr.core.people.leave.api.CreateMyLeaveRequest;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.tenancy.TenantId;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A validated leave request (MVP-041A, D41A-3). Validation reports {@code {field, constraint}}
 * pairs only: no submitted value, date or amount is ever echoed.
 *
 * <p>{@link #from} is deterministic: it depends on the request alone, so it runs before the
 * idempotency decision and an exact retry is always recognized (R88-1). The only time-varying rule,
 * the first day against the business date, is {@link #requireStartFrom}, applied to a new execution
 * only, after the key is reserved.
 *
 * @param policyId requested policy
 * @param startDate first day (inclusive)
 * @param endDate last day (inclusive), not before the first day, at most 366 days in all
 * @param amount requested amount with scale 2, entered in the policy's unit
 */
public record LeaveRequestCommand(
    UUID policyId, LocalDate startDate, LocalDate endDate, BigDecimal amount) {

  /** Longest request, in inclusive calendar days. */
  static final int MAX_DAYS = 366;

  /** Largest amount. */
  static final BigDecimal AMOUNT_MAX = new BigDecimal("10000.00");

  private static final LocalDate DATE_MIN = LocalDate.of(1900, 1, 1);
  private static final LocalDate DATE_MAX = LocalDate.of(2999, 12, 31);
  private static final DateTimeFormatter ISO =
      DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
  private static final Pattern ISO_SHAPE = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");
  private static final Pattern UUID_SHAPE =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

  /**
   * Identifiers only: no date or amount reaches a log line through {@code toString}.
   *
   * @return safe text
   */
  @Override
  public String toString() {
    return "LeaveRequestCommand[policyId=" + policyId + "]";
  }

  /**
   * Validates the request's shape and its time-independent rules.
   *
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param request body
   * @return the command
   * @throws com.divalhr.core.platform.error.ApiException {@code VALIDATION_FAILED} listing every
   *     problem
   */
  static LeaveRequestCommand from(String idempotencyKey, CreateMyLeaveRequest request) {
    FieldErrors errors = new FieldErrors();
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      errors.add(IdempotencyKeys.HEADER, Constraint.REQUIRED);
    } else if (!IdempotencyKeys.isWellFormed(idempotencyKey)) {
      errors.add(IdempotencyKeys.HEADER, Constraint.FORMAT);
    }
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!request.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    UUID policyId = uuid(errors, "policyId", request.getPolicyId());
    LocalDate start = date(errors, "startDate", request.getStartDate());
    LocalDate end = date(errors, "endDate", request.getEndDate());
    if (start != null && end != null) {
      if (end.isBefore(start)) {
        errors.add("endDate", Constraint.RANGE);
      } else if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_DAYS) {
        errors.add("endDate", Constraint.RANGE);
      }
    }
    BigDecimal amount = amount(errors, request.getAmount());
    errors.throwIfAny();
    return new LeaveRequestCommand(policyId, start, end, amount);
  }

  /**
   * The time-varying rule of a new request (D41A-3 rule 1): the first day is the organization's
   * business date or later. Never applied to a replay.
   *
   * @param businessDate today in the organization's time zone
   * @throws com.divalhr.core.platform.error.ApiException {@code VALIDATION_FAILED} with {@code
   *     startDate: RANGE}
   */
  void requireStartFrom(LocalDate businessDate) {
    if (startDate.isBefore(businessDate)) {
      FieldErrors errors = new FieldErrors();
      errors.add("startDate", Constraint.RANGE);
      errors.throwIfAny();
    }
  }

  /**
   * The normalized command and its scope, fingerprinted for idempotency.
   *
   * @param tenant verified tenant
   * @return key-sorted canonical form
   */
  Map<String, Object> canonical(TenantId tenant) {
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("tenantId", tenant.toString());
    canonical.put("policyId", policyId.toString());
    canonical.put("startDate", startDate.toString());
    canonical.put("endDate", endDate.toString());
    canonical.put("amount", amount.toPlainString());
    return canonical;
  }

  private static UUID uuid(FieldErrors errors, String field, Object raw) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof String text) || !UUID_SHAPE.matcher(text).matches()) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    return UUID.fromString(text.toLowerCase(Locale.ROOT));
  }

  private static LocalDate date(FieldErrors errors, String field, Object raw) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof String text) || !ISO_SHAPE.matcher(text).matches()) {
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
    if (date.isBefore(DATE_MIN) || date.isAfter(DATE_MAX)) {
      errors.add(field, Constraint.RANGE);
      return null;
    }
    return date;
  }

  private static BigDecimal amount(FieldErrors errors, Object raw) {
    if (raw == null) {
      errors.add("amount", Constraint.REQUIRED);
      return null;
    }
    BigDecimal value;
    if (raw instanceof Integer || raw instanceof Long || raw instanceof BigInteger) {
      value = new BigDecimal(raw.toString());
    } else if (raw instanceof BigDecimal decimal) {
      value = decimal;
    } else if (raw instanceof Double || raw instanceof Float) {
      if (!Double.isFinite(((Number) raw).doubleValue())) {
        errors.add("amount", Constraint.FORMAT);
        return null;
      }
      // The shortest decimal that reads back as the JSON number (e.g. 1.5, never 1.4999...).
      value = new BigDecimal(raw.toString());
    } else {
      errors.add("amount", Constraint.FORMAT);
      return null;
    }
    value = value.stripTrailingZeros();
    if (value.scale() > 2) {
      errors.add("amount", Constraint.FORMAT);
      return null;
    }
    if (value.signum() <= 0 || value.compareTo(AMOUNT_MAX) > 0) {
      errors.add("amount", Constraint.RANGE);
      return null;
    }
    return value.setScale(2);
  }
}
