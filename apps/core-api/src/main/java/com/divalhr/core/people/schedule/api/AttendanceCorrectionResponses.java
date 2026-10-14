package com.divalhr.core.people.schedule.api;

import com.divalhr.core.people.schedule.api.AttendanceResponses.ShiftView;
import com.divalhr.core.people.schedule.api.WorkScheduleSubstitutionResponses.Person;
import com.divalhr.core.people.schedule.domain.Attendance;
import com.divalhr.core.people.schedule.domain.AttendanceCorrection;
import com.divalhr.core.people.schedule.domain.AttendanceCorrection.Operation;
import com.divalhr.core.people.schedule.domain.AttendanceCorrection.Reason;
import com.divalhr.core.people.schedule.domain.AttendanceCorrection.Times;
import com.divalhr.core.people.schedule.domain.AttendanceCorrection.Warning;
import com.divalhr.core.people.schedule.domain.AttendanceExceptions;
import com.divalhr.core.people.schedule.domain.Substitution;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.UnitView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response bodies of attendance corrections (MVP-062), for tenant administrators only. Restricted
 * HR/Operations: the explanation is returned only by a preview (as submitted) and the history; a
 * receipt never carries it. The administrator's subject is never returned.
 */
public final class AttendanceCorrectionResponses {

  private AttendanceCorrectionResponses() {}

  /**
   * A check-in and an optional check-out.
   *
   * @param checkedInAt check-in instant
   * @param checkedOutAt check-out instant, or {@code null}
   */
  @Schema(name = "AttendanceCorrectionTimes")
  public record TimesView(Instant checkedInAt, @Schema(nullable = true) Instant checkedOutAt) {

    /**
     * The view of values.
     *
     * @param times the values, or {@code null}
     * @return the view, or {@code null}
     */
    public static TimesView of(Times times) {
      return times == null ? null : new TimesView(times.checkedInAt(), times.checkedOutAt());
    }

    /**
     * Never an instant in a log line.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "AttendanceCorrectionTimes[]";
    }
  }

  /**
   * The immutable schedule boundaries a corrected value must respect.
   *
   * @param checkInOpensAt the first accepted check-in (two hours before the start, inclusive)
   * @param checkInClosesAt the scheduled end (a check-in is strictly before it)
   * @param checkOutUntil the last accepted check-out (the end plus 24 hours, inclusive)
   */
  @Schema(name = "AttendanceCorrectionBoundaries")
  public record Boundaries(Instant checkInOpensAt, Instant checkInClosesAt, Instant checkOutUntil) {

    static Boundaries of(Substitution.Shift shift) {
      return new Boundaries(
          Attendance.checkInOpensAt(shift.startAt()),
          shift.endAt(),
          Attendance.checkOutUntil(shift.endAt()));
    }
  }

