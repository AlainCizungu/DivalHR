package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.payroll.domain.PayrollApprovalBlocker;
import com.divalhr.core.payroll.domain.PayrollApprovalEvaluator;
import com.divalhr.core.payroll.domain.PayrollApprovalEvaluator.ComponentFact;
import com.divalhr.core.payroll.domain.PayrollApprovalEvaluator.Facts;
import com.divalhr.core.payroll.domain.PayrollApprovalEvaluator.LineFact;
import com.divalhr.core.payroll.domain.PayrollApprovalEvaluator.Result;
import com.divalhr.core.payroll.domain.PayrollApprovalEvaluator.Status;
import com.divalhr.core.payroll.domain.PayrollApprovalEvaluator.Totals;
import com.divalhr.core.payroll.domain.PayrollApprovalWarning;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** MVP-094C: every blocker, warning and total of the pure approval decision. */
class PayrollApprovalEvaluatorTest {

  private static UUID id(int n) {
    return new UUID(0L, n);
  }

  /** A line whose components add up: one employee component and an optional employer one. */
  private static LineFact line(int n, long gross, long deductions, long contributions) {
    List<ComponentFact> components = new ArrayList<>();
    if (deductions > 0) {
      components.add(new ComponentFact("EMPLOYEE", "SOCIAL_CONTRIBUTION", deductions));
    }
    if (contributions > 0) {
      components.add(new ComponentFact("EMPLOYER", "SOCIAL_CONTRIBUTION", contributions));
    }
    return new LineFact(
        id(n),
        "E-" + n,
        false,
        gross,
        deductions,
        contributions,
        gross - deductions,
        gross + contributions,
        components);
  }

  private static LineFact excluded(int n, long gross) {
    return new LineFact(id(n), "E-" + n, true, gross, 0, 0, gross, gross, List.of());
  }

  /** The mutable facts of a decision; build() declares the totals of the lines. */
  private static final class Scenario {
    private boolean runCurrent = true;
    private String runCurrency = "CDF";
    private boolean calculationCurrent = true;
    private String calculationCurrency = "CDF";
    private boolean packFound = true;
    private boolean packVerified = true;
    private boolean synthetic = false;
    private boolean policyMatches = true;
    private boolean profilesMatch = true;
    private boolean rulePackMatches = true;
    private boolean sourceMatches = true;
    private Integer runLineCount;
    private boolean employeesMatch = true;
    private Integer supported;
    private Integer excludedCount;
    private Totals declared;
    private boolean samePreparer = false;
    private boolean otherApproved = false;
    private boolean exactApproved = false;
    private boolean voidedPair = false;
    private int chainCount = 0;
    private final List<LineFact> lines;

    Scenario(List<LineFact> lines) {
      this.lines = lines;
    }

    void limitAll() {
      runCurrent = false;
      calculationCurrent = false;
      packVerified = false;
      policyMatches = false;
      profilesMatch = false;
      rulePackMatches = false;
      sourceMatches = false;
      runCurrency = "USD";
      voidedPair = true;
      otherApproved = true;
      chainCount = 25;
    }

    Scenario with(UnaryOperator<Scenario> change) {
      return change.apply(this);
    }

    Facts facts() {
      long gross = 0;
      long deductions = 0;
      long contributions = 0;
      long net = 0;
      long cost = 0;
      int excludedLines = 0;
      for (LineFact line : lines) {
        gross += line.grossMinor();
        deductions += line.deductionsMinor();
        contributions += line.contributionsMinor();
        net += line.netMinor();
        cost += line.costMinor();
        if (line.excluded()) {
          excludedLines++;
        }
      }
      return new Facts(
          runCurrent,
          runCurrency,
          calculationCurrent,
          calculationCurrency,
          packFound,
          packVerified,
          synthetic,
          policyMatches,
          profilesMatch,
          rulePackMatches,
          sourceMatches,
          runLineCount == null ? lines.size() : runLineCount,
          employeesMatch,
          supported == null ? lines.size() - excludedLines : supported,
          this.excludedCount == null ? excludedLines : this.excludedCount,
          declared == null ? new Totals(gross, deductions, contributions, net, cost) : declared,
          samePreparer,
          otherApproved,
          exactApproved,
          voidedPair,
          chainCount);
    }

