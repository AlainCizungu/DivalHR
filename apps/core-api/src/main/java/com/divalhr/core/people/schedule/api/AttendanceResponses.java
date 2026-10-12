package com.divalhr.core.people.schedule.api;

import com.divalhr.core.people.schedule.api.WorkScheduleResponses.Labels;
import com.divalhr.core.people.schedule.api.WorkScheduleResponses.Site;
import com.divalhr.core.people.schedule.application.WorkScheduleCommand;
import com.divalhr.core.people.schedule.domain.Attendance;
import com.divalhr.core.people.schedule.domain.Substitution;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.UnitView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response bodies of online attendance (MVP-060A). Restricted HR/Operations: the caller's own
 * records only; never a subject, an employment or an evidence field.
 */
public final class AttendanceResponses {

  private AttendanceResponses() {}

  /**
   * The minimal receipt of a check-in or a check-out; instants are the server's.
   *
   * @param attendanceRecordId record
   * @param slotId slot
   * @param status OPEN after a check-in, COMPLETED after a check-out
   * @param checkedInAt check-in instant
   * @param checkedOutAt check-out instant, or {@code null}
   */
  @Schema(name = "AttendanceReceipt")
  public record Receipt(
      UUID attendanceRecordId,
      UUID slotId,
      Attendance.Status status,
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
          record.status(),
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
      return "AttendanceReceipt[id=" + attendanceRecordId + ", status=" + status + "]";
    }
  }

  /**
   * The immutable published shift of a record.
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
  @Schema(name = "AttendanceShift")
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

    static ShiftView of(Substitution.Shift shift, UnitView site) {
      return new ShiftView(
          shift.scheduleId(),
          shift.slotId(),
          Site.of(site, shift.siteId()),
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
   * One of the caller's own records.
   *
   * @param id record
   * @param status OPEN or COMPLETED
   * @param checkedInAt check-in instant
   * @param checkedOutAt check-out instant, or {@code null}
   * @param checkOutAvailable open and inside the check-out window at the page's instant
   * @param shift its shift
   */
  @Schema(name = "MyAttendanceRecord")
  public record Item(
      UUID id,
      Attendance.Status status,
      Instant checkedInAt,
      Instant checkedOutAt,
      boolean checkOutAvailable,
      ShiftView shift) {

    /**
     * The view of a history row.
     *
     * @param item the row
     * @param site its site, or {@code null}
     * @param at the page's server instant
     * @return the view
     */
    public static Item of(Attendance.HistoryItem item, UnitView site, Instant at) {
      Attendance.Record record = item.record();
      return new Item(
          record.id(),
          record.status(),
          record.checkedInAt(),
          record.checkedOutAt(),
          record.checkOutAvailable(at),
          ShiftView.of(item.shift(), site));
    }

    /**
     * Identifiers and state only: labels never reach a log line.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "MyAttendanceRecord[id=" + id + ", status=" + status + "]";
    }
  }

  /**
   * One page of the caller's own records, newest shift first.
   *
   * @param items records
   * @param nextCursor next page, or {@code null}
   * @param asOfInstant the view's server instant, the same on every page
   */
  @Schema(name = "MyAttendancePage")
  public record Page(List<Item> items, String nextCursor, Instant asOfInstant) {

    /** Copies the items. */
    public Page {
      items = List.copyOf(items);
    }
  }
}
