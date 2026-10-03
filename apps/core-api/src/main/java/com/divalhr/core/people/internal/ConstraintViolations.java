package com.divalhr.core.people.internal;

import java.util.Optional;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * Identifies which named constraint or V14 trigger an integrity failure violated (MVP-021, M21-4),
 * so that only the named ones map to business codes. Nothing from the database message (SQL, values
 * or details) is ever exposed or logged.
 */
public final class ConstraintViolations {

  private ConstraintViolations() {}

  /**
   * Returns the violated constraint name of an integrity-constraint failure (SQLSTATE class 23).
   *
   * @param failure any failure
   * @return the constraint name, if the failure is a named PostgreSQL integrity violation
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
