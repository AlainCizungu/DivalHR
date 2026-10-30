package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.api.CompensationResponses.MoneyView;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Responses of payroll approval and bilingual payslips (MVP-094C, ADR 0035). Money is the
 * canonical decimal string next to its currency code (CDF whole francs). Approved payroll is not a
 * payment, a filing or a claim of legal compliance, and a payslip is not proof of payment.
 * Restricted financial and HR data: no record here prints an amount, an employee number, a
 * snapshot or a digest, and the receipts carry none (they are what the idempotency store keeps).
 * The approver subject and the void explanation are never part of a package summary or a receipt.
 */
public final class PayrollPackageResponses {

  private PayrollPackageResponses() {}

  /**
   * The rule pack a package was approved on.
   *
   * @param packId rule pack
   * @param code its code
   * @param version its version
   * @param synthetic whether the code starts with {@code SYNTHETIC-TEST} (test data only)
   */
  @Schema(name = "PayrollPackageRulePack")
  public record RulePack(UUID packId, String code, int version, boolean synthetic) {}

  /**
   * Sums over the payslips.
   *
   * @param contractualGross total contractual gross
   * @param employeeDeductions total employee deductions
   * @param employerContributions total employer contributions
   * @param approvedNet total approved net
   * @param employerCost total employer cost
   */
  @Schema(name = "PayrollPackageTotals")
  public record Totals(
      MoneyView contractualGross,
      MoneyView employeeDeductions,
      MoneyView employerContributions,
      MoneyView approvedNet,
      MoneyView employerCost) {

    @Override
    public String toString() {
      return "PayrollPackageTotals";
    }
  }

  /**
   * One employee line of the approval review.
   *
   * @param employeeId employee
   * @param employeeNumber employee number
   * @param excluded whether the employee is subject to neither CNSS nor IRPP
   * @param contractualGross gross
   * @param employeeDeductions deductions
   * @param employerContributions employer contributions
   * @param approvedNet net
   * @param employerCost employer cost
   */
  @Schema(name = "PayrollApprovalLine")
  public record ApprovalLine(
      UUID employeeId,
      String employeeNumber,
      boolean excluded,
      MoneyView contractualGross,
      MoneyView employeeDeductions,
      MoneyView employerContributions,
      MoneyView approvedNet,
      MoneyView employerCost) {

    @Override
    public String toString() {
      return "PayrollApprovalLine";
    }
  }

  /**
   * A payroll package (never the approver subject or the void explanation).
   *
   * @param packageId package
   * @param legalEntityId legal entity
   * @param period {@code YYYY-MM}
   * @param currency {@code CDF}
   * @param version version in the chain
   * @param status {@code APPROVED} or {@code VOIDED}
   * @param synthetic whether the rule pack is a test pack
   * @param runId frozen run
   * @param runVersion its version
   * @param calculationId statutory calculation
   * @param calculationVersion its version
   * @param pack the rule pack
   * @param payslipCount payslips
   * @param totals the sums
   * @param samePreparer whether the approver also created the run or the calculation
   * @param approvedAt approval instant
   * @param voidedAt void instant, or {@code null}
   * @param voidReasonCode closed void reason, or {@code null}
   */
  @Schema(name = "PayrollPackageSummary")
  public record PackageSummary(
      UUID packageId,
      UUID legalEntityId,
      String period,
      String currency,
      int version,
      String status,
      boolean synthetic,
      UUID runId,
      int runVersion,
      UUID calculationId,
      int calculationVersion,
      RulePack pack,
      int payslipCount,
      Totals totals,
      boolean samePreparer,
      Instant approvedAt,
      Instant voidedAt,
      String voidReasonCode) {

    @Override
    public String toString() {
      return "PayrollPackageSummary";
    }
  }

  /**
   * A page of packages.
   *
   * @param items the packages
   * @param nextCursor cursor of the next page, or {@code null}
   */
  @Schema(name = "PayrollPackagePage")
  public record PackagePage(List<PackageSummary> items, String nextCursor) {

    /** Copies the list. */
    public PackagePage {
      items = List.copyOf(items);
    }
  }

