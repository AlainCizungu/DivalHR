package com.divalhr.core.people.schedule.domain;

import com.divalhr.core.people.schedule.domain.AttendanceExceptions.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * MVP-062 (Issue #126, C62-2, C62-3): a tenant administrator's append-only correction of factual
 * attendance evidence. Exactly two operations and five closed reasons; the time rules mirror the
 * MVP-060A windows (V31's checks) and are not configurable. Facts only: a correction may produce or
 * remove a factual exception, never a pay, absence, policy or disciplinary outcome.
 */
public final class AttendanceCorrection {

  /** A corrected value may lie at most this far after the server's preview or commit instant. */
  public static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(5);

  /** Shortest explanation, in characters (Unicode code points), after trimming. */
  public static final int EXPLANATION_MIN = 10;

  /** Longest explanation, in characters (Unicode code points), after trimming. */
  public static final int EXPLANATION_MAX = 500;

  private AttendanceCorrection() {}

  /** The two supported operations (C62-2). */
  public enum Operation {
    /** Creates the missing record of a slot's effective assignee. */
    CREATE_MISSING_RECORD,
    /** Replaces an existing record's effective check-in and optional check-out. */
    REPLACE_TIMES
  }

  /** The closed reason codes (C62-2). */
  public enum Reason {
    /** A device or connectivity problem. */
    DEVICE_OR_CONNECTIVITY,
    /** A report by a supervisor or the employee. */
    SUPERVISOR_OR_EMPLOYEE_REPORT,
    /** A data entry error. */
    DATA_ENTRY_ERROR,
    /** A system error. */
    SYSTEM_ERROR,
    /** Another reason, described in the explanation. */
    OTHER
  }

  /** The neutral, factual warnings of a preview (C62-4). */
  public enum Warning {
    /** The correction removes the record's current check-out. */
    CHECK_OUT_REMOVED,
    /** The corrected record has no check-out. */
    NO_CHECK_OUT,
    /** The record's schedule was cancelled or replaced after it was recorded. */
    SCHEDULE_NOT_OPERATIONAL,
    /** The original attendance events are kept unchanged; the record shows the corrected values. */
    ORIGINAL_EVENTS_KEPT,
    /**
     * Approved time covers this shift: a correction that changes it reopens the timesheet for the
     * organization's refresh and a new approval (MVP-070D).
     */
    APPROVED_TIME_MAY_REOPEN
  }

  /**
   * A check-in and an optional check-out.
   *
   * @param checkedInAt check-in instant
   * @param checkedOutAt check-out instant, or {@code null}
   */
  public record Times(Instant checkedInAt, Instant checkedOutAt) {

    /**
     * Never an instant in a log line.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "AttendanceCorrection.Times[checkOut=" + (checkedOutAt != null) + "]";
    }
  }

  /**
   * Which time rule a proposed value breaks, or {@code null} when it keeps every rule.
   *
   * @param startAt scheduled start
   * @param endAt scheduled end
   * @param proposed the proposed values
   * @param at the server's preview or commit instant
   * @return {@code checkedInAt}, {@code checkedOutAt} or {@code null}
   */
  public static String brokenRule(Instant startAt, Instant endAt, Times proposed, Instant at) {
    Instant latest = at.plus(FUTURE_TOLERANCE);
    if (!Attendance.checkInOpen(startAt, endAt, proposed.checkedInAt())
        || proposed.checkedInAt().isAfter(latest)) {
      return "checkedInAt";
    }
    Instant out = proposed.checkedOutAt();
    if (out != null
        && (!Attendance.checkOutOpen(proposed.checkedInAt(), endAt, out) || out.isAfter(latest))) {
      return "checkedOutAt";
    }
    return null;
  }

  /**
   * The factual exceptions of one shift and employee at an instant, exactly as MVP-060C derives
   * them (C60C-2): {@code times == null} is no record.
   *
   * @param startAt scheduled start
   * @param endAt scheduled end
   * @param times the record's values, or {@code null} for no record
   * @param asOf the instant
   * @return the types in their stable order
   */
  public static List<Type> exceptions(Instant startAt, Instant endAt, Times times, Instant asOf) {
    List<Type> types = new ArrayList<>(2);
    if (times == null || times.checkedInAt().isAfter(asOf)) {
      if (!asOf.isBefore(endAt)) {
        types.add(Type.MISSING_CHECK_IN);
      }
      return types;
    }
    if (times.checkedInAt().isAfter(startAt.plus(AttendanceExceptions.TOLERANCE))) {
      types.add(Type.LATE_CHECK_IN);
    }
    Instant out = times.checkedOutAt();
    boolean outByThen = out != null && !out.isAfter(asOf);
    if (outByThen && out.isBefore(endAt.minus(AttendanceExceptions.TOLERANCE))) {
      types.add(Type.EARLY_CHECK_OUT);
    }
    if (!outByThen && Attendance.checkOutUntil(endAt).isBefore(asOf)) {
      types.add(Type.MISSING_CHECK_OUT);
    }
    return types;
  }

  /**
   * One committed correction, as stored.
   *
   * @param id correction
   * @param recordId its record
   * @param version its number in the record's history (1, 2, ...)
   * @param operation the operation
   * @param before the record's values before, or {@code null} for a created record
   * @param after the record's values after
   * @param reason the reason code
   * @param explanation the administrator's explanation (Restricted)
   * @param correctedBy the verified subject (evidence only, never returned)
   * @param correctedAt the server instant
   */
  public record Entry(
      UUID id,
      UUID recordId,
      int version,
      Operation operation,
      Times before,
      Times after,
      Reason reason,
      String explanation,
      String correctedBy,
      Instant correctedAt) {

    /**
     * Identifiers and the operation only: never the explanation, subject or instants.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "AttendanceCorrection.Entry[id=" + id + ", " + operation + ", v" + version + "]";
    }
  }
}
