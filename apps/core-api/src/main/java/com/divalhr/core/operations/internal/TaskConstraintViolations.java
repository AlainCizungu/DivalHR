package com.divalhr.core.operations.internal;

import java.util.Optional;
import java.util.Set;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * Identifies which named V41 constraint an integrity failure violated (MVP-080), so that only the
 * named ones map to business codes. Nothing from the database message (SQL, values or details) is
 * ever exposed or logged.
 */
public final class TaskConstraintViolations {

  /** The 10,000 non-terminal tasks per tenant limit (D80-11). */
  public static final String OPEN_LIMIT = "operations_task_open_limit";

  /** The assignee's foreign key (a concurrent removal; never reachable for a visible employee). */
  public static final String ASSIGNEE = "operations_task_assignee";

  /** The guards and deferred checks that mean the application computed an impossible change. */
  public static final Set<String> CONSISTENCY =
      Set.of(
          "operations_task_initial",
          "operations_task_terminal",
          "operations_task_identity",
          "operations_task_transition",
          "operations_task_exact",
          "operations_task_action_sequence",
          "operations_task_action_transition",
          "operations_task_action_assignee",
          "operations_task_action_exact");

  private TaskConstraintViolations() {}

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
