package com.divalhr.core.people.schedule.application;

import com.divalhr.core.people.schedule.api.CheckInAttendanceRequest;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.tenancy.TenantId;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Deterministic validation of the attendance commands (MVP-060A, A60A-4, A60A-5): shapes only,
 * never a lookup. Errors name fields and fixed constraints, never a submitted value; a malformed ID
 * is the same {@code 404} as an unknown one.
 */
public final class AttendanceCommand {

  private static final Pattern UUID_SHAPE =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

  private AttendanceCommand() {}

  /**
   * A validated check-in.
   *
   * @param slotId the shift's slot
   */
  public record CheckIn(UUID slotId) {

    Map<String, Object> canonical(TenantId tenant) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("slotId", slotId.toString());
      return canonical;
    }
  }

  /**
   * A validated check-out.
   *
   * @param recordId the record (path)
   */
  public record CheckOut(UUID recordId) {

    Map<String, Object> canonical(TenantId tenant) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("attendanceRecordId", recordId.toString());
      return canonical;
    }
  }

  static CheckIn checkIn(String idempotencyKey, CheckInAttendanceRequest request) {
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
    errors.throwIfAny();
    if (!(rawSlot instanceof String text) || !UUID_SHAPE.matcher(text).matches()) {
      throw new ApiException(ErrorCode.ATTENDANCE_SHIFT_NOT_FOUND, Map.of());
    }
    return new CheckIn(UUID.fromString(text.toLowerCase(Locale.ROOT)));
  }

  static CheckOut checkOut(String idempotencyKey, String recordId) {
    if (recordId == null || !UUID_SHAPE.matcher(recordId).matches()) {
      throw new ApiException(ErrorCode.ATTENDANCE_RECORD_NOT_FOUND, Map.of());
    }
    FieldErrors errors = new FieldErrors();
    key(errors, idempotencyKey);
    errors.throwIfAny();
    return new CheckOut(UUID.fromString(recordId.toLowerCase(Locale.ROOT)));
  }

  private static void key(FieldErrors errors, String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      errors.add(IdempotencyKeys.HEADER, Constraint.REQUIRED);
    } else if (!IdempotencyKeys.isWellFormed(idempotencyKey)) {
      errors.add(IdempotencyKeys.HEADER, Constraint.FORMAT);
    }
  }
}
