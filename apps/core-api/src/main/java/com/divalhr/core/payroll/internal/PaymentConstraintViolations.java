package com.divalhr.core.payroll.internal;

import com.divalhr.core.platform.error.ErrorCode;
import java.util.Optional;
import java.util.Set;

/**
 * The named V47 constraints the payment module maps to stable business answers (MVP-095A). The
 * services check everything under the locks, so a guard that still fires is a lost race (or a
 * defect), except the limits, which are business answers. Anything not listed here is a defect and
 * stays unmapped. Nothing from the database message is ever exposed or logged.
 */
public final class PaymentConstraintViolations {

  /** The 25-version chain limit of a package. */
  public static final Set<String> BATCH_LIMITS = Set.of("payment_batch_version_range");

  /** Another preparation moved the chain first. */
  public static final Set<String> BATCH_VERSION_CONFLICT =
      Set.of("payment_batch_version_unique", "payment_batch_version");

  /** A PREPARED batch already exists. */
  public static final Set<String> BATCH_ALREADY_PREPARED = Set.of("payment_batch_one_prepared");

  /** The package or a payslip moved under the preview. */
  public static final Set<String> BATCH_STALE =
      Set.of(
          "payment_batch_package_state",
          "payment_batch_package_latest",
          "payment_batch_line_payslip",
          "payment_batch_line_destination_copy",
          "payment_batch_exact");

  /** The batch is already cancelled. */
  public static final Set<String> BATCH_CANCELLED =
      Set.of(
          "payment_batch_cancel_batch_unique",
          "payment_batch_cancel_state",
          "payment_batch_lifecycle_only",
          "payment_batch_cancel_evidence");

  /** Another command moved the employee's destination chain first. */
  public static final Set<String> DESTINATION_VERSION_CONFLICT =
      Set.of("payment_destination_sequence_unique", "payment_destination_sequence");

  /** The effective date does not follow the current version. */
  public static final Set<String> DESTINATION_INVALID =
      Set.of("payment_destination_effective_order");

  /** The package has a PREPARED batch, so it cannot be voided. */
  public static final Set<String> VOID_BLOCKED = Set.of("payroll_payment_batch_blocks_void");

  private PaymentConstraintViolations() {}

  /**
   * The stable business answer of a batch constraint, if it has one.
   *
   * @param constraint a constraint name
   * @return the error code, or empty when the violation is a defect
   */
  public static Optional<ErrorCode> batchAnswer(String constraint) {
    if (BATCH_LIMITS.contains(constraint)) {
      return Optional.of(ErrorCode.PAYMENT_BATCH_LIMIT_REACHED);
    }
    if (BATCH_VERSION_CONFLICT.contains(constraint)) {
      return Optional.of(ErrorCode.PAYMENT_BATCH_VERSION_CONFLICT);
    }
    if (BATCH_ALREADY_PREPARED.contains(constraint)) {
      return Optional.of(ErrorCode.PAYMENT_BATCH_ALREADY_PREPARED);
    }
    if (BATCH_STALE.contains(constraint)) {
      return Optional.of(ErrorCode.PAYMENT_BATCH_PREVIEW_STALE);
    }
    if (BATCH_CANCELLED.contains(constraint)) {
      return Optional.of(ErrorCode.PAYMENT_BATCH_CANCELLED);
    }
    return Optional.empty();
  }

  /**
   * The stable business answer of a destination constraint, if it has one.
   *
   * @param constraint a constraint name
   * @return the error code, or empty when the violation is a defect
   */
  public static Optional<ErrorCode> destinationAnswer(String constraint) {
    if (DESTINATION_VERSION_CONFLICT.contains(constraint)) {
      return Optional.of(ErrorCode.PAYMENT_DESTINATION_VERSION_CONFLICT);
    }
    if (DESTINATION_INVALID.contains(constraint)) {
      return Optional.of(ErrorCode.PAYMENT_DESTINATION_INVALID);
    }
    return Optional.empty();
  }
}
