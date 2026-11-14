package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Responses of accounting mappings and journals (MVP-096, ADR 0039). Restricted financial data: no
 * employee identifier, name, number or payslip detail is ever returned. A mapping's account codes
 * and a journal's lines and totals are returned only to a tenant administrator with privileged MFA
 * and never reach an audit record, an event, a log or an error. A journal is an accounting-ready
 * export: it is not posted anywhere and it is not proof of payment.
 */
public final class AccountingResponses {

  private AccountingResponses() {}

  /**
   * The five account codes of a mapping version.
   *
   * @param salaryExpense debit account of the gross salary
   * @param employeeDeductionLiability credit account of the employee deductions
   * @param employerContributionExpense debit account of the employer contributions
   * @param employerContributionLiability credit account of the employer contributions
   * @param netSalaryPayable credit account of the net salary
   */
  @Schema(name = "AccountingAccounts")
  public record Accounts(
      String salaryExpense,
      String employeeDeductionLiability,
      String employerContributionExpense,
      String employerContributionLiability,
      String netSalaryPayable) {}

  /**
   * One append-only mapping version.
   *
   * @param id mapping
   * @param legalEntityId legal entity
   * @param currency CDF or USD
   * @param version 1 to 10
   * @param effectiveFrom first day it applies
   * @param accounts the five codes
   * @param mappingDigest stored digest
   * @param status SCHEDULED, ACTIVE or SUPERSEDED, derived at read time
   * @param createdAt creation instant
   */
  @Schema(name = "AccountingMapping")
  public record MappingView(
      UUID id,
      UUID legalEntityId,
      String currency,
      int version,
      LocalDate effectiveFrom,
      Accounts accounts,
      String mappingDigest,
      String status,
      Instant createdAt) {}

  /**
   * The mapping versions of a legal entity, newest first.
   *
   * @param items versions
   */
  @Schema(name = "AccountingMappingList")
  public record MappingList(List<MappingView> items) {

    /** Copies the list. */
    public MappingList {
      items = List.copyOf(items);
    }
  }

  /**
   * What appending a mapping version would do.
   *
   * @param legalEntityId legal entity
   * @param currency CDF or USD
   * @param nextVersion the version number it would receive (1 to 11)
   * @param effectiveFrom first day it would apply
   * @param accounts the five trimmed codes
   * @param previousEffectiveFrom effective date of the latest existing version, or {@code null}
   * @param status {@code READY} or {@code BLOCKED}
   * @param blockers stable blocker codes
   * @param previewDigest digest to send back, or {@code null} when blocked
   * @param expiresAt expiry, or {@code null} when blocked
   */
  @Schema(name = "AccountingMappingPreview")
  public record MappingPreview(
      UUID legalEntityId,
      String currency,
      int nextVersion,
      LocalDate effectiveFrom,
      Accounts accounts,
      LocalDate previousEffectiveFrom,
      String status,
      List<String> blockers,
      String previewDigest,
      Instant expiresAt) {

    /** Copies the list. */
    public MappingPreview {
      blockers = List.copyOf(blockers);
    }
  }

  /**
   * The receipt of an appended mapping version.
   *
   * @param mappingId mapping
   * @param legalEntityId legal entity
   * @param currency CDF or USD
   * @param version version number
   * @param effectiveFrom first day it applies
   * @param createdAt creation instant
   */
  @Schema(name = "AccountingMappingReceipt")
  public record MappingReceipt(
      UUID mappingId,
      UUID legalEntityId,
      String currency,
      int version,
      LocalDate effectiveFrom,
      Instant createdAt) {}

  /**
   * One journal line.
   *
   * @param lineNumber 1 to the line count
   * @param accountCode mapped account code
   * @param direction DEBIT or CREDIT
   * @param amountMinor amount in minor units, positive
   * @param costCenterCode cost center code, or {@code null} (none today)
   * @param siteCode site code, or {@code null} (none today)
   */
  @Schema(name = "AccountingJournalLine")
  public record Line(
      int lineNumber,
      String accountCode,
      String direction,
      long amountMinor,
      String costCenterCode,
      String siteCode) {}

  /**
   * The mapping version a journal was generated with.
   *
   * @param id mapping
   * @param version version number
   * @param effectiveFrom first day it applies
   */
  @Schema(name = "AccountingJournalMappingRef")
  public record MappingRef(UUID id, int version, LocalDate effectiveFrom) {}

