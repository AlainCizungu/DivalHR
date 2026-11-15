package com.divalhr.core.payroll.internal;

import com.divalhr.core.platform.error.ErrorCode;
import java.util.Optional;
import java.util.Set;

/**
 * The named V51 constraints the payroll module maps to stable business answers (MVP-097A). The
 * service checks state, versions, independence, evidence and digests before it writes, so a guard
 * that still fires is a lost race (or a defect). Nothing from the database message is ever exposed
 * or logged.
 */
public final class StatutoryPackConstraintViolations {

  /** The constraints that mean the version or the transition changed under the command. */
  public static final Set<String> TRANSITION =
      Set.of(
          "payroll_statutory_pack_governance_transition",
          "payroll_statutory_pack_governance_version",
          "payroll_statutory_pack_governance_retired",
          "payroll_statutory_pack_governance_review",
          "payroll_statutory_pack_review_pkey",
          "payroll_statutory_pack_review_state");

  /** The guards that mean the reviewer is the submitter. */
  public static final Set<String> SELF_APPROVAL =
      Set.of("payroll_statutory_pack_review_independent");

  /** The guards that mean the period is invalid or taken. */
  public static final Set<String> PERIOD =
      Set.of("payroll_statutory_pack_governance_period", "payroll_statutory_pack_period_overlap");

  /** The guards that mean evidence is incomplete. */
  public static final Set<String> EVIDENCE = Set.of("payroll_statutory_pack_governance_incomplete");

  /** The guards that mean the digests or the validation no longer match. */
  public static final Set<String> DIGEST =
      Set.of(
          "payroll_statutory_pack_governance_digest",
          "payroll_statutory_pack_governance_validation",
          "payroll_statutory_pack_validation_run_digest");

  private StatutoryPackConstraintViolations() {}

  /**
   * The stable business answer of a constraint, if it has one.
   *
   * @param constraint a constraint name
   * @return the error code, or empty when the violation is a defect
   */
  public static Optional<ErrorCode> answer(String constraint) {
    if (TRANSITION.contains(constraint)) {
      return Optional.of(ErrorCode.STATUTORY_PACK_TRANSITION_NOT_ALLOWED);
    }
    if (SELF_APPROVAL.contains(constraint)) {
      return Optional.of(ErrorCode.STATUTORY_PACK_SELF_APPROVAL_FORBIDDEN);
    }
    if (PERIOD.contains(constraint)) {
      return Optional.of(ErrorCode.STATUTORY_PACK_EFFECTIVE_PERIOD_CONFLICT);
    }
    if (EVIDENCE.contains(constraint)) {
      return Optional.of(ErrorCode.STATUTORY_PACK_EVIDENCE_INCOMPLETE);
    }
    if (DIGEST.contains(constraint)) {
      return Optional.of(ErrorCode.STATUTORY_PACK_DIGEST_MISMATCH);
    }
    return Optional.empty();
  }

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
