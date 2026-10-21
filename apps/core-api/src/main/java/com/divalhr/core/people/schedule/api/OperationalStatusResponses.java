package com.divalhr.core.people.schedule.api;

import com.divalhr.core.people.schedule.api.WorkScheduleResponses.Labels;
import com.divalhr.core.people.schedule.api.WorkScheduleResponses.Site;
import com.divalhr.core.people.schedule.api.WorkScheduleSubstitutionResponses.Person;
import com.divalhr.core.people.schedule.application.OperationalStatusService.View;
import com.divalhr.core.people.schedule.application.WorkScheduleCommand;
import com.divalhr.core.people.schedule.domain.OperationalStatus;
import com.divalhr.core.people.schedule.domain.Substitution.Shift;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.UnitView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Response bodies of the operational status (MVP-100, ADR 0026). Restricted HR/Operations: counts
 * of shift occurrences and approvals, and capped detail lists that name an employee only where the
 * caller already has equivalent disclosure authority; never a manager, subject, employment, reason
 * or recorder.
 */
public final class OperationalStatusResponses {

  private OperationalStatusResponses() {}

  /**
   * The fixed rules the figures follow, shown on screen.
   *
   * @param lateMinutes a check-in strictly more than this after the start is late
   * @param notCheckedInGraceMinutes with no check-in, strictly more than this after the start is
   *     "not checked in"; up to and including it, "expected"
   * @param leaveOverdueHours pending leave strictly older than this is overdue
   * @param timesheetOverdueHours a submitted timesheet strictly older than this is overdue
   * @param detailLimit the most rows of any detail list
   */
  @Schema(name = "OperationalStatusThresholds")
  public record Thresholds(
      int lateMinutes,
      int notCheckedInGraceMinutes,
      int leaveOverdueHours,
      int timesheetOverdueHours,
      int detailLimit) {

    static Thresholds fixed() {
      return new Thresholds(
          (int) OperationalStatus.LATE_TOLERANCE.toMinutes(),
          (int) OperationalStatus.NOT_CHECKED_IN_GRACE.toMinutes(),
          (int) OperationalStatus.LEAVE_OVERDUE_AFTER.toHours(),
          (int) OperationalStatus.TIMESHEET_OVERDUE_AFTER.toHours(),
          OperationalStatus.DETAIL_LIMIT);
    }
  }

  /**
   * Shift-occurrence counts: effective assigned shifts of today, not people.
   *
   * @param scheduled all effective assigned shift occurrences of today
   * @param expected no check-in and not yet more than the grace after the scheduled start
   * @param notCheckedIn no check-in and more than the grace after the scheduled start
   * @param present checked in, no check-out recorded
   * @param completed checked in and checked out
   * @param late checked in more than the tolerance after the start; a subset that may overlap
   *     present and completed
   */
  @Schema(name = "OperationalStatusShifts")
  public record Shifts(
      int scheduled, int expected, int notCheckedIn, int present, int completed, int late) {}

  /**
   * Coverage of today's operational slots.
   *
   * @param uncovered slots without an effective assignee
   */
  @Schema(name = "OperationalStatusCoverage")
  public record Coverage(int uncovered) {}

  /**
   * Approvals waiting.
   *
   * @param pending all waiting
   * @param overdue waiting for strictly more than the fixed threshold
   */
  @Schema(name = "OperationalStatusWaiting")
  public record Waiting(int pending, int overdue) {}

  /**
   * Approvals waiting.
   *
   * @param leave pending leave requests
   * @param timesheets submitted timesheets waiting for approval
   */
  @Schema(name = "OperationalStatusApprovals")
  public record Approvals(Waiting leave, Waiting timesheets) {}

