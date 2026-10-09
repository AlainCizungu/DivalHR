package com.divalhr.core.people.leave.application;

import com.divalhr.core.people.leave.api.CreateLeavePolicyRequest;
import com.divalhr.core.people.leave.domain.ApprovalRoute;
import com.divalhr.core.people.leave.domain.BalanceMode;
import com.divalhr.core.people.leave.domain.LeaveUnit;
import com.divalhr.core.people.leave.domain.PayrollEffect;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.tenancy.TenantId;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * A validated, normalized leave policy creation (MVP-040A, D40A-2 and D40A-3). Validation reports
 * {@code {field, constraint}} pairs only: no submitted value is ever echoed.
 *
 * @param code normalized code
 * @param nameEn trimmed, NFC-normalized English name
 * @param nameFr trimmed, NFC-normalized French name
 * @param unit unit
 * @param balanceMode balance mode
 * @param annualEntitlement entitlement with scale 2 when tracked, otherwise {@code null}
 * @param minimumServiceDays service days before eligibility
 * @param approvalRoute approval route
 * @param payrollEffect payroll effect
 * @param effectiveFrom first day
 * @param effectiveTo last day or {@code null}
 */
public record LeavePolicyCommand(
    String code,
    String nameEn,
    String nameFr,
    LeaveUnit unit,
    BalanceMode balanceMode,
    BigDecimal annualEntitlement,
    int minimumServiceDays,
    ApprovalRoute approvalRoute,
    PayrollEffect payrollEffect,
    LocalDate effectiveFrom,
    LocalDate effectiveTo) {

  /** Code grammar (V18 {@code leave_policy_code_format}). */
  static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{1,19}$");

  /** Name length bounds, in code points. */
  static final int NAME_MIN = 2;

  /** Name length bounds, in code points. */
  static final int NAME_MAX = 100;

  /** Largest annual entitlement. */
  static final BigDecimal ENTITLEMENT_MAX = new BigDecimal("10000.00");

  /** Largest minimum service. */
  static final int SERVICE_DAYS_MAX = 3650;

  /** Supported dates (V18 {@code leave_policy_version_period_valid}). */
  static final LocalDate DATE_MIN = LocalDate.of(1900, 1, 1);

  /** Supported dates (V18 {@code leave_policy_version_period_valid}). */
  static final LocalDate DATE_MAX = LocalDate.of(2999, 12, 31);

  private static final DateTimeFormatter ISO =
      DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
  private static final Pattern ISO_SHAPE = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");
  private static final Set<String> NAME_KEYS = Set.of("en", "fr");

  /**
   * Identifiers and enums only: names never reach a log line through {@code toString}.
   *
   * @return safe text
   */
  @Override
  public String toString() {
    return "LeavePolicyCommand[unit=" + unit + ", balanceMode=" + balanceMode + "]";
  }

  /**
   * Validates and normalizes a request.
   *
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param request body
   * @return the command
   * @throws com.divalhr.core.platform.error.ApiException {@code VALIDATION_FAILED} listing every
   *     problem
   */
  static LeavePolicyCommand from(String idempotencyKey, CreateLeavePolicyRequest request) {
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
    String code = code(errors, request.getCode());
    String[] names = names(errors, request.getNames());
    LeaveUnit unit = enumValue(LeaveUnit.class, request.getUnit(), "unit", errors);
    BalanceMode mode =
        enumValue(BalanceMode.class, request.getBalanceMode(), "balanceMode", errors);
    BigDecimal entitlement = entitlement(errors, mode, request.getAnnualEntitlement());
    Integer serviceDays = serviceDays(errors, request.getMinimumServiceDays());
    ApprovalRoute route =
        enumValue(ApprovalRoute.class, request.getApprovalRoute(), "approvalRoute", errors);
    PayrollEffect payroll =
        enumValue(PayrollEffect.class, request.getPayrollEffect(), "payrollEffect", errors);
    LocalDate from = date(errors, "effectiveFrom", request.getEffectiveFrom(), true);
    LocalDate to = date(errors, "effectiveTo", request.getEffectiveTo(), false);
    if (from != null && to != null && to.isBefore(from)) {
      errors.add("effectiveTo", Constraint.RANGE);
    }
    errors.throwIfAny();
    return new LeavePolicyCommand(
        code, names[0], names[1], unit, mode, entitlement, serviceDays, route, payroll, from, to);
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
    canonical.put("code", code);
    canonical.put("nameEn", nameEn);
    canonical.put("nameFr", nameFr);
    canonical.put("unit", unit.name());
    canonical.put("balanceMode", balanceMode.name());
    canonical.put(
        "annualEntitlement", annualEntitlement == null ? null : annualEntitlement.toPlainString());
    canonical.put("minimumServiceDays", minimumServiceDays);
    canonical.put("approvalRoute", approvalRoute.name());
    canonical.put("payrollEffect", payrollEffect.name());
    canonical.put("effectiveFrom", effectiveFrom.toString());
    canonical.put("effectiveTo", effectiveTo == null ? null : effectiveTo.toString());
    return canonical;
  }

  private static String code(FieldErrors errors, Object raw) {
    if (raw == null) {
      errors.add("code", Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof String text)) {
      errors.add("code", Constraint.FORMAT);
      return null;
    }
    String code = text.strip().toUpperCase(Locale.ROOT);
    if (code.isEmpty()) {
      errors.add("code", Constraint.REQUIRED);
    } else if (code.length() < 2 || code.length() > 20) {
      errors.add("code", Constraint.LENGTH);
    } else if (!CODE.matcher(code).matches()) {
      errors.add("code", Constraint.FORMAT);
    }
    return code;
  }

  private static String[] names(FieldErrors errors, Object raw) {
    String[] names = new String[2];
    if (raw == null) {
      errors.add("names", Constraint.REQUIRED);
      return names;
    }
    if (!(raw instanceof Map<?, ?> map)) {
      errors.add("names", Constraint.FORMAT);
      return names;
    }
    for (Object key : map.keySet()) {
      if (!NAME_KEYS.contains(key)) {
        errors.add("names", Constraint.UNKNOWN_PROPERTY);
        break;
      }
    }
    names[0] = name(errors, "names.en", map.get("en"));
    names[1] = name(errors, "names.fr", map.get("fr"));
    return names;
  }

  /**
   * A name: trimmed, NFC-normalized, 2 to 100 code points, no control, format, separator-line or
   * unpaired surrogate characters.
   */
  static String name(FieldErrors errors, String field, Object raw) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof String text)) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isHighSurrogate(c)) {
        if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
          errors.add(field, Constraint.FORMAT);
          return null;
        }
        i++;
      } else if (Character.isLowSurrogate(c)) {
        errors.add(field, Constraint.FORMAT);
        return null;
      }
    }
    String name = Normalizer.normalize(text.strip(), Normalizer.Form.NFC);
    if (name.isEmpty()) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    boolean unsafe =
        name.codePoints()
            .anyMatch(
                cp -> {
                  int type = Character.getType(cp);
                  return type == Character.CONTROL
                      || type == Character.FORMAT
                      || type == Character.LINE_SEPARATOR
                      || type == Character.PARAGRAPH_SEPARATOR
                      || type == Character.PRIVATE_USE
                      || type == Character.UNASSIGNED;
                });
    if (unsafe) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    int length = name.codePointCount(0, name.length());
    if (length < NAME_MIN || length > NAME_MAX) {
      errors.add(field, Constraint.LENGTH);
      return null;
    }
    return name;
  }

  private static BigDecimal entitlement(FieldErrors errors, BalanceMode mode, Object raw) {
    if (mode == BalanceMode.UNTRACKED) {
      if (raw != null) {
        errors.add("annualEntitlement", Constraint.RANGE);
      }
      return null;
    }
    if (raw == null) {
      if (mode == BalanceMode.TRACKED) {
        errors.add("annualEntitlement", Constraint.REQUIRED);
      }
      return null;
    }
    BigDecimal value;
    if (raw instanceof Integer || raw instanceof Long || raw instanceof BigInteger) {
      value = new BigDecimal(raw.toString());
    } else if (raw instanceof BigDecimal decimal) {
      value = decimal;
    } else if (raw instanceof Double || raw instanceof Float) {
      double number = ((Number) raw).doubleValue();
      if (!Double.isFinite(number)) {
        errors.add("annualEntitlement", Constraint.FORMAT);
        return null;
      }
      // The shortest decimal that reads back as the JSON number (e.g. 12.5, never 12.4999...).
      value = new BigDecimal(raw.toString());
    } else {
      errors.add("annualEntitlement", Constraint.FORMAT);
      return null;
    }
    value = value.stripTrailingZeros();
    if (value.scale() > 2) {
      errors.add("annualEntitlement", Constraint.FORMAT);
      return null;
    }
    if (value.signum() <= 0 || value.compareTo(ENTITLEMENT_MAX) > 0) {
      errors.add("annualEntitlement", Constraint.RANGE);
      return null;
    }
    return mode == null ? null : value.setScale(2);
  }

  private static Integer serviceDays(FieldErrors errors, Object raw) {
    if (raw == null) {
      errors.add("minimumServiceDays", Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof Integer || raw instanceof Long)) {
      errors.add("minimumServiceDays", Constraint.FORMAT);
      return null;
    }
    long value = ((Number) raw).longValue();
    if (value < 0 || value > SERVICE_DAYS_MAX) {
      errors.add("minimumServiceDays", Constraint.RANGE);
      return null;
    }
    return (int) value;
  }

  private static <E extends Enum<E>> E enumValue(
      Class<E> type, Object raw, String field, FieldErrors errors) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (raw instanceof String text) {
      for (E value : type.getEnumConstants()) {
        if (value.name().equals(text)) {
          return value;
        }
      }
    }
    errors.add(field, Constraint.FORMAT);
    return null;
  }

  private static LocalDate date(FieldErrors errors, String field, Object raw, boolean required) {
    if (raw == null) {
      if (required) {
        errors.add(field, Constraint.REQUIRED);
      }
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
}
