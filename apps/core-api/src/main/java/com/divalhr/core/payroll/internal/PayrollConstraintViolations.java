package com.divalhr.core.payroll.internal;

import java.util.Optional;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * The V37 and V38 constraints the payroll module maps to stable conflicts; no database text leaves.
 */
public final class PayrollConstraintViolations {

  /** One run per legal entity, period and version. */
  public static final String RUN_VERSION = "payroll_input_run_version_unique";

  /** The deferred exactness of a run. */
  public static final String RUN_EXACT = "payroll_input_run_exact";

  /** V38: a version replaces exactly the previous, latest version of its scope. */
  public static final String SUPERSESSION_VALID = "payroll_input_supersession_valid";

  /** V38: a locked run is never superseded. */
  public static final String SUPERSESSION_LOCKED = "payroll_input_supersession_locked";

  /** V38: a run is superseded at most once. */
  public static final String SUPERSESSION_ONCE = "payroll_input_supersession_once";

  /** V38: the lock records exactly the run. */
  public static final String LOCK_VALID = "payroll_input_lock_valid";

  /** V38: only the latest version is locked. */
  public static final String LOCK_LATEST = "payroll_input_lock_latest";

  /** V38: one lock per run. */
  public static final String LOCK_ONE = "payroll_input_lock_one";

  /** V38: one export per locked run. */
  public static final String EXPORT_ONE = "payroll_input_export_one";

  /** V38: the export reproduces exactly its run. */
  public static final String EXPORT_EXACT = "payroll_input_export_exact";

  /** V38: a version above 1 supersedes exactly the previous version. */
  public static final String RUN_CHAIN = "payroll_input_run_chain";

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
