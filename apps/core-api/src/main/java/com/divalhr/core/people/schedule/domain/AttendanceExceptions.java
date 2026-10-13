package com.divalhr.core.people.schedule.domain;

import com.divalhr.core.people.schedule.domain.Substitution.Shift;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * MVP-060C (Issue #119, C60C-1 to C60C-6): factual attendance exceptions, derived at one
 * server-owned instant from the immutable schedules, terminal evidence, approved substitutions and
 * attendance evidence; nothing is stored. Facts only: no guilt, absence, policy, pay or
 * disciplinary conclusion.
 */
public final class AttendanceExceptions {

  /**
   * The tolerance of a late check-in or an early check-out: strictly more than this is an
   * exception; exactly this is not. A domain constant, not configurable in this increment.
   */
  public static final Duration TOLERANCE = Duration.ofMinutes(5);

  /** Longest explicit date range: 93 inclusive site-local dates. */
  public static final int MAX_RANGE_DAYS = 93;

  /** Default range: the last 31 site-local dates through the as-of date, per site. */
  public static final int DEFAULT_RANGE_DAYS = 31;

  private AttendanceExceptions() {}

  /** The exception types, in their stable order within one shift and employee. */
  public enum Type {
    /** The shift ended and its effective assignee has no record for it. */
    MISSING_CHECK_IN,
    /** Checked in more than {@link #TOLERANCE} after the scheduled start. */
    LATE_CHECK_IN,
    /** Checked out more than {@link #TOLERANCE} before the scheduled end. */
    EARLY_CHECK_OUT,
    /** No check-out by the end of the check-out window (inclusive deadline). */
    MISSING_CHECK_OUT;

    /**
     * The stable rank (the fourth key of the order).
     *
     * @return the rank
     */
    public int rank() {
      return ordinal();
    }

    /**
     * The type of a rank.
     *
     * @param rank 0 to 3
     * @return the type
     */
    public static Type ofRank(int rank) {
      return values()[rank];
    }
  }

  /**
   * Whose exceptions a view may read: the caller's linked employee, the caller as a manager, or the
   * tenant (optionally one site).
   */
  public sealed interface Scope {}

  /**
   * The employee's own exceptions.
   *
   * @param employeeId the caller's linked employee
   */
  public record Self(UUID employeeId) implements Scope {}

  /**
   * The exceptions of the employees whose qualifying employment names the manager on the shift's
   * site-local start date; never the manager's own.
   *
   * @param managerId the caller's linked employee
   */
  public record Team(UUID managerId) implements Scope {}

  /**
   * The tenant's exceptions, optionally of one site.
   *
   * @param siteId the site, or {@code null}
   */
  public record Tenant(UUID siteId) implements Scope {}

  /**
   * The validated filters.
   *
   * @param type one type, or {@code null}
   * @param from first site-local start date, or {@code null} for the default range
   * @param to last site-local start date, or {@code null} for the default range
   */
  public record Filter(Type type, LocalDate from, LocalDate to) {}

  /**
   * A keyset position: the order's four keys.
   *
   * @param startAt scheduled start
   * @param slotId slot
   * @param employeeId target employee
   * @param type exception type
   */
  public record Position(Instant startAt, UUID slotId, UUID employeeId, Type type) {}

  /**
   * One exception.
   *
   * @param type the type
   * @param shift the shift
   * @param employeeId the target employee (the record's, or the effective assignee's)
   * @param employeeNumber the employee's number
   * @param givenNames the employee's current given names
   * @param familyName the employee's current family name
   * @param recordId the attendance record, or {@code null} for a missing check-in
   * @param checkedInAt the recorded check-in, or {@code null}
   * @param checkedOutAt the recorded check-out at the as-of instant, or {@code null}
   * @param varianceMinutes whole minutes late or early (rounded up), or {@code null}
   */
  public record Row(
      Type type,
      Shift shift,
      UUID employeeId,
      String employeeNumber,
      String givenNames,
      String familyName,
      UUID recordId,
      Instant checkedInAt,
      Instant checkedOutAt,
      Integer varianceMinutes) {

    /**
     * This row's keyset position.
     *
     * @return the position
     */
    public Position position() {
      return new Position(shift.startAt(), shift.slotId(), employeeId, type);
    }

    /** No name, number, label or instant: the row is Restricted HR/Operations. */
    @Override
    public String toString() {
      return "Row[type=" + type + ", slot=" + shift.slotId() + "]";
    }
  }
}