    Result evaluate() {
      return PayrollApprovalEvaluator.evaluate(facts(), lines);
    }
  }

  private static List<LineFact> threeLines() {
    return List.of(line(1, 162000, 12960, 21060), line(2, 12345, 617, 1604), excluded(3, 50000));
  }

  private static Scenario ready() {
    return new Scenario(threeLines());
  }

  private static EnumSet<PayrollApprovalBlocker> blockers(PayrollApprovalBlocker... blockers) {
    EnumSet<PayrollApprovalBlocker> set = EnumSet.noneOf(PayrollApprovalBlocker.class);
    set.addAll(List.of(blockers));
    return set;
  }

  @Test
  void aCleanPackageIsReadyWithExactTotalsAndOnlyTheExcludedWarning() {
    Result result = ready().evaluate();
    assertThat(result.status()).isEqualTo(Status.READY);
    assertThat(result.blockers()).isEmpty();
    assertThat(result.warnings()).containsExactly(PayrollApprovalWarning.EXCLUDED_LINES_PRESENT);
    // gross 162000 + 12345 + 50000; deductions 12960 + 617; contributions 21060 + 1604.
    assertThat(result.totals()).isEqualTo(new Totals(224345, 13577, 22664, 210768, 247009));
    assertThat(result.payslipCount()).isEqualTo(3);
    assertThat(result.excludedCount()).isEqualTo(1);
  }

  @Test
  void theNetIsGrossLessEmployeeDeductionsAndEmployerCostIsGrossPlusContributions() {
    Totals totals = ready().evaluate().totals();
    assertThat(totals.netMinor()).isEqualTo(totals.grossMinor() - totals.deductionsMinor());
    assertThat(totals.costMinor()).isEqualTo(totals.grossMinor() + totals.contributionsMinor());
  }

  @Test
  void aLineThatSubtractsEmployerContributionsFromTheNetDoesNotAddUp() {
    // net = gross - deductions - contributions would be 140940; the proof refuses it.
    LineFact wrong =
        new LineFact(
            id(1),
            "E-1",
            false,
            162000,
            12960,
            21060,
            162000 - 12960 - 21060,
            183060,
            List.of(
                new ComponentFact("EMPLOYEE", "SOCIAL_CONTRIBUTION", 12960),
                new ComponentFact("EMPLOYER", "SOCIAL_CONTRIBUTION", 21060)));
    assertThat(PayrollApprovalEvaluator.lineAddsUp(wrong)).isFalse();
    assertThat(PayrollApprovalEvaluator.lineAddsUp(line(1, 162000, 12960, 21060))).isTrue();
    Result result = new Scenario(List.of(wrong, line(2, 1000, 0, 0))).evaluate();
    assertThat(result.blockers()).containsExactly(PayrollApprovalBlocker.ARITHMETIC_MISMATCH);
  }

  @Test
  void eachSingleFactRaisesExactlyItsBlocker() {
    assertThat(ready().with(s -> { s.runCurrent = false; return s; }).evaluate().blockers())
        .isEqualTo(blockers(PayrollApprovalBlocker.SOURCE_RUN_NOT_CURRENT));
    assertThat(ready().with(s -> { s.calculationCurrent = false; return s; }).evaluate().blockers())
        .isEqualTo(blockers(PayrollApprovalBlocker.CALCULATION_NOT_CURRENT));
    assertThat(ready().with(s -> { s.packVerified = false; return s; }).evaluate().blockers())
        .isEqualTo(blockers(PayrollApprovalBlocker.RULE_PACK_NOT_VERIFIED));
    assertThat(ready().with(s -> { s.policyMatches = false; return s; }).evaluate().blockers())
        .isEqualTo(blockers(PayrollApprovalBlocker.POLICY_CHANGED));
    assertThat(ready().with(s -> { s.profilesMatch = false; return s; }).evaluate().blockers())
        .isEqualTo(blockers(PayrollApprovalBlocker.PROFILE_CHANGED));
    assertThat(ready().with(s -> { s.rulePackMatches = false; return s; }).evaluate().blockers())
        .isEqualTo(blockers(PayrollApprovalBlocker.RULE_PACK_CHANGED));
    assertThat(ready().with(s -> { s.sourceMatches = false; return s; }).evaluate().blockers())
        .isEqualTo(blockers(PayrollApprovalBlocker.SOURCE_CHANGED));
    assertThat(ready().with(s -> { s.voidedPair = true; return s; }).evaluate().blockers())
        .isEqualTo(blockers(PayrollApprovalBlocker.SOURCE_ALREADY_VOIDED));
    assertThat(ready().with(s -> { s.otherApproved = true; return s; }).evaluate().blockers())
        .isEqualTo(blockers(PayrollApprovalBlocker.ALREADY_APPROVED));
    assertThat(ready().with(s -> { s.chainCount = 25; return s; }).evaluate().blockers())
        .isEqualTo(blockers(PayrollApprovalBlocker.LIMIT_REACHED));
  }

