package com.divalhr.core.payroll.internal;

import com.divalhr.core.platform.error.ErrorCode;
import java.util.Optional;
import java.util.Set;

/**
 * The named V46 constraints the payroll package module maps to stable business answers (MVP-094C).
 * The services check everything under the locks, so a guard that still fires is a lost race (or a
 * defect), except the limits, which are business answers. Anything not listed here is a defect and
 * stays unmapped. Nothing from the database message is ever exposed or logged.
 */
public final class PayrollPackageConstraintViolations {

  /** At most 25 versions per chain and 5000 payslips per package. */
  public static final Set<String> LIMITS =
      Set.of("payroll_package_version_range", "payroll_package_payslip_count");

  /** Another approval or void moved the chain first. */
  public static final Set<String> VERSION_CONFLICT =
      Set.of("payroll_package_version_unique", "payroll_package_version");

  /** The pair, the run or the calculation already has an approved package. */
  public static final Set<String> ALREADY_APPROVED =
      Set.of(
          "payroll_package_one_approved",
          "payroll_package_one_approved_run",
          "payroll_package_one_approved_calculation",
          "payroll_package_source_locked");

  /** A source or an input moved under the preview. */
  public static final Set<String> STALE =
      Set.of(
          "payroll_package_run_state",
          "payroll_package_calculation_state",
          "payroll_package_totals_source");

  /** The rule pack or a source is no longer approvable. */
  public static final Set<String> BLOCKED =
      Set.of("payroll_package_pack_state", "payroll_package_source_voided");

  /** The package is already voided. */
  public static final Set<String> VOIDED =
      Set.of("payroll_package_void_package_unique", "payroll_package_void_state");

  private PayrollPackageConstraintViolations() {}

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
    if (LIMITS.contains(constraint)) {
      return Optional.of(ErrorCode.PAYROLL_PACKAGE_LIMIT_REACHED);
    }
    if (VERSION_CONFLICT.contains(constraint)) {
      return Optional.of(ErrorCode.PAYROLL_PACKAGE_VERSION_CONFLICT);
    }
    if (ALREADY_APPROVED.contains(constraint)) {
      return Optional.of(ErrorCode.PAYROLL_PACKAGE_ALREADY_APPROVED);
    }
    if (STALE.contains(constraint)) {
      return Optional.of(ErrorCode.PAYROLL_APPROVAL_PREVIEW_STALE);
    }
    if (BLOCKED.contains(constraint)) {
      return Optional.of(ErrorCode.PAYROLL_PACKAGE_BLOCKED);
    }
    if (VOIDED.contains(constraint)) {
      return Optional.of(ErrorCode.PAYROLL_PACKAGE_VOIDED);
    }
    return Optional.empty();
  }
}
