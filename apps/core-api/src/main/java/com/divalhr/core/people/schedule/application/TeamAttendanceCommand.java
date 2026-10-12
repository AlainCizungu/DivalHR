package com.divalhr.core.people.schedule.application;

import com.divalhr.core.people.schedule.api.SupervisorCheckInRequest;
import com.divalhr.core.people.schedule.api.SupervisorCheckOutRequest;
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
 * Deterministic validation of the supervisor-assisted attendance commands (MVP-060B, B60B-2,
 * B60B-5): shapes and the decision-reason grammar only, never a lookup. Errors name fields and
 * fixed constraints, never a submitted value; a malformed ID is the same {@code 404} as an unknown
 * one. {@code toString} never prints a reason.
 */
public final class TeamAttendanceCommand {

  private static final Set<String> LOCALES = Set.of("en", "fr");
  private static final Pattern UUID_SHAPE =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

  private TeamAttendanceCommand() {}

  /**
   * A validated supervisor check-in.
   *
   * @param slotId the shift's slot
   * @param reasonLocale the reason's language
   * @param reason the normalized reason
   */
  public record CheckIn(UUID slotId, String reasonLocale, String reason) {

    Map<String, Object> canonical(TenantId tenant) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("slotId", slotId.toString());
      canonical.put("reasonLocale", reasonLocale);
      canonical.put("reason", reason);
      return canonical;
    }

    @Override
    public String toString() {
      return "TeamAttendanceCommand.CheckIn[slot=" + slotId + "]";
    }
  }

  /**
   * A validated supervisor check-out.
   *
   * @param recordId the record (path)
   * @param reasonLocale the reason's language
   * @param reason the normalized reason
   */
  public record CheckOut(UUID recordId, String reasonLocale, String reason) {

    Map<String, Object> canonical(TenantId tenant) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("attendanceRecordId", recordId.toString());
      canonical.put("reasonLocale", reasonLocale);
      canonical.put("reason", reason);
      return canonical;
    }

    @Override
    public String toString() {
      return "TeamAttendanceCommand.CheckOut[record=" + recordId + "]";
    }
  }

  static CheckIn checkIn(String idempotencyKey, SupervisorCheckInRequest request) {
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
    if (!(rawSlot instanceof String text) || !UUID_SHAPE.matcher(text).matches()) {
      throw notFound();
    }
    return new CheckIn(UUID.fromString(text.toLowerCase(Locale.ROOT)), locale, reason);
  }

  static CheckOut checkOut(
      String idempotencyKey, String recordId, SupervisorCheckOutRequest request) {
    if (recordId == null || !UUID_SHAPE.matcher(recordId).matches()) {
      throw notFound();
    }
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
    String locale = reasonLocale(errors, request.getReasonLocale());
    String reason = WorkScheduleChangeCommand.reason(errors, request.getReason());
    errors.throwIfAny();
    return new CheckOut(UUID.fromString(recordId.toLowerCase(Locale.ROOT)), locale, reason);
  }

  /** The one indistinguishable not-found of the supervisor operations, without params. */
  static ApiException notFound() {
    return new ApiException(ErrorCode.TEAM_ATTENDANCE_ACTION_NOT_FOUND, Map.of());
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
