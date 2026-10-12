package com.divalhr.core.people.schedule.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Shift substitution (MVP-051, ADR 0015): an employee's request for a substitute on one of their
 * own future published shifts, and their effective manager's decision. The records here are read
 * models of the append-only evidence; none of them is a mutable aggregate. Their {@code toString}
 * never prints a name, a number, a label or a reason.
 */
public final class Substitution {

  private Substitution() {}

  /** A request's derived state at one server instant (D51-3). */
  public enum State {
    /** Pending, and the shift has not started. */
    PENDING,
    /** An approved decision exists. */
    APPROVED,
    /** A rejected decision exists. */
    REJECTED,
    /** No decision, still pending, and the shift has started. */
    EXPIRED,
    /** No decision and no longer pending: the schedule was cancelled or replaced. */
    VOIDED;

    /**
     * Derives the state from the evidence.
     *
     * @param outcome the decision's outcome, or {@code null}
     * @param pending whether the pending projection exists
     * @param startAt the shift's start
     * @param asOf the server instant
     * @return the state
     */
    public static State of(String outcome, boolean pending, Instant startAt, Instant asOf) {
      if ("APPROVED".equals(outcome)) {
        return APPROVED;
      }
      if ("REJECTED".equals(outcome)) {
        return REJECTED;
      }
      if (pending) {
        return startAt.isAfter(asOf) ? PENDING : EXPIRED;
      }
      return VOIDED;
    }
  }

  /** How an employee came to work a shift (D51-5). */
  public enum Source {
    /** The employee the shift was published to. */
    PUBLISHED,
    /** The approved substitute. */
    SUBSTITUTION
  }

  /**
   * The immutable published shift, with its schedule's site and time zone and whether the schedule
   * is operational.
   *
   * @param slotId slot
   * @param scheduleId schedule
   * @param siteId site
   * @param siteTimezone the schedule's site time zone snapshot
   * @param start local start
   * @param end local end
   * @param startAt start instant
   * @param endAt end instant
   * @param labelEn English label
   * @param labelFr French label
   * @param assignedEmployeeId the original assignee, or {@code null} (uncovered)
   * @param operational whether the schedule has no terminal action
   */
  public record Shift(
      UUID slotId,
      UUID scheduleId,
      UUID siteId,
      String siteTimezone,
      LocalDateTime start,
      LocalDateTime end,
      Instant startAt,
      Instant endAt,
      String labelEn,
      String labelFr,
      UUID assignedEmployeeId,
      boolean operational) {

    /**
     * The shift's site-local start date (the manager line's and placement's date).
     *
     * @return the date
     */
    public LocalDate firstDate() {
      return start.toLocalDate();
    }

    /**
     * The last local date the shift occupies: a shift ending at midnight ends on the date before.
     *
     * @return the date
     */
    public LocalDate lastDate() {
      LocalDate last = end.toLocalDate();
      if (end.toLocalTime().equals(LocalTime.MIDNIGHT) && last.isAfter(start.toLocalDate())) {
        return last.minusDays(1);
      }
      return last;
    }

    /**
     * Whether the shift ends on a later local date than it starts.
     *
     * @return true when overnight
     */
    public boolean overnight() {
      return end.toLocalDate().isAfter(start.toLocalDate());
    }

    @Override
    public String toString() {
      return "Shift[slotId=" + slotId + "]";
    }
  }

  /**
   * A request's evidence.
   *
   * @param id request
   * @param scheduleId schedule
   * @param slotId slot
   * @param employeeId the original employee
   * @param employmentId the stored qualifying employment
   * @param reasonLocale the reason's language
   * @param reason the reason (Restricted HR)
   * @param requestedAt request instant
   */
  public record Request(
      UUID id,
      UUID scheduleId,
      UUID slotId,
      UUID employeeId,
      UUID employmentId,
      String reasonLocale,
      String reason,
      Instant requestedAt) {

    @Override
    public String toString() {
      return "Request[id=" + id + "]";
    }
  }

  /**
   * A decision's evidence.
   *
   * @param id decision
   * @param requestId request
   * @param outcome {@code APPROVED} or {@code REJECTED}
   * @param managerEmployeeId the deciding manager
   * @param substituteEmployeeId the substitute (approval only)
   * @param substituteEmploymentId the substitute's employment (approval only)
   * @param reasonLocale the reason's language
   * @param reason the reason (Restricted HR)
   * @param decidedAt decision instant
   */
  public record Decision(
      UUID id,
      UUID requestId,
      String outcome,
      UUID managerEmployeeId,
      UUID substituteEmployeeId,
      UUID substituteEmploymentId,
      String reasonLocale,
      String reason,
      Instant decidedAt) {

    @Override
    public String toString() {
      return "Decision[id=" + id + ", outcome=" + outcome + "]";
    }
  }

  /**
   * An employee's current minimal directory identity.
   *
   * @param id employee
   * @param employeeNumber number
   * @param givenNames given names
   * @param familyName family name
   */
  public record Person(UUID id, String employeeNumber, String givenNames, String familyName) {

    @Override
    public String toString() {
      return "Person[id=" + id + "]";
    }
  }

  /**
   * One row of the employee's own history.
   *
   * @param request the request
   * @param shift its shift
   * @param pending whether the pending projection exists
   * @param decision the decision, or {@code null}
   * @param substitute the approved substitute's identity, or {@code null}
   */
  public record HistoryItem(
      Request request, Shift shift, boolean pending, Decision decision, Person substitute) {

    @Override
    public String toString() {
      return "HistoryItem[id=" + request.id() + "]";
    }
  }

  /**
   * One row of a manager's inbox.
   *
   * @param request the request
   * @param shift its shift
   * @param employee the requesting employee's identity
   */
  public record InboxItem(Request request, Shift shift, Person employee) {

    @Override
    public String toString() {
      return "InboxItem[id=" + request.id() + "]";
    }
  }
}
