package com.divalhr.core.operations.internal;

import java.util.Optional;
import java.util.Set;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * Identifies which named V42 constraint an integrity failure violated (MVP-081), so that only the
 * named ones map to business codes. Nothing from the database message (SQL, values or details) is
 * ever exposed or logged.
 */
public final class ExpenseConstraintViolations {

  /** The 5,000 open expenses per tenant limit (D81-13). */
  public static final String TENANT_LIMIT = "operations_expense_tenant_limit";

  /** The 100 open expenses per employee limit (D81-13). */
  public static final String EMPLOYEE_LIMIT = "operations_expense_employee_limit";

  /** The guards and deferred checks that mean the application computed an impossible change. */
  public static final Set<String> CONSISTENCY =
      Set.of(
          "operations_expense_initial",
          "operations_expense_terminal",
          "operations_expense_identity",
          "operations_expense_transition",
          "operations_expense_revision_move",
          "operations_expense_exact",
          "operations_expense_revision_sequence",
          "operations_expense_revision_append",
          "operations_expense_revision_immutable",
          "operations_expense_revision_identity",
          "operations_expense_revision_exact",
          "operations_expense_receipt_immutable",
          "operations_expense_receipt_submitted",
          "operations_expense_action_sequence",
          "operations_expense_action_transition",
          "operations_expense_action_owner",
          "operations_expense_action_self_decision",
          "operations_expense_action_exact");

  private ExpenseConstraintViolations() {}

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
