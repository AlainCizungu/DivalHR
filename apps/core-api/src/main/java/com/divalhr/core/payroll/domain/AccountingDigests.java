package com.divalhr.core.payroll.domain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * The canonical digests of accounting mappings and journals (MVP-096, ADR 0039 section 5). A digest
 * is the lower-case hex SHA-256 of a fixed-order UTF-8 text with a version prefix. Digests are
 * compared and stored only; they are never logged and never put in audit metadata, outbox events,
 * cursors or errors.
 */
public final class AccountingDigests {

  private AccountingDigests() {}

  /**
   * The SHA-256 of file content.
   *
   * @param content the bytes
   * @return 64 lower-case hex characters
   */
  public static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  /**
   * The digest of one mapping version: tenant, entity, currency, version, effective date and the
   * five codes.
   *
   * @param tenantId tenant
   * @param legalEntityId legal entity
   * @param currency currency
   * @param version version number
   * @param effectiveFrom effective date
   * @param accounts the five codes
   * @return the digest
   */
  public static String mapping(
      UUID tenantId,
      UUID legalEntityId,
      AccountingCurrency currency,
      int version,
      LocalDate effectiveFrom,
      AccountingAccounts accounts) {
    return PackageDigests.sha256(
        "DIVALHR-ACCOUNTING-MAPPING\nversion=1\ntenant="
            + tenantId
            + "\nentity="
            + legalEntityId
            + "\ncurrency="
            + currency.name()
            + "\nmappingVersion="
            + version
            + "\neffectiveFrom="
            + effectiveFrom
            + "\nsalaryExpense="
            + accounts.salaryExpense()
            + "\nemployeeDeductionLiability="
            + accounts.employeeDeductionLiability()
            + "\nemployerContributionExpense="
            + accounts.employerContributionExpense()
            + "\nemployerContributionLiability="
            + accounts.employerContributionLiability()
            + "\nnetSalaryPayable="
            + accounts.netSalaryPayable()
            + "\n");
  }

  /**
   * The preview digest of appending a mapping version: the mapping digest of the version it would
   * create, the previous effective date and the expiry.
   *
   * @param mappingDigest the {@link #mapping} digest of the version to append
   * @param previousEffectiveFrom effective date of the latest existing version, or {@code null}
   * @param expiresAt expiry
   * @return the digest
   */
  public static String mappingPreview(
      String mappingDigest, LocalDate previousEffectiveFrom, Instant expiresAt) {
    return PackageDigests.sha256(
        "DIVALHR-ACCOUNTING-MAPPING-PREVIEW\nversion=1\nmapping="
            + mappingDigest
            + "\nprevious="
            + (previousEffectiveFrom == null ? "" : previousEffectiveFrom.toString())
            + "\nexpiresAt="
            + expiresAt
            + "\n");
  }

  /**
   * The facts a journal preview binds.
   *
   * @param tenantId tenant
   * @param legalEntityId legal entity
   * @param packageId package
   * @param packageVersion package version
   * @param approvalDigest package approval digest
   * @param calculationDigest calculation digest
   * @param mappingId mapping
   * @param mappingVersion mapping version
   * @param mappingDigest mapping digest
   * @param currency currency
   * @param lineCount aggregated lines
   * @param totalDebitsMinor total debits
   * @param totalCreditsMinor total credits
   */
  public record JournalFacts(
      UUID tenantId,
      UUID legalEntityId,
      UUID packageId,
      int packageVersion,
      String approvalDigest,
      String calculationDigest,
      UUID mappingId,
      int mappingVersion,
      String mappingDigest,
      String currency,
      int lineCount,
      long totalDebitsMinor,
      long totalCreditsMinor) {

    @Override
    public String toString() {
      return "AccountingJournalFacts[redacted]";
    }
  }

  /**
   * The preview digest of a journal: tenant, entity, package id, version, approval digest and
   * calculation digest, mapping id, version and digest, currency, aggregated line count, total
   * debits, total credits and expiry.
   *
   * @param facts the facts
   * @param expiresAt expiry
   * @return the digest
   */
  public static String journalPreview(JournalFacts facts, Instant expiresAt) {
    return PackageDigests.sha256(
        "DIVALHR-ACCOUNTING-JOURNAL-PREVIEW\nversion=1\n"
            + facts(facts)
            + "expiresAt="
            + expiresAt
            + "\n");
  }

  /**
   * The digest of a generated journal: the facts, the digest of its lines and its CSV SHA-256.
   *
   * @param journalId journal
   * @param journalVersion journal version
   * @param facts the facts
   * @param linesDigest the {@link #lines} digest
   * @param csvSha256 SHA-256 of the CSV bytes
   * @return the digest
   */
  public static String journal(
      UUID journalId,
      int journalVersion,
      JournalFacts facts,
      String linesDigest,
      String csvSha256) {
    return PackageDigests.sha256(
        "DIVALHR-ACCOUNTING-JOURNAL\nversion=1\njournal="
            + journalId
            + "\njournalVersion="
            + journalVersion
            + "\n"
            + facts(facts)
            + "lines="
            + linesDigest
            + "\ncsv="
            + csvSha256
            + "\n");
  }

  /**
   * The digest of the journal lines, in journal order.
   *
   * @param lines the lines
   * @return the digest
   */
  public static String lines(List<AccountingLine> lines) {
    StringBuilder text = new StringBuilder("DIVALHR-ACCOUNTING-LINES\nversion=1\n");
    for (AccountingLine line : lines) {
      text.append(line.lineNumber())
          .append('|')
          .append(line.accountCode())
          .append('|')
          .append(line.direction().name())
          .append('|')
          .append(line.amountMinor())
          .append('|')
          .append(line.costCenterCode() == null ? "" : line.costCenterCode())
          .append('|')
          .append(line.siteCode() == null ? "" : line.siteCode())
          .append('\n');
    }
    return PackageDigests.sha256(text.toString());
  }

  private static String facts(JournalFacts facts) {
    return "tenant="
        + facts.tenantId()
        + "\nentity="
        + facts.legalEntityId()
        + "\npackage="
        + facts.packageId()
        + "\npackageVersion="
        + facts.packageVersion()
        + "\napproval="
        + facts.approvalDigest()
        + "\ncalculation="
        + facts.calculationDigest()
        + "\nmapping="
        + facts.mappingId()
        + "\nmappingVersion="
        + facts.mappingVersion()
        + "\nmappingDigest="
        + facts.mappingDigest()
        + "\ncurrency="
        + facts.currency()
        + "\nlineCount="
        + facts.lineCount()
        + "\ntotalDebitsMinor="
        + facts.totalDebitsMinor()
        + "\ntotalCreditsMinor="
        + facts.totalCreditsMinor()
        + "\n";
  }
}
