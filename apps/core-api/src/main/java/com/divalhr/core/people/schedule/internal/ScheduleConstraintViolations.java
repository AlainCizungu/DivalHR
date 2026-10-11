package com.divalhr.core.people.schedule.internal;

import java.util.Optional;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * The V25 constraints a publication may race into, and the integrity-violation constraint name of a
 * failure (never returned to clients or logs).
 */
public final class ScheduleConstraintViolations {

  /** One published schedule per site and date (V25). */
  public static final String SITE_OVERLAP = "work_schedule_site_no_overlap";

  /** No overlapping assigned instants for an employee (V25). */
  public static final String EMPLOYEE_OVERLAP = "work_schedule_slot_employee_no_overlap";

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