  @Test
  void aCurrencyOtherThanCdfBlocksOnTheRunOrOnTheCalculation() {
    for (String currency : List.of("USD", "EUR", "cdf", "")) {
      Result run = ready().with(s -> { s.runCurrency = currency; return s; }).evaluate();
      assertThat(run.blockers()).isEqualTo(blockers(PayrollApprovalBlocker.CURRENCY_NOT_SUPPORTED));
      Result calculation =
          ready().with(s -> { s.calculationCurrency = currency; return s; }).evaluate();
      assertThat(calculation.blockers())
          .isEqualTo(blockers(PayrollApprovalBlocker.CURRENCY_NOT_SUPPORTED));
    }
    assertThat(ready().evaluate().status()).isEqualTo(Status.READY);
  }

  @Test
  void aMissingRulePackIsAMissingSourceNotAnUnverifiedPack() {
    Result missing =
        ready().with(s -> { s.packFound = false; s.packVerified = false; return s; }).evaluate();
    assertThat(missing.blockers()).isEqualTo(blockers(PayrollApprovalBlocker.SOURCE_MISSING));
    Result found =
        ready().with(s -> { s.packFound = true; s.packVerified = false; return s; }).evaluate();
    assertThat(found.blockers()).isEqualTo(blockers(PayrollApprovalBlocker.RULE_PACK_NOT_VERIFIED));
  }

  @Test
  void aNonVerifiedPackBlocksEvenWhenEverythingElseIsSound() {
    Result result =
        ready().with(s -> { s.packVerified = false; s.synthetic = true; return s; }).evaluate();
    assertThat(result.status()).isEqualTo(Status.BLOCKED);
    assertThat(result.blockers()).contains(PayrollApprovalBlocker.RULE_PACK_NOT_VERIFIED);
  }

  @Test
  void aRunWithoutLinesIsAMissingSource() {
    Result none = new Scenario(List.of()).evaluate();
    assertThat(none.status()).isEqualTo(Status.BLOCKED);
    assertThat(none.blockers()).isEqualTo(blockers(PayrollApprovalBlocker.SOURCE_MISSING));
    assertThat(none.payslipCount()).isEqualTo(0);
    // The run has lines but the calculation brought none.
    Scenario lost = new Scenario(List.of());
    lost.runLineCount = 3;
    assertThat(lost.evaluate().blockers())
        .containsExactly(
            PayrollApprovalBlocker.CALCULATION_INCOMPLETE, PayrollApprovalBlocker.SOURCE_MISSING);
    // The calculation has lines but the run has none.
    Scenario empty = ready();
    empty.runLineCount = 0;
    assertThat(empty.evaluate().blockers())
        .containsExactly(
            PayrollApprovalBlocker.CALCULATION_INCOMPLETE, PayrollApprovalBlocker.SOURCE_MISSING);
  }

