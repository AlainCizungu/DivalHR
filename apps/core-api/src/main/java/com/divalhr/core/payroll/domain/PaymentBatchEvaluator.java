package com.divalhr.core.payroll.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Decides whether a payment batch can be prepared from an approved payroll package and what its
 * lines are (MVP-095A, D95A-4 and D95A-5). Pure: it copies the immutable payslip amounts, performs
 * no payroll calculation and no currency conversion, and one blocker blocks the whole batch (no
 * partial preparation).
 */
public final class PaymentBatchEvaluator {

  /** Most lines in one batch. */
  public static final int MAX_LINES = 5000;

  /** Most batches in the chain of one package. */
  public static final int MAX_VERSIONS = 25;

  private PaymentBatchEvaluator() {}

  /**
   * A payslip of the package.
   *
   * @param id payslip
   * @param employeeId employee
   * @param employeeNumber employee number
   * @param grossMinor gross in minor units
   * @param deductionsMinor employee deductions in minor units
   * @param netMinor net in minor units
   * @param excluded whether the payslip is excluded from the payroll
   */
  public record Slip(
      UUID id,
      UUID employeeId,
      String employeeNumber,
      long grossMinor,
      long deductionsMinor,
      long netMinor,
      boolean excluded) {

    @Override
    public String toString() {
      return "PaymentBatchSlip[" + id + "]";
    }
  }

  /**
   * The destination effective for an employee on the approval date.
   *
   * @param id destination row
   * @param sequence version
   * @param method method
   * @param country ISO country, or null
   * @param providerCode provider code, or null
   * @param providerName provider name, or null
   * @param masked masked value, or null
   * @param fingerprint keyed fingerprint, or null
   * @param authentic whether the sealed envelope authenticated and matched the plain columns
   * @param changedAfterApproval whether a newer version exists that took effect after approval
   */
  public record Destination(
      UUID id,
      int sequence,
      PaymentMethod method,
      String country,
      String providerCode,
      String providerName,
      String masked,
      String fingerprint,
      boolean authentic,
      boolean changedAfterApproval) {

    @Override
    public String toString() {
      return "PaymentBatchDestination[redacted]";
    }
  }

  /**
   * What is known about the package and the chain.
   *
   * @param approved whether the package is APPROVED
   * @param latest whether it is the latest package of its entity, month and currency
   * @param preparedBatchExists whether a PREPARED batch exists for it
   * @param chainVersion the highest batch version of the package, 0 when none
   */
  public record Source(
      boolean approved, boolean latest, boolean preparedBatchExists, int chainVersion) {}

  /**
   * One line of the batch, or of a blocked preview.
   *
   * @param payslipId payslip
   * @param employeeId employee
   * @param employeeNumber employee number
   * @param destination the destination, or null when missing
   * @param net approved net in minor units
   * @param issue the problem, or null
   */
  public record Line(
      UUID payslipId,
      UUID employeeId,
      String employeeNumber,
      Destination destination,
      long net,
      PaymentBatchLineIssue issue) {

    @Override
    public String toString() {
      return "PaymentBatchLine[" + payslipId + "]";
    }
  }

  /**
   * The outcome.
   *
   * @param blockers why it cannot be prepared
   * @param warnings what to look at
   * @param lines payable lines by employee ID
   * @param zeroNetExcluded zero-net or excluded payslips left out
   * @param bank BANK_ACCOUNT lines
   * @param mobile MOBILE_MONEY lines
   * @param manual MANUAL lines
   * @param totalNet positive-net total in minor units
   */
  public record Evaluation(
      List<PaymentBatchBlocker> blockers,
      List<PaymentBatchWarning> warnings,
      List<Line> lines,
      int zeroNetExcluded,
      int bank,
      int mobile,
      int manual,
      long totalNet) {

    /**
     * @return whether the batch can be prepared
     */
    public boolean ready() {
      return blockers.isEmpty();
    }
  }

