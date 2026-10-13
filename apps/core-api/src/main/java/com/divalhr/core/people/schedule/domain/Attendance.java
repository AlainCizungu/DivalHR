package com.divalhr.core.people.schedule.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Online attendance on a scheduled shift (MVP-060A, A60A-2; ADR 0016) and its supervisor-assisted
 * recording (MVP-060B; ADR 0017). The windows are named constants of this increment, mirrored by
 * V28's database checks, and are not tenant-configurable. Every instant is the server's.
 */
public final class Attendance {

  /** The check-in opens this long before the scheduled start (inclusive). */
  public static final Duration CHECK_IN_OPENS_BEFORE_START = Duration.ofHours(2);

  /** The check-out closes this long after the scheduled end (inclusive). */
  public static final Duration CHECK_OUT_CLOSES_AFTER_END = Duration.ofHours(24);

  private Attendance() {}

  /** A record's derived status. */
  public enum Status {
    /** Checked in, not checked out. */
    OPEN,
    /** Checked in and out. */
    COMPLETED
  }

  /** The caller's attendance on one of their shifts. */
  public enum ShiftStatus {
    /** No check-in yet. */
    NOT_RECORDED,
    /** Checked in, not checked out. */
    OPEN,
    /** Checked in and out. */
    COMPLETED
  }

  /** The two evidence events. */
  public enum EventType {
    /** The check-in. */
    CHECKED_IN,
    /** The check-out. */
    CHECKED_OUT
  }

  /** How an event was recorded (MVP-060B). */
  public enum Method {
    /** By the employee. */
    SELF,
    /** By the employee's effective manager on their behalf, with a reason. */
    SUPERVISOR,
    /**
     * By the employee, captured offline and synchronized later (MVP-061A): the event's instant is
     * the claimed device time; the server receipt instant is kept as evidence.
     */
    EMPLOYEE_OFFLINE_SYNC
  }

  /** The action a direct report's shift needs now (MVP-060B). */
  public enum RequiredAction {
    /** A check-in. */
    CHECK_IN,
    /** A check-out. */
    CHECK_OUT
  }

  /**
   * How one event was recorded, as the employee may see it: the method and, for a supervisor's
   * event, the reason exactly as submitted; never the supervisor's identity (MVP-060B).
   *
   * @param method SELF or SUPERVISOR
   * @param supervisorReasonLocale the reason's language, or {@code null} for SELF
   * @param supervisorReason the reason, or {@code null} for SELF
   */
  public record Recording(Method method, String supervisorReasonLocale, String supervisorReason) {

    /** A self-service event. */
    public static final Recording SELF = new Recording(Method.SELF, null, null);

    /**
     * The method only: a reason never reaches a log line.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "Attendance.Recording[" + method + "]";
    }
  }

  /**
   * The first instant a check-in is accepted.
   *
   * @param startAt the scheduled start
   * @return the opening instant
   */
  public static Instant checkInOpensAt(Instant startAt) {
    return startAt.minus(CHECK_IN_OPENS_BEFORE_START);
  }

  /**
   * The last instant a check-out is accepted.
   *
   * @param endAt the scheduled end
   * @return the closing instant
   */
  public static Instant checkOutUntil(Instant endAt) {
    return endAt.plus(CHECK_OUT_CLOSES_AFTER_END);
  }

  /**
   * Whether a check-in at the instant is inside the window: from two hours before the start,
   * strictly before the end.
   *
   * @param startAt scheduled start
   * @param endAt scheduled end
   * @param at the server instant
   * @return true inside the window
   */
  public static boolean checkInOpen(Instant startAt, Instant endAt, Instant at) {
    return !at.isBefore(checkInOpensAt(startAt)) && at.isBefore(endAt);
  }

  /**
   * Whether a check-out at the instant is inside the window: strictly after the check-in, until 24
   * hours after the end.
   *
   * @param checkedInAt the check-in instant
   * @param endAt scheduled end
   * @param at the server instant
   * @return true inside the window
   */
  public static boolean checkOutOpen(Instant checkedInAt, Instant endAt, Instant at) {
    return at.isAfter(checkedInAt) && !at.isAfter(checkOutUntil(endAt));
  }

