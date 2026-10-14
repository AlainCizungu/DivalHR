package com.divalhr.core.people.schedule.internal;

import java.util.Optional;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * The V25 to V28 constraints a publication, cancellation, replacement, substitution or attendance
 * command may race into, and the integrity-violation constraint name of a failure (never returned
 * to clients or logs).
 */
public final class ScheduleConstraintViolations {

  /** One operational schedule per site and date (V25; on the site coverage since V26). */
  public static final String SITE_OVERLAP = "work_schedule_site_no_overlap";

  /** No overlapping operational assigned instants for an employee (V25; slot coverage, V26). */
  public static final String EMPLOYEE_OVERLAP = "work_schedule_slot_employee_no_overlap";

  /** At most one terminal action per schedule (V26). */
  public static final String SINGLE_TERMINAL = "work_schedule_single_terminal";

  /** A terminal action only while every slot starts later (V26). */
  public static final String CHANGE_WINDOW = "work_schedule_change_window";

  /** MVP-051: one pending substitution request per slot. */
  public static final String SUBSTITUTION_PENDING_SLOT =
      "work_schedule_substitution_pending_slot_unique";

  /** MVP-051: one decision per substitution request. */
  public static final String SUBSTITUTION_DECISION_REQUEST =
      "work_schedule_substitution_decision_request_unique";

  /** MVP-051: one approved substitution per slot. */
  public static final String SUBSTITUTION_ONE_APPROVAL =
      "work_schedule_substitution_decision_one_approval";

  /** MVP-060A: one attendance record per slot and employee. */
  public static final String ATTENDANCE_SLOT_EMPLOYEE = "attendance_record_slot_employee_unique";

  /** MVP-060A: one check-in and at most one check-out per record. */
  public static final String ATTENDANCE_EVENT_TYPE = "attendance_event_record_type_unique";

  /** MVP-060A: at most one actionable open record per employee. */
  public static final String ATTENDANCE_ONE_OPEN = "attendance_record_one_open";

  /** MVP-061A: one offline event per tenant, employee and client event. */
  public static final String ATTENDANCE_CLIENT_EVENT = "attendance_event_client_event_unique";

  /** MVP-062: a correction names its record's current version and values. */
  public static final String CORRECTION_SEQUENCE = "attendance_correction_sequence";

  /** MVP-062: a missing record is created for the effective assignee of an operational slot. */
  public static final String CORRECTION_CREATE = "attendance_correction_create";

  /** MVP-062: one correction per record and version. */
  public static final String CORRECTION_VERSION = "attendance_correction_version_unique";

  /** MVP-070A: one generation per tenant, site and week. */
  public static final String TIMESHEET_GENERATION_WEEK = "timesheet_generation_site_week_unique";

  /** MVP-070A: a revision follows the timesheet's latest one. */
  public static final String TIMESHEET_REVISION_SEQUENCE = "timesheet_revision_sequence";

  /** MVP-070A: a generation changes only by a refresh. */
  public static final String TIMESHEET_GENERATION_GUARD = "timesheet_generation_guard";

  /** MVP-070A: a timesheet changes only to its next revision. */
  public static final String TIMESHEET_GUARD = "timesheet_guard";

  private ScheduleConstraintViolations() {}

  /**
   * The violated constraint of an integrity failure (SQL state class 23).
   *
   * @param failure the failure
   * @return the constraint name, when the database reported one
   */
  public static Optional<String> constraint(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof PSQLException psql) {
        String state = psql.getSQLState();
        ServerErrorMessage message = psql.getServerErrorMessage();
        if (state == null || !state.startsWith("23") || message == null) {
          return Optional.empty();
        }
        return Optional.ofNullable(message.getConstraint());
      }
    }
    return Optional.empty();
  }
}
