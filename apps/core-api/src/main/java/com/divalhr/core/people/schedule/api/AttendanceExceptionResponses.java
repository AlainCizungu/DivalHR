package com.divalhr.core.people.schedule.api;

import com.divalhr.core.people.schedule.api.WorkScheduleResponses.Labels;
import com.divalhr.core.people.schedule.api.WorkScheduleResponses.Site;
import com.divalhr.core.people.schedule.api.WorkScheduleSubstitutionResponses.Person;
import com.divalhr.core.people.schedule.application.WorkScheduleCommand;
import com.divalhr.core.people.schedule.domain.AttendanceExceptions;
import com.divalhr.core.people.schedule.domain.Substitution;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.UnitView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response bodies of the factual attendance exceptions (MVP-060C). Restricted HR/Operations: the
 * facts of the shift and its evidence only; never a manager, subject, employment, reason or
 * recorder.
 */
public final class AttendanceExceptionResponses {

  private AttendanceExceptionResponses() {}

  /**
   * One exception of the caller's own shift.
   *
   * @param type the exception type
   * @param scheduleId schedule
   * @param slotId slot
   * @param attendanceRecordId the record, or {@code null} for a missing check-in
   * @param site site
   * @param siteTimezone the schedule's site time zone snapshot
   * @param start local start
   * @param end local end
   * @param startAt start instant
   * @param endAt end instant
   * @param overnight whether it ends on a later local date
   * @param labels labels
   * @param checkedInAt the recorded check-in, or {@code null}
   * @param checkedOutAt the recorded check-out at the as-of instant, or {@code null}
   * @param varianceMinutes whole minutes late or early (rounded up), or {@code null}
   */
  @Schema(name = "AttendanceException")
  public record Item(
      AttendanceExceptions.Type type,
      UUID scheduleId,
      UUID slotId,
      UUID attendanceRecordId,
      Site site,
      String siteTimezone,
      String start,
      String end,
      Instant startAt,
      Instant endAt,
      boolean overnight,
      Labels labels,
      Instant checkedInAt,
      Instant checkedOutAt,
      Integer varianceMinutes) {

    /**
     * The view of a row.
     *
     * @param row the row
     * @param site its site, or {@code null}
     * @return the view
     */
    public static Item of(AttendanceExceptions.Row row, UnitView site) {
      Substitution.Shift shift = row.shift();
      return new Item(
          row.type(),
          shift.scheduleId(),
          shift.slotId(),
          row.recordId(),
          Site.of(site, shift.siteId()),
          shift.siteTimezone(),
          WorkScheduleCommand.wire(shift.start()),
          WorkScheduleCommand.wire(shift.end()),
          shift.startAt(),
          shift.endAt(),
          shift.overnight(),
          new Labels(shift.labelEn(), shift.labelFr()),
          row.checkedInAt(),
          row.checkedOutAt(),
          row.varianceMinutes());
    }

    /**
     * Identifiers and the type only: labels never reach a log line.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "AttendanceException[" + type + ", slot=" + slotId + "]";
    }
  }

  /**
   * One exception of a direct report's (or, for a tenant administrator, any employee's) shift.
   *
   * @param type the exception type
   * @param employee the employee's current minimal identity
   * @param scheduleId schedule
   * @param slotId slot
   * @param attendanceRecordId the record, or {@code null} for a missing check-in
   * @param site site
   * @param siteTimezone the schedule's site time zone snapshot
   * @param start local start
   * @param end local end
   * @param startAt start instant
   * @param endAt end instant
   * @param overnight whether it ends on a later local date
   * @param labels labels
   * @param checkedInAt the recorded check-in, or {@code null}
   * @param checkedOutAt the recorded check-out at the as-of instant, or {@code null}
   * @param varianceMinutes whole minutes late or early (rounded up), or {@code null}
   */
  @Schema(name = "TeamAttendanceException")
  public record TeamItem(
      AttendanceExceptions.Type type,
      Person employee,
      UUID scheduleId,
      UUID slotId,
      UUID attendanceRecordId,
      Site site,
      String siteTimezone,
      String start,
      String end,
      Instant startAt,
      Instant endAt,
      boolean overnight,
      Labels labels,
      Instant checkedInAt,
      Instant checkedOutAt,
      Integer varianceMinutes) {

    /**
     * The view of a row.
     *
     * @param row the row
     * @param site its site, or {@code null}
     * @return the view
     */
    public static TeamItem of(AttendanceExceptions.Row row, UnitView site) {
      Item item = Item.of(row, site);
      return new TeamItem(
          item.type(),
          new Person(row.employeeId(), row.employeeNumber(), row.givenNames(), row.familyName()),
          item.scheduleId(),
          item.slotId(),
          item.attendanceRecordId(),
          item.site(),
          item.siteTimezone(),
          item.start(),
          item.end(),
          item.startAt(),
          item.endAt(),
          item.overnight(),
          item.labels(),
          item.checkedInAt(),
          item.checkedOutAt(),
          item.varianceMinutes());
    }

    /**
     * Identifiers and the type only: names and labels never reach a log line.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "TeamAttendanceException[" + type + ", slot=" + slotId + "]";
    }
  }

  /**
   * One page of the caller's own exceptions.
   *
   * @param items exceptions in the keyset order
   * @param nextCursor next page, or {@code null}
   * @param asOfInstant the view's server instant, the same on every page
   */
  @Schema(name = "AttendanceExceptionPage")
  public record Page(List<Item> items, String nextCursor, Instant asOfInstant) {

    /** Copies the items. */
    public Page {
      items = List.copyOf(items);
    }
  }

  /**
   * One page of a manager's or a tenant administrator's exceptions.
   *
   * @param items exceptions in the keyset order
   * @param nextCursor next page, or {@code null}
   * @param asOfInstant the view's server instant, the same on every page
   */
  @Schema(name = "TeamAttendanceExceptionPage")
  public record TeamPage(List<TeamItem> items, String nextCursor, Instant asOfInstant) {

    /** Copies the items. */
    public TeamPage {
      items = List.copyOf(items);
    }
  }
}
