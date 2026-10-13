package com.divalhr.core.people.schedule.application;

import com.divalhr.core.people.schedule.api.SyncOfflineAttendanceRequest;
import com.divalhr.core.people.schedule.domain.OfflineAttendance;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic validation of an offline attendance submission (MVP-061A, A61A-1, A61A-3): shapes
 * only, never a lookup. Errors name fields and fixed constraints, never a submitted value. A
 * malformed slot is the same {@code 404} as an unknown one (the check-in's shift or the check-out's
 * record), as in MVP-060A.
 *
 * <p>{@code deviceOccurredAt} is RFC 3339 with an explicit offset, fail closed: upper-case {@code
 * T} and {@code Z}, seconds required, at most six fractional digits (the stored precision, so a
 * replay compares exactly), an offset of at most 14 hours, a real calendar date and time (no leap
 * second), and never the "unknown local offset" {@code -00:00}.
 */
public final class OfflineAttendanceCommand {

  private static final Pattern UUID_SHAPE =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  private static final Pattern RFC_3339 =
      Pattern.compile(
          "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,6})?"
              + "(Z|([+-])([0-9]{2}):([0-9]{2}))$");

  private OfflineAttendanceCommand() {}

  /**
   * A validated submission.
   *
   * @param clientEventId the client's event identity
   * @param slotId the slot
   * @param type the event
   * @param occurredAt the claimed device instant
   * @param offsetMinutes the device UTC offset in minutes
   */
  public record Sync(
      UUID clientEventId,
      UUID slotId,
      OfflineAttendance.Type type,
      Instant occurredAt,
      int offsetMinutes) {

    /**
     * Never an identifier or instant in a log line.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "OfflineAttendanceCommand.Sync[" + type + "]";
    }
  }

  static Sync sync(SyncOfflineAttendanceRequest request) {
    FieldErrors errors = new FieldErrors();
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!request.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    UUID clientEventId = null;
    Object rawClient = request.getClientEventId();
    if (rawClient == null) {
      errors.add("clientEventId", Constraint.REQUIRED);
    } else if (rawClient instanceof String text && UUID_SHAPE.matcher(text).matches()) {
      clientEventId = UUID.fromString(text.toLowerCase(Locale.ROOT));
    } else {
      errors.add("clientEventId", Constraint.FORMAT);
    }
    Object rawSlot = request.getSlotId();
    if (rawSlot == null) {
      errors.add("slotId", Constraint.REQUIRED);
    }
    OfflineAttendance.Type type = null;
    Object rawType = request.getType();
    if (rawType == null) {
      errors.add("type", Constraint.REQUIRED);
    } else if ("CHECK_IN".equals(rawType)) {
      type = OfflineAttendance.Type.CHECK_IN;
    } else if ("CHECK_OUT".equals(rawType)) {
      type = OfflineAttendance.Type.CHECK_OUT;
    } else {
      errors.add("type", Constraint.FORMAT);
    }
    OffsetDateTime occurred = null;
    Object rawOccurred = request.getDeviceOccurredAt();
    if (rawOccurred == null) {
      errors.add("deviceOccurredAt", Constraint.REQUIRED);
    } else {
      occurred = occurred(rawOccurred);
      if (occurred == null) {
        errors.add("deviceOccurredAt", Constraint.FORMAT);
      }
    }
    Integer offset = null;
    Object rawOffset = request.getDeviceOffsetMinutes();
    if (rawOffset == null) {
      errors.add("deviceOffsetMinutes", Constraint.REQUIRED);
    } else if (!(rawOffset instanceof Integer value)) {
      // Strings, fractions, booleans and values beyond int are malformed, never coerced.
      errors.add(
          "deviceOffsetMinutes",
          rawOffset instanceof Long || rawOffset instanceof java.math.BigInteger
              ? Constraint.RANGE
              : Constraint.FORMAT);
    } else if (Math.abs(value) > OfflineAttendance.MAX_OFFSET_MINUTES) {
      errors.add("deviceOffsetMinutes", Constraint.RANGE);
    } else {
      offset = value;
    }
    errors.throwIfAny();
    if (occurred.getOffset().getTotalSeconds() != offset * 60) {
      throw new ApiException(ErrorCode.OFFLINE_ATTENDANCE_OFFSET_MISMATCH, Map.of());
    }
    if (!(rawSlot instanceof String slotText) || !UUID_SHAPE.matcher(slotText).matches()) {
      throw new ApiException(
          type == OfflineAttendance.Type.CHECK_IN
              ? ErrorCode.ATTENDANCE_SHIFT_NOT_FOUND
              : ErrorCode.ATTENDANCE_RECORD_NOT_FOUND,
          Map.of());
    }
    return new Sync(
        clientEventId,
        UUID.fromString(slotText.toLowerCase(Locale.ROOT)),
        type,
        occurred.toInstant(),
        offset);
  }

  /** The strictly parsed timestamp, or {@code null} when it is not exactly acceptable. */
  static OffsetDateTime occurred(Object raw) {
    if (!(raw instanceof String text)) {
      return null;
    }
    Matcher shape = RFC_3339.matcher(text);
    if (!shape.matches()) {
      return null;
    }
    if (shape.group(3) != null) {
      int hours = Integer.parseInt(shape.group(4));
      int minutes = Integer.parseInt(shape.group(5));
      int total = hours * 60 + minutes;
      if (minutes > 59
          || total > OfflineAttendance.MAX_OFFSET_MINUTES
          || ("-".equals(shape.group(3)) && total == 0)) {
        return null;
      }
    }
    try {
      // ISO_OFFSET_DATE_TIME resolves strictly: no 30 February, hour 24 or second 60.
      return OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    } catch (DateTimeException malformed) {
      return null;
    }
  }
}