  /**
   * The effective record after a correction.
   *
   * @param attendanceRecordId record
   * @param slotId slot
   * @param status OPEN or COMPLETED
   * @param checkedInAt effective check-in instant
   * @param checkedOutAt effective check-out instant, or {@code null}
   * @param correctionVersion the number of corrections
   * @param correctedAt the latest correction's instant, or {@code null}
   */
  @Schema(name = "CorrectedAttendanceRecord")
  public record CorrectedRecord(
      UUID attendanceRecordId,
      UUID slotId,
      Attendance.Status status,
      Instant checkedInAt,
      @Schema(nullable = true) Instant checkedOutAt,
      int correctionVersion,
      @Schema(nullable = true) Instant correctedAt) {

    /**
     * The view of a record.
     *
     * @param record the record
     * @return the view
     */
    public static CorrectedRecord of(Attendance.Record record) {
      return new CorrectedRecord(
          record.id(),
          record.slotId(),
          record.status(),
          record.checkedInAt(),
          record.checkedOutAt(),
          record.correctionVersion(),
          record.correctedAt());
    }

    /**
     * Identifiers and state only.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "CorrectedAttendanceRecord[id=" + attendanceRecordId + ", status=" + status + "]";
    }
  }

  /**
   * A correction's preview: nothing is written but its disclosure audit.
   *
   * @param operation the operation
   * @param attendanceRecordId the record (REPLACE_TIMES), or {@code null}
   * @param targetVersion the target version to commit against (the record's correction count, or 0
   *     for a missing record)
   * @param employee the target employee's current minimal identity
   * @param shift the immutable shift (site, local times, time zone, bilingual labels)
   * @param boundaries the schedule boundaries
   * @param before the current effective values, or {@code null} for a missing record
   * @param after the proposed values, normalized to microseconds
   * @param reasonCode the reason code
   * @param explanation the explanation as it will be stored (trimmed)
   * @param exceptionsBefore the factual exceptions now
   * @param exceptionsAfter the factual exceptions after the correction, at the same instant
   * @param warnings neutral factual warnings
   * @param previewedAt the server's preview instant
   * @param previewDigest the digest a commit must present
   */
  @Schema(name = "AttendanceCorrectionPreview")
  public record Preview(
      Operation operation,
      @Schema(nullable = true) UUID attendanceRecordId,
      int targetVersion,
      Person employee,
      ShiftView shift,
      Boundaries boundaries,
      @Schema(nullable = true) TimesView before,
      TimesView after,
      Reason reasonCode,
      String explanation,
      List<AttendanceExceptions.Type> exceptionsBefore,
      List<AttendanceExceptions.Type> exceptionsAfter,
      List<Warning> warnings,
      Instant previewedAt,
      String previewDigest) {

    /** Copies the lists. */
    public Preview {
      exceptionsBefore = List.copyOf(exceptionsBefore);
      exceptionsAfter = List.copyOf(exceptionsAfter);
      warnings = List.copyOf(warnings);
    }

    /**
     * Builds a preview.
     *
     * @param operation the operation
     * @param recordId the record, or {@code null}
     * @param targetVersion the target version
     * @param employee the employee
     * @param shift the shift
     * @param site its site, or {@code null}
     * @param before the current values, or {@code null}
     * @param after the proposed values
     * @param reason the reason
     * @param explanation the explanation
     * @param exceptionsBefore exceptions now
     * @param exceptionsAfter exceptions after
     * @param warnings warnings
     * @param at the preview instant
     * @param digest the preview digest
     * @return the preview
     */
    public static Preview of(
        Operation operation,
        UUID recordId,
        int targetVersion,
        Substitution.Person employee,
        Substitution.Shift shift,
        UnitView site,
        Times before,
        Times after,
        Reason reason,
        String explanation,
        List<AttendanceExceptions.Type> exceptionsBefore,
        List<AttendanceExceptions.Type> exceptionsAfter,
        List<Warning> warnings,
        Instant at,
        String digest) {
      return new Preview(
          operation,
          recordId,
          targetVersion,
          Person.of(employee),
          ShiftView.of(shift, site),
          Boundaries.of(shift),
          TimesView.of(before),
          TimesView.of(after),
          reason,
          explanation,
          exceptionsBefore,
          exceptionsAfter,
          warnings,
          at,
          digest);
    }

    /**
     * The operation only: never a name, label, instant or the explanation in a log line.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "AttendanceCorrectionPreview[" + operation + "]";
    }
  }

  /**
   * A committed correction's receipt. Never the explanation or the administrator.
   *
   * @param correctionId the correction
   * @param operation the operation
   * @param correctionVersion its number in the record's history
   * @param correctedAt the server instant
   * @param reasonCode the reason code
   * @param before the values before, or {@code null} for a created record
   * @param after the values after
   * @param record the effective record
   * @param reopenedTimesheets the approved timesheets this correction reopened (MVP-070D)
   */
  @Schema(name = "AttendanceCorrectionReceipt")
  public record Receipt(
      UUID correctionId,
      Operation operation,
      int correctionVersion,
      Instant correctedAt,
      Reason reasonCode,
      @Schema(nullable = true) TimesView before,
      TimesView after,
      CorrectedRecord record,
      int reopenedTimesheets) {

    /**
     * The receipt of a correction.
     *
     * @param entry the correction
     * @param record the effective record
     * @param reopenedTimesheets the approved timesheets it reopened
     * @return the receipt
     */
    public static Receipt of(
        AttendanceCorrection.Entry entry, Attendance.Record record, int reopenedTimesheets) {
      return new Receipt(
          entry.id(),
          entry.operation(),
          entry.version(),
          entry.correctedAt(),
          entry.reason(),
          TimesView.of(entry.before()),
          TimesView.of(entry.after()),
          CorrectedRecord.of(record),
          reopenedTimesheets);
    }

    /**
     * Identifiers only.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "AttendanceCorrectionReceipt[id=" + correctionId + ", " + operation + "]";
    }
  }

  /**
   * One committed correction in a record's history.
   *
   * @param id the correction
   * @param version its number
   * @param operation the operation
   * @param before the values before, or {@code null} for a created record
   * @param after the values after
   * @param reasonCode the reason code
   * @param explanation the administrator's explanation (Restricted HR/Operations)
   * @param correctedAt the server instant
   * @param correctedByYou whether the viewing administrator committed it
   * @param administratorReference a stable pseudonymous reference to the committing administrator
   *     within the organization (never the subject)
   */
  @Schema(name = "AttendanceCorrectionHistoryEntry")
  public record HistoryEntry(
      UUID id,
      int version,
      Operation operation,
      @Schema(nullable = true) TimesView before,
      TimesView after,
      Reason reasonCode,
      String explanation,
      Instant correctedAt,
      boolean correctedByYou,
      String administratorReference) {

    /**
     * Identifiers only: never the explanation.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "AttendanceCorrectionHistoryEntry[id=" + id + ", v" + version + "]";
    }
  }

  /**
   * One original attendance event, kept unchanged (MVP-060A/B, MVP-061A).
   *
   * @param type CHECKED_IN or CHECKED_OUT
   * @param recordedAt the event's instant
   * @param recordingMethod how it was recorded
   */
  @Schema(name = "OriginalAttendanceEvent")
  public record OriginalEvent(
      Attendance.EventType type, Instant recordedAt, Attendance.Method recordingMethod) {}

  /**
   * A record's current effective values, its original events and its correction history, newest
   * first.
   *
   * @param record the effective record
   * @param employee the record's employee (current minimal identity)
   * @param shift the immutable shift
   * @param originalEvents the original events, unchanged (none for a record created by a
   *     correction)
   * @param corrections the corrections, newest first
   */
  @Schema(name = "AttendanceCorrectionHistory")
  public record History(
      CorrectedRecord record,
      Person employee,
      ShiftView shift,
      List<OriginalEvent> originalEvents,
      List<HistoryEntry> corrections) {

    /** Copies the lists. */
    public History {
      originalEvents = List.copyOf(originalEvents);
      corrections = List.copyOf(corrections);
    }

    /**
     * Builds the history view.
     *
     * @param record the record
     * @param employee the employee
     * @param shift the shift
     * @param site its site, or {@code null}
     * @param events the original events
     * @param corrections the corrections, newest first
     * @return the view
     */
    public static History of(
        Attendance.Record record,
        Substitution.Person employee,
        Substitution.Shift shift,
        UnitView site,
        List<OriginalEvent> events,
        List<HistoryEntry> corrections) {
      return new History(
          CorrectedRecord.of(record),
          Person.of(employee),
          ShiftView.of(shift, site),
          events,
          corrections);
    }

    /**
     * Identifiers only.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "AttendanceCorrectionHistory[record=" + record.attendanceRecordId() + "]";
    }
  }
}
