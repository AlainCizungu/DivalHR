package com.divalhr.core.payroll.internal;

import java.util.Optional;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/** The V37 constraints the payroll module maps to stable conflicts; no database text leaves. */
public final class PayrollConstraintViolations {

  /** One run per legal entity, period and version. */
  public static final String RUN_VERSION = "payroll_input_run_version_unique";

  /** The deferred exactness of a run. */
  public static final String RUN_EXACT = "payroll_input_run_exact";

  private PayrollConstraintViolations() {}

  /**
   * The constraint behind an integrity failure.
   *
   * @param failure the failure
   * @return the constraint name, if the cause is a PostgreSQL integrity error
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
