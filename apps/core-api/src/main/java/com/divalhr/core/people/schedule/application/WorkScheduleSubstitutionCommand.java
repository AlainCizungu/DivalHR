package com.divalhr.core.people.schedule.application;

import com.divalhr.core.people.schedule.api.CreateWorkScheduleSubstitutionRequest;
import com.divalhr.core.people.schedule.api.DecideWorkScheduleSubstitutionRequest;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.tenancy.TenantId;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Deterministic validation of the shift-substitution commands (MVP-051, D51-2, D51-5): shapes and
 * the decision-reason grammar only, never a lookup. Errors name fields and fixed constraints, never
 * a submitted value. {@code toString} never prints a reason.
 */
public final class WorkScheduleSubstitutionCommand {

  private static final Set<String> LOCALES = Set.of("en", "fr");
  private static final Pattern UUID_SHAPE =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

  private WorkScheduleSubstitutionCommand() {}

  /**
   * A validated request.
   *
   * @param slotId the shift's slot
   * @param reasonLocale the reason's language
   * @param reason the normalized reason
   */
  public record Create(UUID slotId, String reasonLocale, String reason) {

    @Override
    public String toString() {
      return "Create[slotId=" + slotId + "]";
    }

    Map<String, Object> canonical(TenantId tenant) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("slotId", slotId.toString());
      canonical.put("reasonLocale", reasonLocale);
      canonical.put("reason", reason);
      return canonical;
    }
  }

  /**
   * A validated decision.
   *
   * @param requestId the request (path)
   * @param approve whether it approves
   * @param substituteId the substitute (approval only)
   * @param reasonLocale the reason's language
   * @param reason the normalized reason
   */
  public record Decide(
      UUID requestId, boolean approve, UUID substituteId, String reasonLocale, String reason) {

    @Override
    public String toString() {
      return "Decide[requestId=" + requestId + ", approve=" + approve + "]";
    }

    /**
     * The outcome stored with the decision.
     *
     * @return {@code APPROVED} or {@code REJECTED}
     */
    public String outcome() {
      return approve ? "APPROVED" : "REJECTED";
    }

    Map<String, Object> canonical(TenantId tenant) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("requestId", requestId.toString());
      canonical.put("decision", outcome());
      canonical.put("substituteEmployeeId", substituteId == null ? "" : substituteId.toString());
      canonical.put("reasonLocale", reasonLocale);
      canonical.put("reason", reason);
      return canonical;
    }
  }

  static Create create(String idempotencyKey, CreateWorkScheduleSubstitutionRequest request) {
    FieldErrors errors = new FieldErrors();
    key(errors, idempotencyKey);
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!request.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    Object rawSlot = request.getSlotId();
    if (rawSlot == null) {
      errors.add("slotId", Constraint.REQUIRED);
    }
    String locale = reasonLocale(errors, request.getReasonLocale());
    String reason = WorkScheduleChangeCommand.reason(errors, request.getReason());
    errors.throwIfAny();
    // A malformed shift ID is the same 404 as an unknown one (D51-6).
    if (!(rawSlot instanceof String text) || !UUID_SHAPE.matcher(text).matches()) {
      throw new ApiException(ErrorCode.WORK_SCHEDULE_SHIFT_NOT_FOUND, Map.of());
    }
    return new Create(UUID.fromString(text.toLowerCase(Locale.ROOT)), locale, reason);
  }

  static Decide decide(
      String idempotencyKey, String requestId, DecideWorkScheduleSubstitutionRequest request) {
    UUID id = requestId(requestId);
    FieldErrors errors = new FieldErrors();
    key(errors, idempotencyKey);
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!request.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    Boolean approve = null;
    Object rawDecision = request.getDecision();
    if (rawDecision == null) {
      errors.add("decision", Constraint.REQUIRED);
    } else if (!(rawDecision instanceof String text)
        || !(text.equals("APPROVED") || text.equals("REJECTED"))) {
      errors.add("decision", Constraint.FORMAT);
    } else {
      approve = text.equals("APPROVED");
    }
    UUID substitute = null;
    if (Boolean.TRUE.equals(approve)) {
      substitute = uuid(errors, "substituteEmployeeId", request.getSubstituteEmployeeId(), true);
    } else if (Boolean.FALSE.equals(approve) && request.getSubstituteEmployeeId() != null) {
      // A rejection names no substitute.
      errors.add("substituteEmployeeId", Constraint.FORMAT);
    }
    String locale = reasonLocale(errors, request.getReasonLocale());
    String reason = WorkScheduleChangeCommand.reason(errors, request.getReason());
    errors.throwIfAny();
    return new Decide(id, Boolean.TRUE.equals(approve), substitute, locale, reason);
  }

  /**
   * A request path ID; malformed is the same {@code 404} as unknown.
   *
   * @param raw path value
   * @return the ID
   */
  static UUID requestId(String raw) {
    if (raw == null || !UUID_SHAPE.matcher(raw).matches()) {
      throw notFound();
    }
    return UUID.fromString(raw.toLowerCase(Locale.ROOT));
  }

  static ApiException notFound() {
    return new ApiException(ErrorCode.WORK_SCHEDULE_SUBSTITUTION_NOT_FOUND, Map.of());
  }

  private static UUID uuid(FieldErrors errors, String field, Object raw, boolean required) {
    if (raw == null) {
      if (required) {
        errors.add(field, Constraint.REQUIRED);
      }
      return null;
    }
    if (!(raw instanceof String text) || !UUID_SHAPE.matcher(text).matches()) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    return UUID.fromString(text.toLowerCase(Locale.ROOT));
  }

  private static String reasonLocale(FieldErrors errors, Object raw) {
    if (raw == null) {
      errors.add("reasonLocale", Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof String text) || !LOCALES.contains(text)) {
      errors.add("reasonLocale", Constraint.FORMAT);
      return null;
    }
    return text;
  }

  private static void key(FieldErrors errors, String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      errors.add(IdempotencyKeys.HEADER, Constraint.REQUIRED);
    } else if (!IdempotencyKeys.isWellFormed(idempotencyKey)) {
      errors.add(IdempotencyKeys.HEADER, Constraint.FORMAT);
    }
  }
}
