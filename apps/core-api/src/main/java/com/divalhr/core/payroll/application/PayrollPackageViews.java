package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.PayrollPackageResponses.ApprovalLine;
import com.divalhr.core.payroll.api.PayrollPackageResponses.Evidence;
import com.divalhr.core.payroll.api.PayrollPackageResponses.PackageSummary;
import com.divalhr.core.payroll.api.PayrollPackageResponses.RulePack;
import com.divalhr.core.payroll.api.PayrollPackageResponses.SlipSummary;
import com.divalhr.core.payroll.api.PayrollPackageResponses.SlipView;
import com.divalhr.core.payroll.api.PayrollPackageResponses.Snapshot;
import com.divalhr.core.payroll.api.PayrollPackageResponses.SnapshotComponent;
import com.divalhr.core.payroll.api.PayrollPackageResponses.SnapshotRulePack;
import com.divalhr.core.payroll.api.PayrollPackageResponses.Totals;
import com.divalhr.core.payroll.api.PayrollPackageResponses.Wording;
import com.divalhr.core.payroll.domain.PayrollApprovalEvaluator;
import com.divalhr.core.payroll.domain.PayrollVoidReason;
import com.divalhr.core.payroll.domain.PayslipLocale;
import com.divalhr.core.payroll.domain.PayslipSnapshot;
import com.divalhr.core.payroll.domain.PayslipText;
import com.divalhr.core.payroll.internal.JdbcPayrollPackageRepository.PackageRow;
import com.divalhr.core.payroll.internal.JdbcPayrollPackageRepository.SlipRow;
import com.divalhr.core.payroll.internal.JdbcStatutoryRepository.LineRow;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Maps payroll package rows and snapshots to the wire views (MVP-094C). The approver subject is
 * never selected and the void explanation is only ever placed in {@link #evidence} by the one read
 * that is allowed to return it. Restricted financial and HR data: nothing here is logged.
 */
final class PayrollPackageViews {

  /** The currency of every package. */
  static final String CURRENCY = "CDF";

  private PayrollPackageViews() {}

  /** A bounded metric and log label of a line count: 1, 2-10, 11-100, 101-1000, 1001-5000. */
  static String countBucket(int count) {
    if (count <= 1) {
      return "1";
    }
    if (count <= 10) {
      return "2-10";
    }
    if (count <= 100) {
      return "11-100";
    }
    return count <= 1000 ? "101-1000" : "1001-5000";
  }

  static Totals totals(PayrollApprovalEvaluator.Totals totals) {
    return totals(
        totals.grossMinor(),
        totals.deductionsMinor(),
        totals.contributionsMinor(),
        totals.netMinor(),
        totals.costMinor());
  }

  static Totals totals(long gross, long deductions, long contributions, long net, long cost) {
    return new Totals(
        StatutoryViews.cdf(gross),
        StatutoryViews.cdf(deductions),
        StatutoryViews.cdf(contributions),
        StatutoryViews.cdf(net),
        StatutoryViews.cdf(cost));
  }

  static RulePack rulePack(UUID packId, String code, int version) {
    boolean synthetic = code.startsWith(PayrollApprovalEvidence.SYNTHETIC_PREFIX);
    return new RulePack(packId, code, version, synthetic);
  }

  static PackageSummary summary(PackageRow row) {
    return new PackageSummary(
        row.id(),
        row.legalEntityId(),
        StatutoryViews.month(row.periodStart()),
        row.currency(),
        row.version(),
        row.status(),
        row.synthetic(),
        row.runId(),
        row.runVersion(),
        row.calculationId(),
        row.calculationVersion(),
        rulePack(row.packId(), row.packCode(), row.packVersion()),
        row.payslipCount(),
        totals(
            row.grossTotal(),
            row.deductionsTotal(),
            row.contributionsTotal(),
            row.netTotal(),
            row.costTotal()),
        row.samePreparer(),
        row.approvedAt(),
        row.voidedAt(),
        row.voidReasonCode());
  }

  static Evidence evidence(PackageRow row) {
    return new Evidence(
        row.approvalDigest(),
        row.calculationSourceRunDigest(),
        row.runDigest(),
        row.calculationDigest(),
        row.calculationProfileDigest(),
        row.calculationRulePackDigest(),
        row.rulesDigest(),
        row.policySequence(),
        row.reviewedTotals(),
        row.reviewedExceptions(),
        row.understandsNoPayment());
  }

  static ApprovalLine line(LineRow line) {
    return new ApprovalLine(
        line.employeeId(),
        line.employeeNumber(),
        line.excluded(),
        StatutoryViews.cdf(line.grossMinor()),
        StatutoryViews.cdf(line.deductionsMinor()),
        StatutoryViews.cdf(line.contributionsMinor()),
        StatutoryViews.cdf(line.netMinor()),
        StatutoryViews.cdf(line.costMinor()));
  }

  static SlipSummary slipSummary(SlipRow row) {
    return new SlipSummary(
        row.id(),
        row.packageId(),
        row.legalEntityId(),
        StatutoryViews.month(row.periodStart()),
        CURRENCY,
        row.employeeId(),
        row.employeeNumber(),
        row.packageStatus(),
        row.synthetic(),
        row.voidReasonCode(),
        row.approvedAt(),
        row.voidedAt(),
        StatutoryViews.cdf(row.grossMinor()),
        StatutoryViews.cdf(row.deductionsMinor()),
        StatutoryViews.cdf(row.contributionsMinor()),
        StatutoryViews.cdf(row.netMinor()));
  }

  /**
   * The payslip view: the canonical snapshot (identical in both locales) and the wording.
   *
   * @param row the stored payslip with its package status
   * @param snapshot the snapshot parsed from the stored canonical text
   * @param locale the wording locale
   * @return the view
   */
  static SlipView slipView(SlipRow row, PayslipSnapshot snapshot, PayslipLocale locale) {
    boolean voided = "VOIDED".equals(row.packageStatus());
    PayrollVoidReason reason = PayrollVoidReason.parse(row.voidReasonCode()).orElse(null);
    List<SnapshotComponent> components = new ArrayList<>();
    for (PayslipSnapshot.Component component : snapshot.components()) {
      components.add(
          new SnapshotComponent(
              component.seq(),
              component.id(),
              component.party(),
              component.category(),
              StatutoryViews.cdf(component.baseMinor()),
              StatutoryViews.cdf(component.amountMinor()),
              component.explanationCodes()));
    }
    Snapshot view =
        new Snapshot(
            PayslipSnapshot.SCHEMA_VERSION,
            snapshot.packageId(),
            snapshot.legalEntityId(),
            snapshot.period(),
            PayslipSnapshot.CURRENCY,
            snapshot.employeeId(),
            snapshot.employeeNumber(),
            PayslipSnapshot.STATUS,
            snapshot.approvedAt(),
            new SnapshotRulePack(snapshot.rulePackCode(), snapshot.rulePackVersion()),
            snapshot.excluded(),
            StatutoryViews.cdf(snapshot.grossMinor()),
            StatutoryViews.cdf(snapshot.employeeCnssMinor()),
            StatutoryViews.cdf(snapshot.irppWithholdingMinor()),
            StatutoryViews.cdf(snapshot.employeeDeductionsMinor()),
            StatutoryViews.cdf(snapshot.netMinor()),
            StatutoryViews.cdf(snapshot.employerContributionsMinor()),
            snapshot.explanationCodes(),
            components);
    Wording wording =
        new Wording(
            locale.code(),
            PayslipText.title(locale),
            PayslipText.statusLabel(locale, voided),
            PayslipText.notProofOfPayment(locale),
            PayslipText.employerContributionsNote(locale),
            PayslipText.rulePackStatement(
                locale, snapshot.rulePackCode(), snapshot.rulePackVersion()),
            row.synthetic() ? PayslipText.syntheticNotice(locale) : null,
            voided ? PayslipText.voidedNotice(locale, reason) : null);
    return new SlipView(
        row.id(),
        row.packageStatus(),
        row.synthetic(),
        row.voidReasonCode(),
        row.voidedAt(),
        view,
        row.snapshotDigest(),
        wording);
  }
}
