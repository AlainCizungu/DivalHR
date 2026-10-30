package com.divalhr.core.payroll.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The pure decision of a payroll approval (MVP-094C, ADR 0035 section 6): given the frozen source
 * facts and the calculation lines, which blockers and warnings exist, whether the package can be
 * committed, is blocked or would change nothing, and the package totals. Nothing is recalculated:
 * the amounts are copied from the frozen statutory calculation lines and this class proves the copy
 * (every line adds up, the lines add up to the calculation's totals, the counts match).
 *
 * <p>Exact arithmetic: whole CDF francs in {@code long} with overflow checks; an overflow is an
 * arithmetic mismatch. Net is gross less the employee deductions; employer contributions never
 * reduce the net; employer cost is gross plus employer contributions. Restricted financial data:
 * nothing is printed by {@code toString}.
 */
public final class PayrollApprovalEvaluator {

  /** The most payslips of one package. */
  public static final int MAX_PAYSLIPS = 5_000;

  /** The most packages of one chain (entity, month, currency). */
  public static final int MAX_VERSIONS = 25;

  private PayrollApprovalEvaluator() {}

  /** The decision. */
  public enum Status {
    /** The package can be approved. */
    READY,
    /** A blocker exists. */
    BLOCKED,
    /** An APPROVED package exists for exactly this run and calculation. */
    NO_CHANGE
  }

  /**
   * One component of a calculation line, as far as the proof needs it.
   *
   * @param party {@code EMPLOYEE} or {@code EMPLOYER}
   * @param category {@code SOCIAL_CONTRIBUTION} or {@code INCOME_TAX}
   * @param amountMinor the amount
   */
  public record ComponentFact(String party, String category, long amountMinor) {}

  /**
   * One frozen calculation line.
   *
   * @param employeeId employee
   * @param employeeNumber employee number
   * @param excluded whether subject to neither CNSS nor IRPP
   * @param grossMinor contractual gross
   * @param deductionsMinor employee deductions
   * @param contributionsMinor employer contributions
   * @param netMinor preview net
   * @param costMinor employer cost
   * @param components the line's components
   */
  public record LineFact(
      UUID employeeId,
      String employeeNumber,
      boolean excluded,
      long grossMinor,
      long deductionsMinor,
      long contributionsMinor,
      long netMinor,
      long costMinor,
      List<ComponentFact> components) {

    /** Copies the list. */
    public LineFact {
      components = List.copyOf(components);
    }

    @Override
    public String toString() {
      return "ApprovalLine[" + employeeId + "]";
    }
  }

  /**
   * Package totals in whole francs.
   *
   * @param grossMinor contractual gross
   * @param deductionsMinor employee deductions
   * @param contributionsMinor employer contributions
   * @param netMinor approved net
   * @param costMinor employer cost
   */
  public record Totals(
      long grossMinor,
      long deductionsMinor,
      long contributionsMinor,
      long netMinor,
      long costMinor) {

    @Override
    public String toString() {
      return "ApprovalTotals[redacted]";
    }
  }

  /**
   * The source facts the application read and compared (digests and states are compared by the
   * caller; this record carries only the verdicts).
   *
   * @param runCurrent the run is the current DRAFT of its chain
   * @param runCurrency the run currency
   * @param calculationCurrent the calculation is the current DRAFT
   * @param calculationCurrency the calculation currency
   * @param packFound the rule pack row exists
   * @param packVerified the calculation's pack is VERIFIED
   * @param synthetic the pack is a SYNTHETIC-TEST pack
   * @param policyMatches the policy in force equals the one frozen in the run
   * @param profilesMatch the profiles now equal those the calculation used
   * @param rulePackMatches the activation and pack now equal those the calculation used
   * @param sourceMatches the frozen run still has the digest the calculation recorded, and a fresh
   *     payroll calculation equals the frozen run
   * @param runLineCount lines of the frozen run
   * @param employeesMatch the calculation covers exactly the employees of the run
   * @param calculationSupported supported lines the calculation declares
   * @param calculationExcluded excluded lines the calculation declares
   * @param declared the totals the calculation declares
   * @param samePreparer the approver also created the run or the calculation
   * @param otherApproved another APPROVED package exists for the chain, from other sources
   * @param exactApproved an APPROVED package exists for exactly this run and calculation
   * @param voidedPair this run and calculation were in a VOIDED package
   * @param chainCount packages already in the chain
   */
  public record Facts(
      boolean runCurrent,
      String runCurrency,
      boolean calculationCurrent,
      String calculationCurrency,
      boolean packFound,
      boolean packVerified,
      boolean synthetic,
      boolean policyMatches,
      boolean profilesMatch,
      boolean rulePackMatches,
      boolean sourceMatches,
      int runLineCount,
      boolean employeesMatch,
      int calculationSupported,
      int calculationExcluded,
      Totals declared,
      boolean samePreparer,
      boolean otherApproved,
      boolean exactApproved,
      boolean voidedPair,
      int chainCount) {}

  /**
   * The result.
   *
   * @param status ready, blocked or unchanged
   * @param blockers the blockers in contract order
   * @param warnings the warnings in contract order
   * @param totals the totals of the lines, or {@code null} when they cannot be trusted (more than
   *     5,000 lines or an overflow)
   * @param payslipCount lines
   * @param excludedCount excluded lines
   */
  public record Result(
      Status status,
      Set<PayrollApprovalBlocker> blockers,
      Set<PayrollApprovalWarning> warnings,
      Totals totals,
      int payslipCount,
      int excludedCount) {

    /** Copies the sets in contract order. */
    public Result {
      EnumSet<PayrollApprovalBlocker> blocked = EnumSet.noneOf(PayrollApprovalBlocker.class);
      blocked.addAll(blockers);
      EnumSet<PayrollApprovalWarning> warned = EnumSet.noneOf(PayrollApprovalWarning.class);
      warned.addAll(warnings);
      blockers = Collections.unmodifiableSet(blocked);
      warnings = Collections.unmodifiableSet(warned);
    }

    @Override
    public String toString() {
      return "ApprovalResult[" + status + "]";
    }
  }

  /**
   * Decides an approval.
   *
   * @param facts the source verdicts
   * @param lines the frozen calculation lines (at most 5,001 are read)
   * @return the decision
   */
  public static Result evaluate(Facts facts, List<LineFact> lines) {
    EnumSet<PayrollApprovalBlocker> blockers = EnumSet.noneOf(PayrollApprovalBlocker.class);
    EnumSet<PayrollApprovalWarning> warnings = EnumSet.noneOf(PayrollApprovalWarning.class);

    if (!facts.runCurrent()) {
      blockers.add(PayrollApprovalBlocker.SOURCE_RUN_NOT_CURRENT);
    }
    if (!facts.calculationCurrent()) {
      blockers.add(PayrollApprovalBlocker.CALCULATION_NOT_CURRENT);
    }
    if (!"CDF".equals(facts.runCurrency()) || !"CDF".equals(facts.calculationCurrency())) {
      blockers.add(PayrollApprovalBlocker.CURRENCY_NOT_SUPPORTED);
    }
    if (!facts.packFound()) {
      blockers.add(PayrollApprovalBlocker.SOURCE_MISSING);
    } else if (!facts.packVerified()) {
      blockers.add(PayrollApprovalBlocker.RULE_PACK_NOT_VERIFIED);
    }
    if (!facts.policyMatches()) {
      blockers.add(PayrollApprovalBlocker.POLICY_CHANGED);
    }
    if (!facts.profilesMatch()) {
      blockers.add(PayrollApprovalBlocker.PROFILE_CHANGED);
    }
    if (!facts.rulePackMatches()) {
      blockers.add(PayrollApprovalBlocker.RULE_PACK_CHANGED);
    }
    if (!facts.sourceMatches()) {
      blockers.add(PayrollApprovalBlocker.SOURCE_CHANGED);
    }
    if (facts.runLineCount() == 0 || lines.isEmpty()) {
      blockers.add(PayrollApprovalBlocker.SOURCE_MISSING);
    }
    if (facts.voidedPair()) {
      blockers.add(PayrollApprovalBlocker.SOURCE_ALREADY_VOIDED);
    }
    if (facts.otherApproved() && !facts.exactApproved()) {
      blockers.add(PayrollApprovalBlocker.ALREADY_APPROVED);
    }
    if (lines.size() > MAX_PAYSLIPS
        || (!facts.exactApproved() && facts.chainCount() >= MAX_VERSIONS)) {
      blockers.add(PayrollApprovalBlocker.LIMIT_REACHED);
    }

    int excludedCount = 0;
    int zeroNet = 0;
    int payable = 0;
    Totals totals = null;
    if (lines.size() <= MAX_PAYSLIPS) {
      boolean sound = true;
      long gross = 0;
      long deductions = 0;
      long contributions = 0;
      long net = 0;
      long cost = 0;
      Set<UUID> employees = new HashSet<>();
      try {
        for (LineFact line : lines) {
          sound &= employees.add(line.employeeId()) && lineAddsUp(line);
          if (line.excluded()) {
            excludedCount++;
          }
          if (line.netMinor() == 0) {
            zeroNet++;
          }
          if (line.netMinor() > 0) {
            payable++;
          }
          gross = Math.addExact(gross, line.grossMinor());
          deductions = Math.addExact(deductions, line.deductionsMinor());
          contributions = Math.addExact(contributions, line.contributionsMinor());
          net = Math.addExact(net, line.netMinor());
          cost = Math.addExact(cost, line.costMinor());
        }
        totals = new Totals(gross, deductions, contributions, net, cost);
        sound &= totals.equals(facts.declared());
        sound &= net == gross - deductions && cost == Math.addExact(gross, contributions);
      } catch (ArithmeticException overflow) {
        sound = false;
        totals = null;
      }
      if (!sound) {
        blockers.add(PayrollApprovalBlocker.ARITHMETIC_MISMATCH);
      }
      int supported = lines.size() - excludedCount;
      if (lines.size() != facts.runLineCount()
          || !facts.employeesMatch()
          || supported != facts.calculationSupported()
          || excludedCount != facts.calculationExcluded()) {
        blockers.add(PayrollApprovalBlocker.CALCULATION_INCOMPLETE);
      }
      if (!lines.isEmpty() && payable == 0) {
        blockers.add(PayrollApprovalBlocker.NO_PAYABLE_LINES);
      }
    }

    if (facts.samePreparer()) {
      warnings.add(PayrollApprovalWarning.SAME_PREPARER_AND_APPROVER);
    }
    if (facts.synthetic()) {
      warnings.add(PayrollApprovalWarning.SYNTHETIC_RULE_PACK);
    }
    if (excludedCount > 0) {
      warnings.add(PayrollApprovalWarning.EXCLUDED_LINES_PRESENT);
    }
    if (zeroNet > 0) {
      warnings.add(PayrollApprovalWarning.ZERO_NET_LINES_PRESENT);
    }

    Status status;
    if (!blockers.isEmpty()) {
      status = Status.BLOCKED;
    } else if (facts.exactApproved()) {
      status = Status.NO_CHANGE;
    } else {
      status = Status.READY;
    }
    return new Result(status, blockers, warnings, totals, lines.size(), excludedCount);
  }

  /**
   * Whether one line adds up: net is gross less deductions, cost is gross plus contributions, the
   * deductions are the employee components and the contributions the employer components, and an
   * excluded line carries nothing. Employer components never enter the deductions or the net.
   *
   * @param line the line
   * @return true when it adds up
   */
  public static boolean lineAddsUp(LineFact line) {
    try {
      long employee = 0;
      long employer = 0;
      for (ComponentFact component : line.components()) {
        if (component.amountMinor() < 0) {
          return false;
        }
        if ("EMPLOYER".equals(component.party())) {
          employer = Math.addExact(employer, component.amountMinor());
        } else if ("EMPLOYEE".equals(component.party())) {
          employee = Math.addExact(employee, component.amountMinor());
        } else {
          return false;
        }
      }
      return line.grossMinor() >= 0
          && line.deductionsMinor() >= 0
          && line.contributionsMinor() >= 0
          && line.deductionsMinor() <= line.grossMinor()
          && line.deductionsMinor() == employee
          && line.contributionsMinor() == employer
          && line.netMinor() == line.grossMinor() - line.deductionsMinor()
          && line.costMinor() == Math.addExact(line.grossMinor(), line.contributionsMinor())
          && (!line.excluded()
              || (line.deductionsMinor() == 0
                  && line.contributionsMinor() == 0
                  && line.components().isEmpty()));
    } catch (ArithmeticException overflow) {
      return false;
    }
  }

  /**
   * The blockers that, at commit, are answered as a stale preview rather than as a blocked
   * package.
   *
   * @param blockers a result's blockers
   * @return the drift blockers among them, in contract order
   */
  public static List<PayrollApprovalBlocker> drift(Set<PayrollApprovalBlocker> blockers) {
    List<PayrollApprovalBlocker> drift = new ArrayList<>();
    for (PayrollApprovalBlocker blocker : PayrollApprovalBlocker.values()) {
      if (blockers.contains(blocker) && blocker.drift()) {
        drift.add(blocker);
      }
    }
    return drift;
  }
}