  /**
   * What approving would publish.
   *
   * @param legalEntityId legal entity
   * @param runId frozen run
   * @param runVersion its version
   * @param calculationId statutory calculation
   * @param calculationVersion its version
   * @param period {@code YYYY-MM}
   * @param currency run currency
   * @param status {@code READY}, {@code BLOCKED} or {@code NO_CHANGE}
   * @param pack the rule pack the calculation used, or {@code null}
   * @param policySequence sequence of the policy frozen in the run
   * @param blockers closed blocker codes
   * @param warnings closed warning codes
   * @param samePreparer whether the caller also prepared a source
   * @param expectedVersion the current chain version (0 when none)
   * @param existingPackage the APPROVED package of the chain, or {@code null}
   * @param payslipCount payslips
   * @param excludedCount excluded lines
   * @param totals the sums, or {@code null} when they cannot be trusted
   * @param lines one line per employee
   * @param sourceRunDigest live source-run digest
   * @param calculationDigest stored calculation digest
   * @param profileDigest live profile digest
   * @param rulePackDigest live rule-pack digest, or {@code null}
   * @param previewDigest the preview digest, or {@code null} unless READY
   * @param expiresAt expiry, or {@code null} unless READY
   */
  @Schema(name = "PayrollApprovalPreview")
  public record ApprovalPreview(
      UUID legalEntityId,
      UUID runId,
      int runVersion,
      UUID calculationId,
      int calculationVersion,
      String period,
      String currency,
      String status,
      RulePack pack,
      int policySequence,
      List<String> blockers,
      List<String> warnings,
      boolean samePreparer,
      int expectedVersion,
      PackageSummary existingPackage,
      int payslipCount,
      int excludedCount,
      Totals totals,
      List<ApprovalLine> lines,
      String sourceRunDigest,
      String calculationDigest,
      String profileDigest,
      String rulePackDigest,
      String previewDigest,
      Instant expiresAt) {

    /** Copies the lists. */
    public ApprovalPreview {
      blockers = List.copyOf(blockers);
      warnings = List.copyOf(warnings);
      lines = List.copyOf(lines);
    }

    @Override
    public String toString() {
      return "PayrollApprovalPreview[" + status + "]";
    }
  }

  /**
   * The amount-free receipt of an approval (what the idempotency store keeps).
   *
   * @param packageId created package
   * @param legalEntityId legal entity
   * @param runId frozen run
   * @param runVersion its version
   * @param calculationId statutory calculation
   * @param calculationVersion its version
   * @param packId rule pack
   * @param packVersion its version
   * @param period {@code YYYY-MM}
   * @param currency {@code CDF}
   * @param version version in the chain
   * @param status always {@code APPROVED}
   * @param synthetic whether the rule pack is a test pack
   * @param payslipCount payslips
   * @param samePreparer whether the approver also prepared a source
   * @param approvedAt approval instant
   */
  @Schema(name = "PayrollApprovalReceipt")
  public record ApprovalReceipt(
      UUID packageId,
      UUID legalEntityId,
      UUID runId,
      int runVersion,
      UUID calculationId,
      int calculationVersion,
      UUID packId,
      int packVersion,
      String period,
      String currency,
      int version,
      String status,
      boolean synthetic,
      int payslipCount,
      boolean samePreparer,
      Instant approvedAt) {}

  /**
   * The restricted source and digest evidence of a package.
   *
   * @param approvalDigest the approval digest
   * @param sourceRunDigest the source-run digest recorded in the calculation
   * @param runInputDigest the input digest of the run
   * @param calculationDigest the calculation digest
   * @param calculationProfileDigest the profile digest of the calculation
   * @param calculationRulePackDigest the rule-pack digest of the calculation
   * @param rulesDigest the rules digest of the pack
   * @param policySequence the sequence of the frozen policy
   * @param reviewedTotals acknowledgement
   * @param reviewedExceptions acknowledgement
   * @param understandsNoPayment acknowledgement
   */
  @Schema(name = "PayrollPackageEvidence")
  public record Evidence(
      String approvalDigest,
      String sourceRunDigest,
      String runInputDigest,
      String calculationDigest,
      String calculationProfileDigest,
      String calculationRulePackDigest,
      String rulesDigest,
      int policySequence,
      boolean reviewedTotals,
      boolean reviewedExceptions,
      boolean understandsNoPayment) {

    @Override
    public String toString() {
      return "PayrollPackageEvidence";
    }
  }

