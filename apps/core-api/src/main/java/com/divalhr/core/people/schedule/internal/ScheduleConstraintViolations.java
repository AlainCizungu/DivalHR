package com.divalhr.core.people.schedule.internal;

import java.util.Optional;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * The V25 and V26 constraints a publication, cancellation or replacement may race into, and the
 * integrity-violation constraint name of a failure (never returned to clients or logs).
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
