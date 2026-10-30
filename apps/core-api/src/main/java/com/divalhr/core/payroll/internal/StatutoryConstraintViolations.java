package com.divalhr.core.payroll.internal;

import com.divalhr.core.platform.error.ErrorCode;
import java.util.Optional;
import java.util.Set;

/**
 * The named V45 constraints the payroll module maps to stable business answers (MVP-094B). The
 * services check versions, limits, the pack and the profiles under the locks, so a guard that still
 * fires is a lost race (or a defect), except the limits, which are business answers. Nothing from
 * the database message is ever exposed or logged.
 */
public final class StatutoryConstraintViolations {

  /** At most 60 activations per legal entity, 100 profile rows per employee, 25 versions. */
  public static final Set<String> LIMITS =
      Set.of(
          "payroll_statutory_activation_sequence_range",
          "payroll_statutory_profile_sequence_range",
          "payroll_statutory_calculation_version_range");

  /** The constraints that mean another command moved a sequence, a month or a chain first. */
  public static final Set<String> VERSION_CONFLICT =
      Set.of(
          "payroll_statutory_activation_sequence_unique",
          "payroll_statutory_activation_month_unique",
          "payroll_statutory_activation_sequence",
          "payroll_statutory_profile_sequence_unique",
          "payroll_statutory_profile_sequence",
          "payroll_statutory_calculation_version_unique",
          "payroll_statutory_calculation_version",
          "payroll_statutory_calculation_one_draft",
          "payroll_statutory_calculation_chain",
          "payroll_statutory_calculation_supersession_only",
          "payroll_statutory_calculation_supersession");

  /** The guards that mean the pack is not VERIFIED (or RETIRED) at insertion. */
  public static final Set<String> PACK_NOT_VERIFIED =
      Set.of(
          "payroll_statutory_activation_pack_verified",
          "payroll_statutory_calculation_pack_state");

  /** The guards that mean the month lies outside the pack interval. */
  public static final Set<String> NOT_APPLICABLE =
      Set.of(
          "payroll_statutory_activation_interval",
          "payroll_statutory_calculation_pack_interval");

  /** The guards that mean the source run or the activation changed under the preview. */
  public static final Set<String> STALE =
      Set.of(
          "payroll_statutory_calculation_run_state",
          "payroll_statutory_calculation_activation_rule");

  private StatutoryConstraintViolations() {}

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
      return Optional.of(ErrorCode.STATUTORY_LIMIT_REACHED);
    }
    if (VERSION_CONFLICT.contains(constraint)) {
      return Optional.of(ErrorCode.STATUTORY_VERSION_CONFLICT);
    }
    if (PACK_NOT_VERIFIED.contains(constraint)) {
      return Optional.of(ErrorCode.STATUTORY_RULE_PACK_NOT_VERIFIED);
    }
    if (NOT_APPLICABLE.contains(constraint)) {
      return Optional.of(ErrorCode.STATUTORY_RULE_PACK_NOT_APPLICABLE);
    }
    if (STALE.contains(constraint)) {
      return Optional.of(ErrorCode.STATUTORY_PREVIEW_STALE);
    }
    return Optional.empty();
  }
}