  /**
   * One attendance record (the projection), bound to its immutable shift, employee and qualifying
   * employment. Its instants are the effective ones: the events' instants, or after a correction
   * (MVP-062) the latest correction's values.
   *
   * @param id record
   * @param scheduleId schedule
   * @param slotId slot
   * @param employeeId the effective assignee at check-in
   * @param employmentId their qualifying employment
   * @param slotStartAt scheduled start
   * @param slotEndAt scheduled end
   * @param checkInEventId the check-in event, or {@code null} for a record created by a correction
   * @param checkedInAt the effective check-in instant
   * @param checkOutEventId the check-out event, or {@code null}
   * @param checkedOutAt the effective check-out instant, or {@code null}
   * @param correctionVersion the number of corrections (0 when uncorrected)
   * @param correctedAt the latest correction's instant, or {@code null}
   */
  public record Record(
      UUID id,
      UUID scheduleId,
      UUID slotId,
      UUID employeeId,
      UUID employmentId,
      Instant slotStartAt,
      Instant slotEndAt,
      UUID checkInEventId,
      Instant checkedInAt,
      UUID checkOutEventId,
      Instant checkedOutAt,
      int correctionVersion,
      Instant correctedAt) {

    /**
     * An uncorrected record.
     *
     * @param id record
     * @param scheduleId schedule
     * @param slotId slot
     * @param employeeId the effective assignee at check-in
     * @param employmentId their qualifying employment
     * @param slotStartAt scheduled start
     * @param slotEndAt scheduled end
     * @param checkInEventId the check-in event
     * @param checkedInAt the check-in instant
     * @param checkOutEventId the check-out event, or {@code null}
     * @param checkedOutAt the check-out instant, or {@code null}
     */
    public Record(
        UUID id,
        UUID scheduleId,
        UUID slotId,
        UUID employeeId,
        UUID employmentId,
        Instant slotStartAt,
        Instant slotEndAt,
        UUID checkInEventId,
        Instant checkedInAt,
        UUID checkOutEventId,
        Instant checkedOutAt) {
      this(
          id,
          scheduleId,
          slotId,
          employeeId,
          employmentId,
          slotStartAt,
          slotEndAt,
          checkInEventId,
          checkedInAt,
          checkOutEventId,
          checkedOutAt,
          0,
          null);
    }

    /**
     * The derived status: the effective check-out (MVP-062).
     *
     * @return OPEN or COMPLETED
     */
    public Status status() {
      return checkedOutAt == null ? Status.OPEN : Status.COMPLETED;
    }

    /**
     * Whether a check-out event can still complete the record: no effective and no recorded
     * check-out (a correction may have removed an effective check-out whose event stays).
     *
     * @return true when no check-out exists
     */
    public boolean acceptsCheckOut() {
      return checkedOutAt == null && checkOutEventId == null;
    }

    /**
     * Whether a check-out event is possible at the instant: none exists yet and the check-out
     * window still includes it.
     *
     * @param at the server instant
     * @return true when a check-out is possible
     */
    public boolean checkOutAvailable(Instant at) {
      return acceptsCheckOut() && checkOutOpen(checkedInAt, slotEndAt, at);
    }

    /**
     * Identifiers only: never an employee's data in a log line.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "Attendance.Record[id=" + id + ", status=" + status() + "]";
    }
  }

  /**
   * One evidence event.
   *
   * @param id event
   * @param recordId its record
   * @param type CHECKED_IN or CHECKED_OUT
   * @param recordedAt the server instant
   */
  public record Event(UUID id, UUID recordId, EventType type, Instant recordedAt) {}

  /**
   * The caller's attendance on a shift, as read with the shift.
   *
   * @param recordId record, or {@code null} when not recorded
   * @param checkedInAt effective check-in instant, or {@code null}
   * @param checkedOutAt effective check-out instant, or {@code null}
   * @param checkIn how the check-in was recorded, or {@code null} (none, or created by a
   *     correction)
   * @param checkOut how the check-out was recorded, or {@code null}
   * @param correctedAt the latest correction's instant, or {@code null} when uncorrected (MVP-062)
   */
  public record Mark(
      UUID recordId,
      Instant checkedInAt,
      Instant checkedOutAt,
      Recording checkIn,
      Recording checkOut,
      Instant correctedAt) {

    /** No attendance recorded. */
    public static final Mark NONE = new Mark(null, null, null, null, null, null);

    /**
     * The status on the shift.
     *
     * @return NOT_RECORDED, OPEN or COMPLETED
     */
    public ShiftStatus status() {
      if (recordId == null) {
        return ShiftStatus.NOT_RECORDED;
      }
      return checkedOutAt == null ? ShiftStatus.OPEN : ShiftStatus.COMPLETED;
    }
  }

  /**
   * One record of the caller's history with its shift.
   *
   * @param record the record
   * @param shift its immutable shift
   * @param checkIn how the check-in was recorded
   * @param checkOut how the check-out was recorded, or {@code null}
   */
  public record HistoryItem(
      Record record, Substitution.Shift shift, Recording checkIn, Recording checkOut) {}

  /**
   * One action a direct report's shift needs now (MVP-060B).
   *
   * @param action CHECK_IN or CHECK_OUT
   * @param deadline the last instant the action is possible
   * @param shift the immutable shift
   * @param employeeId the target employee
   * @param employeeNumber their number
   * @param givenNames their current given names
   * @param familyName their current family name
   * @param recordId the open record (check-out), or {@code null}
   * @param checkedInAt its check-in instant, or {@code null}
   */
  public record TeamAction(
      RequiredAction action,
      Instant deadline,
      Substitution.Shift shift,
      UUID employeeId,
      String employeeNumber,
      String givenNames,
      String familyName,
      UUID recordId,
      Instant checkedInAt) {

    /**
     * Identifiers and the action only: names and labels never reach a log line.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "Attendance.TeamAction[" + action + ", slot=" + shift.slotId() + "]";
    }
  }
}
