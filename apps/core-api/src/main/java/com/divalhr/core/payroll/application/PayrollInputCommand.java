package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.GeneratePayrollInputsRequest;
import com.divalhr.core.payroll.api.PayrollInputPreviewRequest;
import com.divalhr.core.payroll.domain.PayrollInputs;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validation of the payroll-input requests (MVP-090A): shapes first (one field error per problem,
 * every unknown property named), then the one safe 404 for a malformed or unknown legal entity, so
 * that nothing about another tenant's units is disclosed.
 */
public final class PayrollInputCommand {

  private static final Pattern UUID_SHAPE =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
  private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");

  private PayrollInputCommand() {}

  /**
   * The validated selection: the legal entity and the inclusive period.
   *
   * @param legalEntityId legal entity
   * @param periodStart first date
   * @param periodEnd last date
   */
  public record Target(UUID legalEntityId, LocalDate periodStart, LocalDate periodEnd) {

    Map<String, Object> canonical(TenantId tenant) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("legalEntityId", legalEntityId.toString());
      canonical.put("periodStart", periodStart.toString());
      canonical.put("periodEnd", periodEnd.toString());
      return canonical;
    }
  }

  /**
   * A validated generation command.
   *
   * @param target the selection
   * @param previewDigest the reviewed preview's digest
   * @param sourceVersionsDigest the source versions the preview showed
   */
  public record Generate(Target target, String previewDigest, String sourceVersionsDigest) {

    Map<String, Object> canonical(TenantId tenant) {
      Map<String, Object> canonical = target.canonical(tenant);
      canonical.put("previewDigest", previewDigest);
      canonical.put("sourceVersionsDigest", sourceVersionsDigest);
      canonical.put("confirm", true);
      return canonical;
    }

    @Override
    public String toString() {
      return "PayrollInputCommand.Generate["
          + target.periodStart()
          + ".."
          + target.periodEnd()
          + "]";
    }
  }

  static Target target(PayrollInputPreviewRequest request) {
    FieldErrors errors = new FieldErrors();
    Target target = target(request, errors);
    errors.throwIfAny();
    return found(target);
  }

  static Generate generate(String idempotencyKey, GeneratePayrollInputsRequest request) {
    FieldErrors errors = new FieldErrors();
    key(idempotencyKey, errors);
    Target target = target(request, errors);
    String digest = digest(request.getPreviewDigest(), "previewDigest", errors);
    String versions = digest(request.getSourceVersionsDigest(), "sourceVersionsDigest", errors);
    if (!Boolean.TRUE.equals(request.getConfirm())) {
      errors.add("confirm", Constraint.REQUIRED);
    }
    errors.throwIfAny();
    return new Generate(found(target), digest, versions);
  }

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
    return new ApiException(ErrorCode.PAYROLL_INPUT_TARGET_NOT_FOUND, Map.of());
  }

  // ------------------------------------------------------------------------------------------

  private static Target target(PayrollInputPreviewRequest request, FieldErrors errors) {
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!request.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    UUID legalEntity = null;
    if (request.getLegalEntityId() == null) {
      errors.add("legalEntityId", Constraint.REQUIRED);
    } else if (request.getLegalEntityId() instanceof String text
        && UUID_SHAPE.matcher(text).matches()) {
      legalEntity = UUID.fromString(text.toLowerCase(Locale.ROOT));
    }
    LocalDate start = date(request.getPeriodStart(), "periodStart", errors);
    LocalDate end = date(request.getPeriodEnd(), "periodEnd", errors);
    if (start != null && end != null && !PayrollInputs.supported(start, end)) {
      errors.add("periodEnd", Constraint.RANGE);
    }
    // A malformed legal entity is decided after every shape check (the same 404 as an unknown one).
    return new Target(legalEntity, start, end);
  }

  private static Target found(Target target) {
    if (target.legalEntityId() == null) {
      throw notFound();
    }
    return target;
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
      if (day.isBefore(PayrollInputs.EARLIEST)) {
        errors.add(field, Constraint.RANGE);
        return null;
      }
      return day;
    } catch (DateTimeParseException invalid) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
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
    return (String) raw;
  }

  private static void key(String idempotencyKey, FieldErrors errors) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      errors.add(IdempotencyKeys.HEADER, Constraint.REQUIRED);
    } else if (!IdempotencyKeys.isWellFormed(idempotencyKey)) {
      errors.add(IdempotencyKeys.HEADER, Constraint.FORMAT);
    }
  }
}
