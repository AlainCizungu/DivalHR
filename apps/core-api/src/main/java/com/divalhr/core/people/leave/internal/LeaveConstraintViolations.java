package com.divalhr.core.people.leave.internal;

import java.util.Optional;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * Identifies which named V18 constraint an integrity failure violated (MVP-040A), so that only the
 * named ones map to business codes. Nothing from the database message (SQL, values or details) is
 * ever exposed or logged.
 */
public final class LeaveConstraintViolations {

  /** The constraint that decides duplicate codes in one tenant. */
  public static final String CODE_UNIQUE = "leave_policy_code_unique";

  /**
   * The exclusion that keeps an employee's pending and approved requests from overlapping
   * (MVP-041A, MVP-041B).
   */
  public static final String REQUEST_OVERLAP = "leave_request_no_overlap";

  /** The one decision per request (MVP-041B). */
  public static final String DECISION_UNIQUE = "leave_request_decision_request_unique";

  /** The deferred request/decision consistency checks (MVP-041B). */
  public static final java.util.Set<String> DECISION_CONSISTENCY =
      java.util.Set.of(
          "leave_request_decided", "leave_request_decision_consistent", "leave_request_transition");

  private LeaveConstraintViolations() {}

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
