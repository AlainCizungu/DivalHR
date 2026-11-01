package com.divalhr.core.payroll.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * The digests of a payroll package (MVP-094C, ADR 0035 section 4): the snapshot digest, the
 * approval digest (byte-identical to the SQL function {@code
 * payroll.payroll_package_approval_digest} of V46, which recomputes it at commit) and the preview
 * digests of the approval and of the void. All are lower-case hex SHA-256 of UTF-8 text. They are
 * compared and hashed only; they are never logged, never put in audit metadata, outbox events,
 * cursors or errors.
 */
public final class PackageDigests {

  private PackageDigests() {}

  /**
   * The facts the approval digest covers (everything but the chain version, the approver, the
   * approval time and the acknowledgements).
   *
   * @param tenantId tenant
   * @param legalEntityId legal entity
   * @param period {@code YYYY-MM}
   * @param runId frozen run
   * @param runVersion its version
   * @param runDigest its input digest
   * @param calculationId statutory calculation
   * @param calculationVersion its version
   * @param calculationDigest its digest
   * @param calculationSourceRunDigest its stored source-run digest
   * @param calculationProfileDigest its stored profile digest
   * @param calculationRulePackDigest its stored rule-pack digest
   * @param packId rule pack
   * @param rulesDigest the pack's rules digest
   * @param policyId frozen policy
   * @param policySequence its sequence
   * @param grossTotal total contractual gross
   * @param deductionsTotal total employee deductions
   * @param contributionsTotal total employer contributions
   * @param netTotal total net
   * @param costTotal total employer cost
   * @param payslipCount number of payslips
   */
  public record ApprovalBasis(
      UUID tenantId,
      UUID legalEntityId,
      String period,
      UUID runId,
      int runVersion,
      String runDigest,
      UUID calculationId,
      int calculationVersion,
      String calculationDigest,
      String calculationSourceRunDigest,
      String calculationProfileDigest,
      String calculationRulePackDigest,
      UUID packId,
      String rulesDigest,
      UUID policyId,
      int policySequence,
      long grossTotal,
      long deductionsTotal,
      long contributionsTotal,
      long netTotal,
      long costTotal,
      int payslipCount) {

    @Override
    public String toString() {
      return "ApprovalBasis[" + runId + "]";
    }
  }

  /**
   * One payslip line of the approval digest.
   *
   * @param employeeId employee
   * @param employeeNumber employee number
   * @param snapshotDigest the digest of the snapshot text
   */
  public record Slip(UUID employeeId, String employeeNumber, String snapshotDigest) {

    @Override
    public String toString() {
      return "PackageSlip[" + employeeId + "]";
    }
  }

  /**
   * The digest of a snapshot text.
   *
   * @param snapshotText the exact canonical text
   * @return lower-case hex SHA-256 of its UTF-8 bytes
   */
  public static String snapshotDigest(String snapshotText) {
    return sha256(snapshotText);
  }

  /**
   * The approval digest, as the database recomputes it: the versioned header, the sources, the
   * pack, the policy, the totals, then one line per payslip ordered by employee number in byte
   * order, then employee id. Every line ends with a line feed.
   *
   * @param basis the facts
   * @param slips the payslips in any order
   * @return the digest
   */
  public static String approval(ApprovalBasis basis, List<Slip> slips) {
    StringBuilder text = new StringBuilder(256 + slips.size() * 140);
    text.append("payroll-package-approval:v1\n");
    text.append("tenant=").append(basis.tenantId()).append('\n');
    text.append("entity=").append(basis.legalEntityId()).append('\n');
    text.append("period=").append(basis.period()).append('\n');
    text.append("currency=CDF\n");
    text.append("run=")
        .append(basis.runId())
        .append('|')
        .append(basis.runVersion())
        .append('|')
        .append(basis.runDigest())
        .append('\n');
    text.append("calculation=")
        .append(basis.calculationId())
        .append('|')
        .append(basis.calculationVersion())
        .append('|')
        .append(basis.calculationDigest())
        .append('|')
        .append(basis.calculationSourceRunDigest())
        .append('|')
        .append(basis.calculationProfileDigest())
        .append('|')
        .append(basis.calculationRulePackDigest())
        .append('\n');
    text.append("pack=")
        .append(basis.packId())
        .append('|')
        .append(basis.rulesDigest())
        .append('\n');
    text.append("policy=")
        .append(basis.policyId())
        .append('|')
        .append(basis.policySequence())
        .append('\n');
    text.append("totals=")
        .append(basis.grossTotal())
        .append('|')
        .append(basis.deductionsTotal())
        .append('|')
        .append(basis.contributionsTotal())
        .append('|')
        .append(basis.netTotal())
        .append('|')
        .append(basis.costTotal())
        .append('|')
        .append(basis.payslipCount())
        .append('\n');
    List<Slip> ordered = new ArrayList<>(slips);
    ordered.sort(
        Comparator.comparing(
                (Slip slip) -> slip.employeeNumber().getBytes(StandardCharsets.UTF_8),
                Arrays::compareUnsigned)
            .thenComparing(slip -> slip.employeeId().toString()));
    for (Slip slip : ordered) {
      text.append(slip.employeeId())
          .append('|')
          .append(slip.employeeNumber())
          .append('|')
          .append(slip.snapshotDigest())
          .append('\n');
    }
    return sha256(text.toString());
  }

