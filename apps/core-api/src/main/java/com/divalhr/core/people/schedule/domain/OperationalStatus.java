package com.divalhr.core.people.schedule.domain;

import com.divalhr.core.people.schedule.domain.Substitution.Shift;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * MVP-100 (Issue #154, ADR 0026): today's operational status, derived read-only at one server-owned
 * instant from the immutable schedules, terminal evidence, approved substitutions, attendance
 * records, pending leave requests and submitted timesheets; nothing is stored. Facts only: no
 * absence, leave, pay or disciplinary conclusion.
 *
 * <p>Workforce values count effective assigned <em>shift occurrences</em>, not people. {@code
 * expected + notCheckedIn + present + completed = scheduled} is a mutually exclusive partition;
 * {@code late} is a subset indicator that may overlap {@code present} and {@code completed}.
 */
public final class OperationalStatus {

  /**
   * The late tolerance, shared with the attendance exceptions (MVP-060C): strictly more than this
   * after the scheduled start is late; exactly this is not.
   */
  public static final Duration LATE_TOLERANCE = AttendanceExceptions.TOLERANCE;

  /**
   * The fixed no-check-in grace: a shift without a check-in is {@code expected} until strictly more
   * than this has elapsed since its scheduled start; exactly this is still expected. A domain
   * constant, not configurable in this increment.
   */
  public static final Duration NOT_CHECKED_IN_GRACE = Duration.ofMinutes(30);

  /** A pending leave request is overdue only when strictly older than this (exact instants). */
  public static final Duration LEAVE_OVERDUE_AFTER = Duration.ofHours(48);

  /** A submitted timesheet is overdue only when strictly older than this (exact instants). */
  public static final Duration TIMESHEET_OVERDUE_AFTER = Duration.ofHours(72);

  /** The most rows of any detail list; a longer list is flagged as truncated. */
  public static final int DETAIL_LIMIT = 20;

  private OperationalStatus() {}

  /** Whose status a view may read. */
  public sealed interface Scope {}

  /**
   * The organization (tenant administrator), optionally narrowed.
   *
   * @param siteId one site, or {@code null}
   * @param legalEntityId one legal entity, or {@code null}
   * @param legalEntitySites the sites of that legal entity (empty when none or no filter): the only
   *     reference an uncovered shift, which has no employee, has to a legal entity
   */
  public record Organization(UUID siteId, UUID legalEntityId, Set<UUID> legalEntitySites)
      implements Scope {}

  /**
   * The employees whose qualifying employment names the manager on the reference date; never the
   * manager.
   *
   * @param managerId the caller's linked employee
   */
  public record Team(UUID managerId) implements Scope {}

  /**
   * The shift-occurrence counts.
   *
   * @param scheduled all effective assigned shift occurrences of today
   * @param expected no check-in and not yet more than the grace after the scheduled start
   * @param notCheckedIn no check-in and more than the grace after the scheduled start
   * @param present checked in, no check-out recorded by the instant
   * @param completed checked in and out by the instant
   * @param late checked in more than the tolerance after the start (subset)
   */
  public record Shifts(
      int scheduled, int expected, int notCheckedIn, int present, int completed, int late) {}

  /**
   * Waiting approvals.
   *
   * @param pending all waiting
   * @param overdue waiting for strictly more than the threshold
   */
  public record Waiting(int pending, int overdue) {}

  /**
   * An employee's minimal current identity (a view the caller already has).
   *
   * @param id employee
   * @param number employee number
   * @param givenNames given names
   * @param familyName family name
   */
  public record Person(UUID id, String number, String givenNames, String familyName) {}

  /**
   * A shift occurrence with no check-in after the grace.
   *
   * @param shift the shift
   * @param person its effective assignee
   * @param minutesSinceStart whole minutes since the scheduled start (rounded down)
   */
  public record NotCheckedIn(Shift shift, Person person, long minutesSinceStart) {}

  /**
   * An overdue leave request of a manager's report.
   *
   * @param requestId the request
   * @param person the requester
   * @param submittedAt submission instant
   * @param ageHours whole hours waiting (rounded down)
   */
  public record OverdueLeave(UUID requestId, Person person, Instant submittedAt, long ageHours) {}

  /**
   * An overdue timesheet of a manager's report.
   *
   * @param timesheetId the timesheet
   * @param person the employee
   * @param siteId the timesheet's site
   * @param weekStart the site-local Monday
   * @param submittedAt submission instant
   * @param ageHours whole hours waiting (rounded down)
   */
  public record OverdueTimesheet(
      UUID timesheetId,
      Person person,
      UUID siteId,
      LocalDate weekStart,
      Instant submittedAt,
      long ageHours) {}

  /**
   * A capped, deterministically ordered detail list.
   *
   * @param items at most {@link #DETAIL_LIMIT} rows
   * @param truncated whether more exist than are listed
   * @param <T> row type
   */
  public record Detail<T>(List<T> items, boolean truncated) {

    /**
     * A list from at most {@code DETAIL_LIMIT + 1} rows read.
     *
     * @param rows the rows read
     * @param <T> row type
     * @return the capped list
     */
    public static <T> Detail<T> of(List<T> rows) {
      boolean more = rows.size() > DETAIL_LIMIT;
      return new Detail<>(List.copyOf(more ? rows.subList(0, DETAIL_LIMIT) : rows), more);
    }
  }

  /**
   * The whole status at the instant.
   *
   * @param asOf the pinned instant
   * @param shifts the shift counts
   * @param uncovered shifts without an effective assignee, or {@code null} when the scope has no
   *     such notion (a manager)
   * @param leave pending leave requests
   * @param timesheets submitted timesheets waiting for approval
   * @param notCheckedIn the shifts not checked in
   * @param uncoveredShifts the uncovered shifts (administrator only)
   * @param overdueLeave a manager's overdue leave, or {@code null} for an administrator
   * @param overdueTimesheets a manager's overdue timesheets, or {@code null} for an administrator
   */
  public record Status(
      Instant asOf,
      Shifts shifts,
      Integer uncovered,
      Waiting leave,
      Waiting timesheets,
      Detail<NotCheckedIn> notCheckedIn,
      Detail<Shift> uncoveredShifts,
      Detail<OverdueLeave> overdueLeave,
      Detail<OverdueTimesheet> overdueTimesheets) {}
}