  /**
   * The void evidence.
   *
   * @param reasonCode closed reason
   * @param explanation the restricted explanation of OTHER_CONTROLLED, or {@code null}
   * @param voidedAt void instant
   */
  @Schema(name = "PayrollPackageVoid")
  public record VoidEvidence(String reasonCode, String explanation, Instant voidedAt) {

    @Override
    public String toString() {
      return "PayrollPackageVoid";
    }
  }

  /**
   * One package with its evidence.
   *
   * @param pack the package
   * @param evidence the restricted evidence
   * @param voided the void evidence, or {@code null}
   */
  @Schema(name = "PayrollPackageDetail")
  public record PackageDetail(
      @JsonProperty("package") PackageSummary pack,
      Evidence evidence,
      @JsonProperty("void") VoidEvidence voided) {

    @Override
    public String toString() {
      return "PayrollPackageDetail";
    }
  }

  /**
   * A void preview.
   *
   * @param legalEntityId legal entity
   * @param packageId package
   * @param packageVersion its version
   * @param period {@code YYYY-MM}
   * @param currency {@code CDF}
   * @param packageStatus current status
   * @param status {@code READY} or {@code BLOCKED}
   * @param blockers closed blocker codes
   * @param reasonCode the validated reason
   * @param payslipCount payslips
   * @param approvalDigest the package's approval digest
   * @param previewDigest the preview digest, or {@code null} unless READY
   * @param expiresAt expiry, or {@code null} unless READY
   */
  @Schema(name = "PayrollVoidPreview")
  public record VoidPreview(
      UUID legalEntityId,
      UUID packageId,
      int packageVersion,
      String period,
      String currency,
      String packageStatus,
      String status,
      List<String> blockers,
      String reasonCode,
      int payslipCount,
      String approvalDigest,
      String previewDigest,
      Instant expiresAt) {

    @Override
    public String toString() {
      return "PayrollVoidPreview";
    }

    /** Copies the list. */
    public VoidPreview {
      blockers = List.copyOf(blockers);
    }
  }

  /**
   * The amount-free receipt of a void. The explanation is never returned.
   *
   * @param packageId voided package
   * @param legalEntityId legal entity
   * @param version package version
   * @param period {@code YYYY-MM}
   * @param currency {@code CDF}
   * @param status always {@code VOIDED}
   * @param reasonCode closed reason
   * @param payslipCount payslips
   * @param voidedAt void instant
   */
  @Schema(name = "PayrollVoidReceipt")
  public record VoidReceipt(
      UUID packageId,
      UUID legalEntityId,
      int version,
      String period,
      String currency,
      String status,
      String reasonCode,
      int payslipCount,
      Instant voidedAt) {}

  /**
   * One published payslip in a list.
   *
   * @param payslipId payslip
   * @param packageId package
   * @param legalEntityId legal entity
   * @param period {@code YYYY-MM}
   * @param currency {@code CDF}
   * @param employeeId employee
   * @param employeeNumber employee number
   * @param status {@code APPROVED} or {@code VOIDED}
   * @param synthetic whether the rule pack is a test pack
   * @param voidReasonCode closed void reason, or {@code null}
   * @param approvedAt approval instant
   * @param voidedAt void instant, or {@code null}
   * @param contractualGross gross
   * @param employeeDeductions deductions
   * @param employerContributions employer contributions
   * @param approvedNet net
   */
  @Schema(name = "PayslipSummary")
  public record SlipSummary(
      UUID payslipId,
      UUID packageId,
      UUID legalEntityId,
      String period,
      String currency,
      UUID employeeId,
      String employeeNumber,
      String status,
      boolean synthetic,
      String voidReasonCode,
      Instant approvedAt,
      Instant voidedAt,
      MoneyView contractualGross,
      MoneyView employeeDeductions,
      MoneyView employerContributions,
      MoneyView approvedNet) {

    @Override
    public String toString() {
      return "PayslipSummary";
    }
  }

  /**
   * A page of payslips.
   *
   * @param items the payslips
   * @param nextCursor cursor of the next page, or {@code null}
   */
  @Schema(name = "PayslipPage")
  public record SlipPage(List<SlipSummary> items, String nextCursor) {

    /** Copies the list. */
    public SlipPage {
      items = List.copyOf(items);
    }
  }