  /**
   * A shift of today.
   *
   * @param scheduleId schedule
   * @param slotId slot
   * @param site site
   * @param siteTimezone the schedule's site time zone snapshot
   * @param start local start
   * @param end local end
   * @param startAt start instant
   * @param endAt end instant
   * @param overnight whether it ends on a later local date
   * @param labels labels
   */
  @Schema(name = "OperationalStatusShift")
  public record ShiftView(
      UUID scheduleId,
      UUID slotId,
      Site site,
      String siteTimezone,
      String start,
      String end,
      Instant startAt,
      Instant endAt,
      boolean overnight,
      Labels labels) {

    static ShiftView of(Shift shift, Map<UUID, UnitView> sites) {
      return new ShiftView(
          shift.scheduleId(),
          shift.slotId(),
          Site.of(sites.get(shift.siteId()), shift.siteId()),
          shift.siteTimezone(),
          WorkScheduleCommand.wire(shift.start()),
          WorkScheduleCommand.wire(shift.end()),
          shift.startAt(),
          shift.endAt(),
          shift.overnight(),
          new Labels(shift.labelEn(), shift.labelFr()));
    }
  }

  /**
   * A shift whose effective assignee has no check-in after the grace.
   *
   * @param employee the effective assignee
   * @param shift the shift
   * @param minutesSinceStart whole minutes since the scheduled start
   */
  @Schema(name = "OperationalStatusNotCheckedIn")
  public record NotCheckedIn(Person employee, ShiftView shift, long minutesSinceStart) {

    /**
     * Identifiers only: names and labels never reach a log line.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "OperationalStatusNotCheckedIn[slot=" + shift.slotId() + "]";
    }
  }

  /**
   * A manager's overdue leave request.
   *
   * @param requestId the request
   * @param employee the requester
   * @param submittedAt submission instant
   * @param ageHours whole hours waiting
   */
  @Schema(name = "OperationalStatusOverdueLeave")
  public record OverdueLeave(UUID requestId, Person employee, Instant submittedAt, long ageHours) {