  @Test
  void countsThatDoNotMatchTheSourcesAreAnIncompleteCalculation() {
    for (int variant = 0; variant < 5; variant++) {
      Scenario scenario = ready();
      switch (variant) {
        case 0 -> scenario.runLineCount = 4;
        case 1 -> scenario.employeesMatch = false;
        case 2 -> scenario.supported = 1;
        case 3 -> scenario.excludedCount = 2;
        default -> scenario.supported = 3;
      }
      assertThat(scenario.evaluate().blockers())
          .as("variant " + variant)
          .isEqualTo(blockers(PayrollApprovalBlocker.CALCULATION_INCOMPLETE));
    }
  }

  @Test
  void aPackageWithoutAnyPayableLineIsBlockedAndZeroNetLinesWarn() {
    LineFact zero = new LineFact(id(1), "E-1", false, 0, 0, 0, 0, 0, List.of());
    Result result = new Scenario(List.of(zero)).evaluate();
    assertThat(result.status()).isEqualTo(Status.BLOCKED);
    assertThat(result.blockers()).isEqualTo(blockers(PayrollApprovalBlocker.NO_PAYABLE_LINES));
    assertThat(result.warnings()).containsExactly(PayrollApprovalWarning.ZERO_NET_LINES_PRESENT);

    // Deductions equal to the gross also leave a net of zero.
    LineFact consumed = line(2, 1000, 1000, 0);
    assertThat(consumed.netMinor()).isEqualTo(0L);
    Result mixed = new Scenario(List.of(consumed, line(3, 5000, 100, 0))).evaluate();
    assertThat(mixed.status()).isEqualTo(Status.READY);
    assertThat(mixed.warnings()).containsExactly(PayrollApprovalWarning.ZERO_NET_LINES_PRESENT);
    Result all = new Scenario(List.of(consumed)).evaluate();
    assertThat(all.blockers()).containsExactly(PayrollApprovalBlocker.NO_PAYABLE_LINES);
  }

  @Test
  void aLineOrATotalOrACountThatDoesNotAddUpIsAnArithmeticMismatch() {
    LineFact badNet =
        new LineFact(
            id(1),
            "E-1",
            false,
            1000,
            100,
            0,
            901,
            1000,
            List.of(new ComponentFact("EMPLOYEE", "INCOME_TAX", 100)));
    assertThat(new Scenario(List.of(badNet)).evaluate().blockers())
        .containsExactly(PayrollApprovalBlocker.ARITHMETIC_MISMATCH);
    LineFact badCost =
        new LineFact(
            id(1),
            "E-1",
            false,
            1000,
            0,
            50,
            1000,
            1049,
            List.of(new ComponentFact("EMPLOYER", "SOCIAL_CONTRIBUTION", 50)));
    assertThat(new Scenario(List.of(badCost)).evaluate().blockers())
        .containsExactly(PayrollApprovalBlocker.ARITHMETIC_MISMATCH);
    Scenario declared = ready();
    declared.declared = new Totals(224345, 13577, 22664, 210768, 247010);
    assertThat(declared.evaluate().blockers())
        .containsExactly(PayrollApprovalBlocker.ARITHMETIC_MISMATCH);
    for (int field = 0; field < 5; field++) {
      long[] t = {224345, 13577, 22664, 210768, 247009};
      t[field] += 1;
      Scenario off = ready();
      off.declared = new Totals(t[0], t[1], t[2], t[3], t[4]);
      assertThat(off.evaluate().blockers())
          .as("declared total " + field)
          .containsExactly(PayrollApprovalBlocker.ARITHMETIC_MISMATCH);
    }
  }

  @Test
  void aDuplicateEmployeeIsAnArithmeticMismatch() {
    Result result =
        new Scenario(List.of(line(1, 1000, 0, 0), line(1, 2000, 0, 0))).evaluate();
    assertThat(result.blockers()).containsExactly(PayrollApprovalBlocker.ARITHMETIC_MISMATCH);
  }