  /**
   * One statutory component of the snapshot.
   *
   * @param seq 1-based position of the rule in the pack
   * @param id rule id
   * @param party {@code EMPLOYEE} or {@code EMPLOYER}
   * @param category {@code SOCIAL_CONTRIBUTION} or {@code INCOME_TAX}
   * @param base the base
   * @param amount the amount
   * @param explanationCodes explanation codes
   */
  @Schema(name = "PayslipSnapshotComponent")
  public record SnapshotComponent(
      int seq,
      String id,
      String party,
      String category,
      MoneyView base,
      MoneyView amount,
      List<String> explanationCodes) {

    @Override
    public String toString() {
      return "PayslipSnapshotComponent";
    }

    /** Copies the list. */
    public SnapshotComponent {
      explanationCodes = List.copyOf(explanationCodes);
    }
  }

  /**
   * The rule pack of a snapshot.
   *
   * @param code pack code
   * @param version pack version
   */
  @Schema(name = "PayslipSnapshotRulePack")
  public record SnapshotRulePack(String code, int version) {}

  /**
   * The canonical payslip snapshot (schema version 1) as an API object.
   *
   * @param schemaVersion always 1
   * @param packageId package
   * @param legalEntityId legal entity
   * @param period {@code YYYY-MM}
   * @param currency {@code CDF}
   * @param employeeId employee
   * @param employeeNumber employee number
   * @param status status at publication, always {@code APPROVED}
   * @param approvedAt approval instant
   * @param rulePack the rule pack
   * @param excluded whether subject to neither CNSS nor IRPP
   * @param contractualGross gross
   * @param employeeCnss employee CNSS
   * @param irppWithholding IRPP withholding
   * @param employeeDeductions total deductions
   * @param approvedNet net
   * @param employerContributions employer contributions (separate)
   * @param explanationCodes sorted distinct explanation codes
   * @param components the components in rule order
   */
  @Schema(name = "PayslipSnapshot")
  public record Snapshot(
      int schemaVersion,
      UUID packageId,
      UUID legalEntityId,
      String period,
      String currency,
      UUID employeeId,
      String employeeNumber,
      String status,
      Instant approvedAt,
      SnapshotRulePack rulePack,
      boolean excluded,
      MoneyView contractualGross,
      MoneyView employeeCnss,
      MoneyView irppWithholding,
      MoneyView employeeDeductions,
      MoneyView approvedNet,
      MoneyView employerContributions,
      List<String> explanationCodes,
      List<SnapshotComponent> components) {

    @Override
    public String toString() {
      return "PayslipSnapshot";
    }

    /** Copies the lists. */
    public Snapshot {
      explanationCodes = List.copyOf(explanationCodes);
      components = List.copyOf(components);
    }
  }

  /**
   * The wording rendered in the selected locale.
   *
   * @param locale {@code en} or {@code fr}
   * @param title title
   * @param statusLabel status label
   * @param notProofOfPayment the not-proof-of-payment sentence
   * @param employerContributionsNote the employer contributions sentence
   * @param rulePackStatement the rule pack statement
   * @param syntheticNotice the notice of a test pack, or {@code null}
   * @param voidedNotice the notice of a voided package, or {@code null}
   */
  @Schema(name = "PayslipWording")
  public record Wording(
      String locale,
      String title,
      String statusLabel,
      String notProofOfPayment,
      String employerContributionsNote,
      String rulePackStatement,
      String syntheticNotice,
      String voidedNotice) {}

  /**
   * The accessible HTML-primary payslip representation.
   *
   * @param payslipId payslip
   * @param status current status of the package
   * @param synthetic whether the rule pack is a test pack
   * @param voidReasonCode closed void reason, or {@code null}
   * @param voidedAt void instant, or {@code null}
   * @param snapshot the canonical snapshot
   * @param snapshotDigest SHA-256 of the canonical text
   * @param wording the wording in the selected locale
   */
  @Schema(name = "PayslipView")
  public record SlipView(
      UUID payslipId,
      String status,
      boolean synthetic,
      String voidReasonCode,
      Instant voidedAt,
      Snapshot snapshot,
      String snapshotDigest,
      Wording wording) {

    @Override
    public String toString() {
      return "PayslipView[" + payslipId + "]";
    }
  }
}
