package com.divalhr.core.people.schedule.api;

import com.divalhr.core.people.schedule.api.WorkScheduleResponses.Labels;
import com.divalhr.core.people.schedule.api.WorkScheduleResponses.Site;
import com.divalhr.core.people.schedule.api.WorkScheduleSubstitutionResponses.Person;
import com.divalhr.core.people.schedule.application.WorkScheduleCommand;
import com.divalhr.core.people.schedule.domain.Attendance;
import com.divalhr.core.people.schedule.domain.Substitution;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.UnitView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response bodies of supervisor-assisted attendance (MVP-060B). Restricted HR/Operations: only what
 * acting requires; never a reason, a subject or an employment.
 */
public final class TeamAttendanceResponses {

  private TeamAttendanceResponses() {}

  /**
   * The minimal receipt of a supervisor's check-in or check-out of the target employee; never the
   * reason or the manager.
   *
   * @param attendanceRecordId record
   * @param slotId slot
   * @param employeeId the target employee
   * @param status OPEN after a check-in, COMPLETED after a check-out
   * @param recordingMethod always SUPERVISOR
   * @param checkedInAt check-in instant
   * @param checkedOutAt check-out instant, or {@code null}
   */
  @Schema(name = "SupervisorAttendanceReceipt")
  public record Receipt(
      UUID attendanceRecordId,
      UUID slotId,
      UUID employeeId,
      Attendance.Status status,
      @Schema(allowableValues = {"SUPERVISOR"}) String recordingMethod,
      Instant checkedInAt,
      Instant checkedOutAt) {

    /**
     * The receipt of a record.
     *
     * @param record the record
     * @return the receipt
     */
    public static Receipt of(Attendance.Record record) {
      return new Receipt(
          record.id(),
          record.slotId(),
          record.employeeId(),
          record.status(),
          Attendance.Method.SUPERVISOR.name(),
          record.checkedInAt(),
          record.checkedOutAt());
    }

    /**
     * Identifiers and state only.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "SupervisorAttendanceReceipt[id=" + attendanceRecordId + ", status=" + status + "]";
    }
  }

  /**
   * One action a direct report's shift needs now.
   *
   * @param requiredAction CHECK_IN or CHECK_OUT
   * @param actionDeadline the last instant the action is possible
   * @param slotId slot
   * @param scheduleId schedule
   * @param attendanceRecordId the open record (check-out), or {@code null}
   * @param employee the target employee's current minimal identity
   * @param attendanceStatus NOT_RECORDED (check-in) or OPEN (check-out)
   * @param checkedInAt the open record's check-in instant, or {@code null}
   * @param site site
   * @param siteTimezone the schedule's site time zone snapshot
   * @param start local start
   * @param end local end
   * @param startAt start instant
   * @param endAt end instant
   * @param overnight whether it ends on a later local date
   * @param labels labels
   */
  @Schema(name = "TeamAttendanceAction")
  public record Action(
      Attendance.RequiredAction requiredAction,
      Instant actionDeadline,
      UUID slotId,
      UUID scheduleId,
      UUID attendanceRecordId,
      Person employee,
      Attendance.ShiftStatus attendanceStatus,
      Instant checkedInAt,
      Site site,
      String siteTimezone,
      String start,
      String end,
      Instant startAt,
      Instant endAt,
      boolean overnight,
      Labels labels) {

    /**
     * The view of a queue row.
     *
     * @param action the row
     * @param site its site, or {@code null}
     * @return the view
     */
    public static Action of(Attendance.TeamAction action, UnitView site) {
      Substitution.Shift shift = action.shift();
      return new Action(
          action.action(),
          action.deadline(),
          shift.slotId(),
          shift.scheduleId(),
          action.recordId(),
          new Person(
              action.employeeId(),
              action.employeeNumber(),
              action.givenNames(),
              action.familyName()),
          action.recordId() == null
              ? Attendance.ShiftStatus.NOT_RECORDED
              : Attendance.ShiftStatus.OPEN,
          action.checkedInAt(),
          Site.of(site, shift.siteId()),
          shift.siteTimezone(),
          WorkScheduleCommand.wire(shift.start()),
          WorkScheduleCommand.wire(shift.end()),
          shift.startAt(),
          shift.endAt(),
          shift.overnight(),
          new Labels(shift.labelEn(), shift.labelFr()));
    }

    /**
     * Identifiers and the action only: names and labels never reach a log line.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "TeamAttendanceAction[" + requiredAction + ", slot=" + slotId + "]";
    }
  }

  /**
   * One page of the manager's queue.
   *
   * @param items actions in deadline order
   * @param nextCursor next page, or {@code null}
   * @param asOfInstant the view's server instant, the same on every page
   */
  @Schema(name = "TeamAttendanceActionPage")
  public record Page(List<Action> items, String nextCursor, Instant asOfInstant) {

    /** Copies the items. */
    public Page {
      items = List.copyOf(items);
    }
  }
}
