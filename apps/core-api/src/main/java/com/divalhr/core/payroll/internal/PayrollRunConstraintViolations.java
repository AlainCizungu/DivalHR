package com.divalhr.core.payroll.internal;

import com.divalhr.core.platform.error.ErrorCode;
import java.util.Optional;
import java.util.Set;

/**
 * The named V44 constraints the payroll module maps to stable business answers (MVP-094A). The
 * service checks the versions, the policy and the inputs under the locks, so a guard that still
 * fires is a lost race (or a defect), except the version and policy limits, which are business
 * answers. Nothing from the database message is ever exposed or logged.
 */
public final class PayrollRunConstraintViolations {

  /** At most 60 policies per legal entity. */
  public static final String POLICY_LIMIT = "payroll_run_policy_sequence_range";

  /** At most 25 versions per chain. */
  public static final String RUN_LIMIT = "payroll_pay_run_version_range";

  /** The constraints that mean another policy command moved the timeline first. */
  public static final Set<String> POLICY_CONFLICT =
      Set.of(
          "payroll_run_policy_sequence_unique",
          "payroll_run_policy_effective_unique",
          "payroll_run_policy_sequence");

  /** The constraints that mean another run command moved the chain first. */
  public static final Set<String> RUN_CONFLICT =
      Set.of(
          "payroll_pay_run_version_unique",
          "payroll_pay_run_version",
          "payroll_pay_run_one_draft",
          "payroll_pay_run_chain",
          "payroll_pay_run_supersession_only",
          "payroll_pay_run_supersession");

  /** The constraint that means the policy in force changed under the preview. */
  public static final String POLICY_IN_FORCE = "payroll_pay_run_policy_in_force";

  private PayrollRunConstraintViolations() {}

  /**
   * The violated constraint of an integrity failure.
   *
   * @param failure any failure
   * @return the constraint name, if the failure is a named PostgreSQL integrity violation
   */
  public static Optional<String> constraint(Throwable failure) {
    return PayrollConstraintViolations.constraint(failure);
  }

  /**
   * The stable business answer of a constraint, if it has one.
   *
   * @param constraint a constraint name
   * @return the error code, or empty when the violation is a defect
   */
  public static Optional<ErrorCode> answer(String constraint) {
    if (POLICY_LIMIT.equals(constraint) || RUN_LIMIT.equals(constraint)) {
      return Optional.of(ErrorCode.PAYROLL_RUN_LIMIT_REACHED);
    }
    if (POLICY_CONFLICT.contains(constraint)) {
      return Optional.of(ErrorCode.PAYROLL_POLICY_CONFLICT);
    }
    if (RUN_CONFLICT.contains(constraint)) {
      return Optional.of(ErrorCode.PAYROLL_RUN_VERSION_CONFLICT);
    }
    if (POLICY_IN_FORCE.equals(constraint)) {
      return Optional.of(ErrorCode.PAYROLL_RUN_PREVIEW_STALE);
    }
    return Optional.empty();
  }
}