    /**
     * Identifiers only.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "OperationalStatusOverdueLeave[request=" + requestId + "]";
    }
  }

  /**
   * A manager's overdue timesheet.
   *
   * @param timesheetId the timesheet
   * @param employee the employee
   * @param site the timesheet's site
   * @param weekStart the site-local Monday
   * @param submittedAt submission instant
   * @param ageHours whole hours waiting
   */
  @Schema(name = "OperationalStatusOverdueTimesheet")
  public record OverdueTimesheet(
      UUID timesheetId,
      Person employee,
      Site site,
      LocalDate weekStart,
      Instant submittedAt,
      long ageHours) {

    /**
     * Identifiers only.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "OperationalStatusOverdueTimesheet[timesheet=" + timesheetId + "]";
    }
  }

  /** Shifts not checked in (named employees, as the attendance review shows them). */
  @Schema(name = "OperationalStatusNotCheckedInDetails")
  public record NotCheckedInDetails(List<NotCheckedIn> items, boolean truncated) {

    /** Copies the items. */
    public NotCheckedInDetails {
      items = List.copyOf(items);
    }
  }

  /** Uncovered shifts: shift facts only. */
  @Schema(name = "OperationalStatusUncoveredDetails")
  public record UncoveredDetails(List<ShiftView> items, boolean truncated) {

    /** Copies the items. */
    public UncoveredDetails {
      items = List.copyOf(items);
    }
  }

  /** A manager's overdue leave requests. */
  @Schema(name = "OperationalStatusOverdueLeaveDetails")
  public record OverdueLeaveDetails(List<OverdueLeave> items, boolean truncated) {

    /** Copies the items. */
    public OverdueLeaveDetails {
      items = List.copyOf(items);
    }
  }

  /** A manager's overdue timesheets. */
  @Schema(name = "OperationalStatusOverdueTimesheetDetails")
  public record OverdueTimesheetDetails(List<OverdueTimesheet> items, boolean truncated) {

    /** Copies the items. */
    public OverdueTimesheetDetails {
      items = List.copyOf(items);
    }
  }

  /**
   * The capped detail lists; a list is {@code null} where the caller has no equivalent disclosure
   * authority (or the notion does not exist for the scope).
   *
   * @param notCheckedIn shifts not checked in
   * @param uncovered uncovered shifts (administrator only)
   * @param overdueLeave overdue leave of the manager's reports (manager only)
   * @param overdueTimesheets overdue timesheets of the manager's reports (manager only)
   */
  @Schema(name = "OperationalStatusDetails")
  public record DetailsView(
      NotCheckedInDetails notCheckedIn,
      UncoveredDetails uncovered,
      OverdueLeaveDetails overdueLeave,
      OverdueTimesheetDetails overdueTimesheets) {}

  /**
   * Today's operational status at one server-owned instant.
   *
   * @param asOfInstant the server instant every figure describes
   * @param thresholds the fixed rules
   * @param shifts shift-occurrence counts
   * @param coverage uncovered slots, or {@code null} for a manager
   * @param approvals approvals waiting
   * @param details capped detail lists
   */
  @Schema(name = "OperationalStatus")
  public record Status(
      Instant asOfInstant,
      Thresholds thresholds,
      Shifts shifts,
      Coverage coverage,
      Approvals approvals,
      DetailsView details) {

    /**
     * The view of a computed status.
     *
     * @param view the status and the sites its shifts name
     * @return the response
     */
    public static Status of(View view) {
      OperationalStatus.Status s = view.status();
      Map<UUID, UnitView> sites = view.sites();
      OperationalStatus.Shifts c = s.shifts();
      return new Status(
          s.asOf(),
          Thresholds.fixed(),
          new Shifts(
              c.scheduled(), c.expected(), c.notCheckedIn(), c.present(), c.completed(), c.late()),
          s.uncovered() == null ? null : new Coverage(s.uncovered()),
          new Approvals(
              new Waiting(s.leave().pending(), s.leave().overdue()),
              new Waiting(s.timesheets().pending(), s.timesheets().overdue())),
          new DetailsView(
              new NotCheckedInDetails(
                  s.notCheckedIn().items().stream()
                      .map(
                          row ->
                              new NotCheckedIn(
                                  new Person(
                                      row.person().id(),
                                      row.person().number(),
                                      row.person().givenNames(),
                                      row.person().familyName()),
                                  ShiftView.of(row.shift(), sites),
                                  row.minutesSinceStart()))
                      .toList(),
                  s.notCheckedIn().truncated()),
              s.uncoveredShifts() == null
                  ? null
                  : new UncoveredDetails(
                      s.uncoveredShifts().items().stream()
                          .map(shift -> ShiftView.of(shift, sites))
                          .toList(),
                      s.uncoveredShifts().truncated()),
              s.overdueLeave() == null
                  ? null
                  : new OverdueLeaveDetails(
                      s.overdueLeave().items().stream()
                          .map(
                              row ->
                                  new OverdueLeave(
                                      row.requestId(),
                                      new Person(
                                          row.person().id(),
                                          row.person().number(),
                                          row.person().givenNames(),
                                          row.person().familyName()),
                                      row.submittedAt(),
                                      row.ageHours()))
                          .toList(),
                      s.overdueLeave().truncated()),
              s.overdueTimesheets() == null
                  ? null
                  : new OverdueTimesheetDetails(
                      s.overdueTimesheets().items().stream()
                          .map(
                              row ->
                                  new OverdueTimesheet(
                                      row.timesheetId(),
                                      new Person(
                                          row.person().id(),
                                          row.person().number(),
                                          row.person().givenNames(),
                                          row.person().familyName()),
                                      Site.of(sites.get(row.siteId()), row.siteId()),
                                      row.weekStart(),
                                      row.submittedAt(),
                                      row.ageHours()))
                          .toList(),
                      s.overdueTimesheets().truncated())));
    }
  }
}