  /**
   * The digest of an approval preview (ADR 0035 section 4.4). {@code policyId} and {@code
   * policySequence} name the policy in force now, so that a policy change after the preview makes
   * the commit stale.
   *
   * @param tenantId tenant
   * @param legalEntityId legal entity
   * @param runId run
   * @param runVersion its version
   * @param calculationId calculation
   * @param calculationVersion its version
   * @param sourceRunDigest live source-run digest
   * @param calculationDigest stored calculation digest
   * @param profileDigest live profile digest
   * @param rulePackDigest live rule-pack digest (may be {@code null})
   * @param policyId policy in force
   * @param policySequence its sequence
   * @param samePreparer whether the committing subject also prepared a source
   * @param expectedVersion the chain version
   * @param expiresAt expiry
   * @return the digest
   */
  public static String approvalPreview(
      UUID tenantId,
      UUID legalEntityId,
      UUID runId,
      int runVersion,
      UUID calculationId,
      int calculationVersion,
      String sourceRunDigest,
      String calculationDigest,
      String profileDigest,
      String rulePackDigest,
      UUID policyId,
      int policySequence,
      boolean samePreparer,
      int expectedVersion,
      Instant expiresAt) {
    String text =
        "DIVALHR-PAYROLL-APPROVAL-PREVIEW\nversion=1\ntenant="
            + tenantId
            + "\nentity="
            + legalEntityId
            + "\nrun="
            + runId
            + "\nrunVersion="
            + runVersion
            + "\ncalculation="
            + calculationId
            + "\ncalculationVersion="
            + calculationVersion
            + "\nsourceRunDigest="
            + sourceRunDigest
            + "\ncalculationDigest="
            + calculationDigest
            + "\nprofileDigest="
            + profileDigest
            + "\nrulePackDigest="
            + rulePackDigest
            + "\npolicy="
            + policyId
            + ';'
            + policySequence
            + "\nsamePreparer="
            + samePreparer
            + "\nexpectedVersion="
            + expectedVersion
            + "\nexpiresAt="
            + expiresAt
            + "\n";
    return sha256(text);
  }

  /**
   * The digest of a void preview. The explanation enters only as its own SHA-256, so the digest
   * carries no text.
   *
   * @param tenantId tenant
   * @param legalEntityId legal entity
   * @param packageId package
   * @param packageVersion package version
   * @param approvalDigest the package's approval digest
   * @param reason the closed reason
   * @param explanation the explanation, or {@code null}
   * @param expiresAt expiry
   * @return the digest
   */
  public static String voidPreview(
      UUID tenantId,
      UUID legalEntityId,
      UUID packageId,
      int packageVersion,
      String approvalDigest,
      PayrollVoidReason reason,
      String explanation,
      Instant expiresAt) {
    String text =
        "DIVALHR-PAYROLL-VOID-PREVIEW\nversion=1\ntenant="
            + tenantId
            + "\nentity="
            + legalEntityId
            + "\npackage="
            + packageId
            + "\npackageVersion="
            + packageVersion
            + "\napprovalDigest="
            + approvalDigest
            + "\nreason="
            + reason.name()
            + "\nexplanation="
            + (explanation == null ? "" : sha256(explanation))
            + "\nexpiresAt="
            + expiresAt
            + "\n";
    return sha256(text);
  }

  /**
   * Lower-case hex SHA-256 of UTF-8 text.
   *
   * @param text the text
   * @return 64 hex characters
   */
  public static String sha256(String text) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