  /**
   * The exact journal a generation would create.
   *
   * @param legalEntityId legal entity
   * @param packageId package
   * @param packageVersion package version
   * @param period {@code YYYY-MM}
   * @param currency CDF or USD
   * @param mapping the applicable mapping version, or {@code null} when none applies
   * @param status {@code READY} or {@code BLOCKED}
   * @param blockers stable blocker codes
   * @param sourceEntryCount entries before aggregation
   * @param lineCount aggregated lines
   * @param totalDebitsMinor total debits
   * @param totalCreditsMinor total credits
   * @param lines the aggregated lines, in journal order
   * @param previewDigest digest to send back, or {@code null} when blocked
   * @param expiresAt expiry, or {@code null} when blocked
   */
  @Schema(name = "AccountingJournalPreview")
  public record JournalPreview(
      UUID legalEntityId,
      UUID packageId,
      int packageVersion,
      String period,
      String currency,
      MappingRef mapping,
      String status,
      List<String> blockers,
      int sourceEntryCount,
      int lineCount,
      long totalDebitsMinor,
      long totalCreditsMinor,
      List<Line> lines,
      String previewDigest,
      Instant expiresAt) {

    /** Copies the lists. */
    public JournalPreview {
      blockers = List.copyOf(blockers);
      lines = List.copyOf(lines);
    }
  }

  /**
   * The receipt of a generated journal (amount-free).
   *
   * @param journalId journal
   * @param legalEntityId legal entity
   * @param packageId package
   * @param currency CDF or USD
   * @param version journal version
   * @param lineCount lines
   * @param generatedAt generation instant
   */
  @Schema(name = "AccountingJournalReceipt")
  public record Receipt(
      UUID journalId,
      UUID legalEntityId,
      UUID packageId,
      String currency,
      int version,
      int lineCount,
      Instant generatedAt) {}

  /**
   * One journal in a package's history.
   *
   * @param id journal
   * @param legalEntityId legal entity
   * @param packageId package
   * @param version journal version
   * @param period {@code YYYY-MM}
   * @param currency CDF or USD
   * @param lineCount lines
   * @param generatedAt generation instant
   * @param sourceVoided whether the package was voided after generation (derived at read time)
   */
  @Schema(name = "AccountingJournalSummary")
  public record Summary(
      UUID id,
      UUID legalEntityId,
      UUID packageId,
      int version,
      String period,
      String currency,
      int lineCount,
      Instant generatedAt,
      boolean sourceVoided) {}

  /**
   * The journals of a package.
   *
   * @param items journals
   */
  @Schema(name = "AccountingJournalList")
  public record JournalList(List<Summary> items) {

    /** Copies the list. */
    public JournalList {
      items = List.copyOf(items);
    }
  }

  /**
   * The immutable journal.
   *
   * @param id journal
   * @param legalEntityId legal entity
   * @param legalEntityCode stored legal entity code
   * @param packageId package
   * @param packageVersion package version
   * @param version journal version
   * @param period {@code YYYY-MM}
   * @param currency CDF or USD
   * @param mapping the mapping version used
   * @param mappingDigest the mapping digest
   * @param payslipCount payslips behind the journal
   * @param sourceEntryCount entries before aggregation
   * @param lineCount lines
   * @param totalDebitsMinor total debits
   * @param totalCreditsMinor total credits (equal to the debits)
   * @param csvSha256 SHA-256 of the CSV
   * @param csvBytes size of the CSV
   * @param journalDigest journal digest
   * @param schemaVersion always 1
   * @param generatedAt generation instant
   * @param sourceVoided whether the package was voided after generation (derived at read time)
   * @param lines the lines, in journal order
   */
  @Schema(name = "AccountingJournalView")
  public record JournalView(
      UUID id,
      UUID legalEntityId,
      String legalEntityCode,
      UUID packageId,
      int packageVersion,
      int version,
      String period,
      String currency,
      MappingRef mapping,
      String mappingDigest,
      int payslipCount,
      int sourceEntryCount,
      int lineCount,
      long totalDebitsMinor,
      long totalCreditsMinor,
      String csvSha256,
      int csvBytes,
      String journalDigest,
      int schemaVersion,
      Instant generatedAt,
      boolean sourceVoided,
      List<Line> lines) {

    /** Copies the list. */
    public JournalView {
      lines = List.copyOf(lines);
    }
  }
}