  @Test
  void anExcludedLineMustCarryNothingAndAnEmployerComponentIsNotAnEmployeeDeduction() {
    assertThat(PayrollApprovalEvaluator.lineAddsUp(excluded(1, 50000))).isTrue();
    LineFact withDeduction =
        new LineFact(
            id(1),
            "E-1",
            true,
            1000,
            10,
            0,
            990,
            1000,
            List.of(new ComponentFact("EMPLOYEE", "SOCIAL_CONTRIBUTION", 10)));
    assertThat(PayrollApprovalEvaluator.lineAddsUp(withDeduction)).isFalse();
    // The employer component is counted as a deduction: refused.
    LineFact employerAsDeduction =
        new LineFact(
            id(1),
            "E-1",
            false,
            1000,
            75,
            0,
            925,
            1000,
            List.of(new ComponentFact("EMPLOYER", "SOCIAL_CONTRIBUTION", 75)));
    assertThat(PayrollApprovalEvaluator.lineAddsUp(employerAsDeduction)).isFalse();
    LineFact negative =
        new LineFact(
            id(1),
            "E-1",
            false,
            1000,
            0,
            0,
            1000,
            1000,
            List.of(
                new ComponentFact("EMPLOYEE", "INCOME_TAX", -5),
                new ComponentFact("EMPLOYEE", "INCOME_TAX", 5)));
    assertThat(PayrollApprovalEvaluator.lineAddsUp(negative)).isFalse();
    LineFact unknownParty =
        new LineFact(
            id(1),
            "E-1",
            false,
            1000,
            10,
            0,
            990,
            1000,
            List.of(new ComponentFact("STATE", "INCOME_TAX", 10)));
    assertThat(PayrollApprovalEvaluator.lineAddsUp(unknownParty)).isFalse();
    LineFact deductionsAboveGross =
        new LineFact(
            id(1),
            "E-1",
            false,
            100,
            200,
            0,
            -100,
            100,
            List.of(new ComponentFact("EMPLOYEE", "INCOME_TAX", 200)));
    assertThat(PayrollApprovalEvaluator.lineAddsUp(deductionsAboveGross)).isFalse();
  }

