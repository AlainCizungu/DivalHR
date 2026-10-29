package com.divalhr.core.payroll.internal;

import java.util.Optional;
import java.util.Set;

/**
 * The named V43 constraints the payroll module maps to stable business answers (MVP-093A). The
 * service checks the version, the transition and the dates under the employee's lock, so a guard
 * that still fires is a defect (or a race the lock should have excluded), except the 100-change
 * limit, which is a business answer. Nothing from the database message is ever exposed or logged.
 */
public final class CompensationConstraintViolations {

  /** At most 100 changes per employee. */
  public static final String CHANGE_LIMIT = "payroll_compensation_change_limit";

  /** The constraints that mean another command moved the employee's timeline first. */
  public static final Set<String> CONFLICT =
      Set.of(
          "payroll_compensation_no_overlap",
          "payroll_compensation_exact",
          "payroll_compensation_change_exact",
          "payroll_compensation_change_sequence",
          "payroll_compensation_change_first",
          "payroll_compensation_change_time",
          "payroll_compensation_change_target",
          "payroll_compensation_creation",
          "payroll_compensation_supersession");

  private CompensationConstraintViolations() {}

  /**
   * The violated constraint of an integrity failure.
   *
   * @param failure any failure
   * @return the constraint name, if the failure is a named PostgreSQL integrity violation
   */
  public static Optional<String> constraint(Throwable failure) {
    return PayrollConstraintViolations.constraint(failure);
  }
}