  /**
   * Evaluates a package.
   *
   * @param source package and chain facts
   * @param slips the package's payslips
   * @param destinations the effective destination of each payable employee, by employee
   * @return the evaluation
   */
  public static Evaluation evaluate(
      Source source, List<Slip> slips, Map<UUID, Destination> destinations) {
    Set<PaymentBatchBlocker> blockers = EnumSet.noneOf(PaymentBatchBlocker.class);
    Set<PaymentBatchWarning> warnings = EnumSet.noneOf(PaymentBatchWarning.class);
    if (!source.approved()) {
      blockers.add(PaymentBatchBlocker.PACKAGE_VOIDED);
    }
    if (source.approved() && !source.latest()) {
      blockers.add(PaymentBatchBlocker.PACKAGE_NOT_LATEST);
    }
    if (source.preparedBatchExists()) {
      blockers.add(PaymentBatchBlocker.BATCH_ALREADY_PREPARED);
    }
    if (source.chainVersion() >= MAX_VERSIONS) {
      blockers.add(PaymentBatchBlocker.LIMIT_REACHED);
    }
    if (!source.approved() || !source.latest()) {
      return new Evaluation(sorted(blockers), sorted(warnings), List.of(), 0, 0, 0, 0, 0);
    }

    List<Slip> ordered = new ArrayList<>(slips);
    ordered.sort(Comparator.comparing(slip -> slip.employeeId().toString()));
    List<Line> lines = new ArrayList<>();
    int zero = 0;
    int bank = 0;
    int mobile = 0;
    int manual = 0;
    long total = 0;
    Map<String, Integer> fingerprints = new HashMap<>();
    for (Slip slip : ordered) {
      boolean arithmetic =
          slip.netMinor() >= 0
              && slip.netMinor() == slip.grossMinor() - slip.deductionsMinor()
              && (!slip.excluded() || slip.netMinor() == 0);
      if (!arithmetic) {
        blockers.add(PaymentBatchBlocker.INTEGRITY_FAILURE);
        lines.add(
            new Line(
                slip.id(),
                slip.employeeId(),
                slip.employeeNumber(),
                null,
                Math.max(slip.netMinor(), 0),
                PaymentBatchLineIssue.INTEGRITY_FAILURE));
        continue;
      }
      if (slip.excluded() || slip.netMinor() == 0) {
        zero++;
        continue;
      }
      Destination destination = destinations.get(slip.employeeId());
      PaymentBatchLineIssue issue = null;
      if (destination == null) {
        issue = PaymentBatchLineIssue.MISSING_DESTINATION;
        blockers.add(PaymentBatchBlocker.MISSING_DESTINATION);
      } else if (!destination.authentic()) {
        issue = PaymentBatchLineIssue.DESTINATION_INVALID;
        blockers.add(PaymentBatchBlocker.DESTINATION_INVALID);
      } else {
        switch (destination.method()) {
          case BANK_ACCOUNT -> bank++;
          case MOBILE_MONEY -> mobile++;
          case MANUAL -> manual++;
        }
        if (destination.changedAfterApproval()) {
          warnings.add(PaymentBatchWarning.DESTINATION_CHANGED_AFTER_APPROVAL);
        }
        if (destination.method() == PaymentMethod.MANUAL) {
          warnings.add(PaymentBatchWarning.MANUAL_LINES_PRESENT);
        }
        if (destination.fingerprint() != null
            && fingerprints.merge(destination.fingerprint(), 1, Integer::sum) > 1) {
          warnings.add(PaymentBatchWarning.DUPLICATE_DESTINATION);
        }
      }
      total += slip.netMinor();
      lines.add(
          new Line(
              slip.id(),
              slip.employeeId(),
              slip.employeeNumber(),
              issue == null ? destination : null,
              slip.netMinor(),
              issue));
    }
    if (zero > 0) {
      warnings.add(PaymentBatchWarning.ZERO_NET_EXCLUDED);
    }
    if (lines.isEmpty() && blockers.isEmpty()) {
      blockers.add(PaymentBatchBlocker.NO_PAYABLE_LINES);
    }
    if (lines.size() > MAX_LINES) {
      blockers.add(PaymentBatchBlocker.LIMIT_REACHED);
    }
    return new Evaluation(
        sorted(blockers), sorted(warnings), List.copyOf(lines), zero, bank, mobile, manual, total);
  }

  private static <E extends Enum<E>> List<E> sorted(Set<E> values) {
    return values.stream().sorted().toList();
  }
}