  @Test
  void exactTotalsHoldForVeryLargeValuesBelowTheLongLimit() {
    long third = 3_000_000_000_000_000_000L;
    List<LineFact> lines =
        List.of(
            new LineFact(id(1), "E-1", true, third, 0, 0, third, third, List.of()),
            new LineFact(id(2), "E-2", true, third, 0, 0, third, third, List.of()),
            new LineFact(id(3), "E-3", true, third, 0, 0, third, third, List.of()));
    Result result = new Scenario(lines).evaluate();
    assertThat(result.status()).isEqualTo(Status.READY);
    assertThat(result.totals().grossMinor()).isEqualTo(9_000_000_000_000_000_000L);
    assertThat(result.totals().netMinor()).isEqualTo(9_000_000_000_000_000_000L);
    assertThat(result.totals().costMinor()).isEqualTo(9_000_000_000_000_000_000L);
    LineFact maximum =
        new LineFact(
            id(1), "E-1", true, Long.MAX_VALUE, 0, 0, Long.MAX_VALUE, Long.MAX_VALUE, List.of());
    Result single = new Scenario(List.of(maximum)).evaluate();
    assertThat(single.status()).isEqualTo(Status.READY);
    assertThat(single.totals().grossMinor()).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  void aTotalThatOverflowsALongIsAnArithmeticMismatchWithNoTrustedTotals() {
    List<LineFact> lines =
        List.of(
            new LineFact(id(1), "E-1", true, Long.MAX_VALUE, 0, 0, Long.MAX_VALUE, Long.MAX_VALUE,
                List.of()),
            new LineFact(id(2), "E-2", true, 1, 0, 0, 1, 1, List.of()));
    Scenario scenario = new Scenario(lines);
    // The declared totals are the wrapped sums a naive implementation would compute.
    scenario.declared = new Totals(Long.MIN_VALUE, 0, 0, Long.MIN_VALUE, Long.MIN_VALUE);
    Result result = scenario.evaluate();
    assertThat(result.status()).isEqualTo(Status.BLOCKED);
    assertThat(result.blockers()).containsExactly(PayrollApprovalBlocker.ARITHMETIC_MISMATCH);
    assertThat(result.totals()).isNull();
    // gross + contributions of one line overflows: the line cannot add up.
    LineFact cost =
        new LineFact(
            id(3),
            "E-3",
            false,
            Long.MAX_VALUE - 1,
            0,
            5,
            Long.MAX_VALUE - 1,
            Long.MAX_VALUE,
            List.of(new ComponentFact("EMPLOYER", "SOCIAL_CONTRIBUTION", 5)));
    assertThat(PayrollApprovalEvaluator.lineAddsUp(cost)).isFalse();
  }

  @Test
  void fiveThousandPayslipsAreAllowedAndThe5001stIsTheLimit() {
    List<LineFact> max = new ArrayList<>();
    for (int n = 1; n <= PayrollApprovalEvaluator.MAX_PAYSLIPS; n++) {
      max.add(line(n, 1000, 10, 0));
    }
    Result ok = new Scenario(max).evaluate();
    assertThat(ok.status()).isEqualTo(Status.READY);
    assertThat(ok.payslipCount()).isEqualTo(5000);
    assertThat(ok.totals()).isEqualTo(new Totals(5_000_000, 50_000, 0, 4_950_000, 5_000_000));

    List<LineFact> over = new ArrayList<>(max);
    over.add(line(5001, 1000, 10, 0));
    Result limit = new Scenario(over).evaluate();
    assertThat(limit.status()).isEqualTo(Status.BLOCKED);
    assertThat(limit.blockers()).containsExactly(PayrollApprovalBlocker.LIMIT_REACHED);
    assertThat(limit.totals()).isNull();
    assertThat(limit.payslipCount()).isEqualTo(5001);
    assertThat(PayrollApprovalEvaluator.MAX_PAYSLIPS).isEqualTo(5000);
  }

  @Test
  void theTwentySixthPackageOfAChainIsTheLimitUnlessItIsTheExactApprovedOne() {
    assertThat(ready().with(s -> { s.chainCount = 24; return s; }).evaluate().status())
        .isEqualTo(Status.READY);
    assertThat(ready().with(s -> { s.chainCount = 25; return s; }).evaluate().blockers())
        .containsExactly(PayrollApprovalBlocker.LIMIT_REACHED);
    Result exact =
        ready().with(s -> { s.chainCount = 25; s.exactApproved = true; return s; }).evaluate();
    assertThat(exact.status()).isEqualTo(Status.NO_CHANGE);
    assertThat(PayrollApprovalEvaluator.MAX_VERSIONS).isEqualTo(25);
  }

  @Test
  void anIdenticalApprovedPackageIsNoChangeNotAnotherApproval() {
    Result same = ready().with(s -> { s.exactApproved = true; return s; }).evaluate();
    assertThat(same.status()).isEqualTo(Status.NO_CHANGE);
    assertThat(same.blockers()).isEmpty();
    // Another package from other sources does not hide the exact one.
    Result both =
        ready().with(s -> { s.exactApproved = true; s.otherApproved = true; return s; }).evaluate();
    assertThat(both.status()).isEqualTo(Status.NO_CHANGE);
    assertThat(both.blockers()).isEmpty();
  }

  @Test
  void anyBlockerOutranksNoChange() {
    Result result =
        ready().with(s -> { s.exactApproved = true; s.runCurrent = false; return s; }).evaluate();
    assertThat(result.status()).isEqualTo(Status.BLOCKED);
    assertThat(result.blockers()).containsExactly(PayrollApprovalBlocker.SOURCE_RUN_NOT_CURRENT);
  }

  @Test
  void blockersAreReturnedInContractOrderWhateverTheOrderTheyWereRaised() {
    Scenario scenario = ready();
    scenario.limitAll();
    Result result = scenario.evaluate();
    List<PayrollApprovalBlocker> sorted = new ArrayList<>(result.blockers());
    List<PayrollApprovalBlocker> contract = new ArrayList<>();
    contract.addAll(List.of(PayrollApprovalBlocker.values()));
    contract.retainAll(sorted);
    assertThat(sorted).isEqualTo(contract);
    assertThat(sorted).contains(
        PayrollApprovalBlocker.SOURCE_RUN_NOT_CURRENT,
        PayrollApprovalBlocker.CALCULATION_NOT_CURRENT,
        PayrollApprovalBlocker.POLICY_CHANGED,
        PayrollApprovalBlocker.PROFILE_CHANGED,
        PayrollApprovalBlocker.RULE_PACK_CHANGED,
        PayrollApprovalBlocker.SOURCE_CHANGED,
        PayrollApprovalBlocker.CURRENCY_NOT_SUPPORTED,
        PayrollApprovalBlocker.RULE_PACK_NOT_VERIFIED,
        PayrollApprovalBlocker.ALREADY_APPROVED,
        PayrollApprovalBlocker.SOURCE_ALREADY_VOIDED,
        PayrollApprovalBlocker.LIMIT_REACHED);
  }

  @Test
  void warningsNeverBlock() {
    Result result =
        ready().with(s -> { s.samePreparer = true; s.synthetic = true; return s; }).evaluate();
    assertThat(result.status()).isEqualTo(Status.READY);
    assertThat(result.blockers()).isEmpty();
    assertThat(result.warnings())
        .containsExactly(
            PayrollApprovalWarning.SAME_PREPARER_AND_APPROVER,
            PayrollApprovalWarning.SYNTHETIC_RULE_PACK,
            PayrollApprovalWarning.EXCLUDED_LINES_PRESENT);
  }

  @Test
  void theSamePreparerWarningIsRaisedOnlyWhenTheApproverAlsoPreparedASource() {
    assertThat(ready().evaluate().warnings())
        .doesNotContain(PayrollApprovalWarning.SAME_PREPARER_AND_APPROVER);
    assertThat(ready().with(s -> { s.samePreparer = true; return s; }).evaluate().warnings())
        .contains(PayrollApprovalWarning.SAME_PREPARER_AND_APPROVER);
  }

  @Test
  void theSyntheticWarningFollowsThePackNotTheAmounts() {
    assertThat(ready().evaluate().warnings())
        .doesNotContain(PayrollApprovalWarning.SYNTHETIC_RULE_PACK);
    assertThat(ready().with(s -> { s.synthetic = true; return s; }).evaluate().warnings())
        .contains(PayrollApprovalWarning.SYNTHETIC_RULE_PACK);
  }

  @Test
  void onlySourceAndPolicyDriftIsAnsweredAsAStalePreview() {
    List<PayrollApprovalBlocker> all = List.of(PayrollApprovalBlocker.values());
    assertThat(PayrollApprovalEvaluator.drift(EnumSet.copyOf(all)))
        .containsExactly(
            PayrollApprovalBlocker.SOURCE_RUN_NOT_CURRENT,
            PayrollApprovalBlocker.CALCULATION_NOT_CURRENT,
            PayrollApprovalBlocker.POLICY_CHANGED,
            PayrollApprovalBlocker.PROFILE_CHANGED,
            PayrollApprovalBlocker.RULE_PACK_CHANGED,
            PayrollApprovalBlocker.SOURCE_CHANGED);
    assertThat(PayrollApprovalEvaluator.drift(EnumSet.noneOf(PayrollApprovalBlocker.class)))
        .isEmpty();
    for (PayrollApprovalBlocker blocker : all) {
      boolean drift = PayrollApprovalEvaluator.drift(EnumSet.of(blocker)).contains(blocker);
      assertThat(drift).as(blocker.name()).isEqualTo(blocker.drift());
    }
    assertThat(PayrollApprovalBlocker.RULE_PACK_NOT_VERIFIED.drift()).isFalse();
    assertThat(PayrollApprovalBlocker.ARITHMETIC_MISMATCH.drift()).isFalse();
    assertThat(PayrollApprovalBlocker.ALREADY_APPROVED.drift()).isFalse();
    assertThat(all).hasSize(15);
  }

  @Test
  void theResultSetsAreUnmodifiableAndPrintNothingFinancial() {
    Result result = ready().evaluate();
    assertThatThrownBy(() -> result.blockers().add(PayrollApprovalBlocker.POLICY_CHANGED))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> result.warnings().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(result.toString()).doesNotContain("224345").doesNotContain("210768");
    assertThat(result.totals().toString()).doesNotContain("224345");
    assertThat(threeLines().get(0).toString()).doesNotContain("162000").doesNotContain("E-1");
  }
}
