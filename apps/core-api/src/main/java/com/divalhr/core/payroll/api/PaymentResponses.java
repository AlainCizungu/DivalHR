package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Responses of payment destinations and prepared payment batches (MVP-095A, ADR 0036). Restricted
 * financial and HR data: a destination is only ever masked here (never the account, phone, holder
 * or branch), amounts are integer minor units next to the CDF currency code, and no record prints
 * a value, an employee number or a digest. A prepared batch records instructions to pay; it is not
 * a payment, a transmission, a bank file or a claim of legal compliance.
 */
public final class PaymentResponses {

  private PaymentResponses() {}

  /**
   * A controlled provider.
   *
   * @param code provider code
   * @param name display name
   */
  @Schema(name = "PaymentProvider")
  public record Provider(String code, String name) {}

  /**
   * One recorded destination version, masked.
   *
   * @param id version ID
   * @param version version number
   * @param method the method
   * @param countryCode ISO country, or {@code null}
   * @param provider the provider, or {@code null}
   * @param maskedValue masked account or phone, or {@code null}
   * @param effectiveFrom first day it applies
   * @param status always {@code RECORDED}
   * @param reasonCode closed reason
   * @param recordedAt recording instant
   */
  @Schema(name = "PaymentDestinationView")
  public record DestinationView(
      UUID id,
      int version,
      String method,
      String countryCode,
      Provider provider,
      String maskedValue,
      LocalDate effectiveFrom,
      String status,
      String reasonCode,
      Instant recordedAt) {

    @Override
    public String toString() {
      return "PaymentDestinationView[" + id + "]";
    }
  }

  /**
   * The employee's destination.
   *
   * @param employeeId employee
   * @param latestVersion latest version number, 0 when none
   * @param current the version effective today, or {@code null}
   * @param scheduled the next future version, or {@code null}
   */
  @Schema(name = "PaymentDestinationRecord")
  public record DestinationRecord(
      UUID employeeId, int latestVersion, DestinationView current, DestinationView scheduled) {}

  /**
   * A page of destination versions.
   *
   * @param items versions, newest first
   * @param nextCursor cursor, or {@code null}
   */
  @Schema(name = "PaymentDestinationPage")
  public record DestinationPage(List<DestinationView> items, String nextCursor) {

    /** Copies the list. */
    public DestinationPage {
      items = List.copyOf(items);
    }
  }

  /**
   * What recording a destination would do.
   *
   * @param employeeId employee
   * @param expectedVersion latest version the preview saw
   * @param status {@code READY} or {@code BLOCKED}
   * @param blockers why not
   * @param warnings what to look at
   * @param method the method
   * @param countryCode country, or {@code null}
   * @param provider provider, or {@code null}
   * @param maskedValue masked value, or {@code null}
   * @param effectiveFrom first day it applies
   * @param reasonCode closed reason
   * @param previewDigest digest to send back, or {@code null} when blocked
   * @param expiresAt expiry, or {@code null} when blocked
   */
  @Schema(name = "PaymentDestinationPreview")
  public record DestinationPreview(
      UUID employeeId,
      int expectedVersion,
      String status,
      List<String> blockers,
      List<String> warnings,
      String method,
      String countryCode,
      Provider provider,
      String maskedValue,
      LocalDate effectiveFrom,
      String reasonCode,
      String previewDigest,
      Instant expiresAt) {

    /** Copies the lists. */
    public DestinationPreview {
      blockers = List.copyOf(blockers);
      warnings = List.copyOf(warnings);
    }
  }

  /**
   * The receipt of a recorded destination (masked; what the idempotency store keeps).
   *
   * @param employeeId employee
   * @param version version number
   * @param method the method
   * @param countryCode country, or {@code null}
   * @param provider provider, or {@code null}
   * @param maskedValue masked value, or {@code null}
   * @param effectiveFrom first day it applies
   * @param status always {@code RECORDED}
   * @param reasonCode closed reason
   * @param recordedAt recording instant
   */
  @Schema(name = "PaymentDestinationReceipt")
  public record DestinationReceipt(
      UUID employeeId,
      int version,
      String method,
      String countryCode,
      Provider provider,
      String maskedValue,
      LocalDate effectiveFrom,
      String status,
      String reasonCode,
      Instant recordedAt) {}

  /**
   * The caller's own current destination, masked.
   *
   * @param status {@code RECORDED} or {@code NOT_RECORDED}
   * @param method the method, or {@code null}
   * @param countryCode country, or {@code null}
   * @param provider provider, or {@code null}
   * @param maskedValue masked value, or {@code null}
   * @param effectiveFrom first day it applies, or {@code null}
   */
  @Schema(name = "MyPaymentDestination")
  public record MyDestination(
      String status,
      String method,
      String countryCode,
      Provider provider,
      String maskedValue,
      LocalDate effectiveFrom) {}

  /**
   * Line counts by method.
   *
   * @param bankAccount bank lines
   * @param mobileMoney mobile-money lines
   * @param manual manual lines
   */
  @Schema(name = "PaymentBatchMethodCounts")
  public record MethodCounts(int bankAccount, int mobileMoney, int manual) {}

  /**
   * One batch line (masked destination, approved net).
   *
   * @param payslipId payslip
   * @param employeeId employee
   * @param employeeNumber employee number
   * @param method method, or {@code null} when missing
   * @param provider provider, or {@code null}
   * @param maskedValue masked value, or {@code null}
   * @param destinationVersion destination version, or {@code null}
   * @param netMinor approved net in minor units
   * @param issue the problem, or {@code null}
   */
  @Schema(name = "PaymentBatchLine")
  public record BatchLine(
      UUID payslipId,
      UUID employeeId,
      String employeeNumber,
      String method,
      Provider provider,
      String maskedValue,
      Integer destinationVersion,
      long netMinor,
      String issue) {

    @Override
    public String toString() {
      return "PaymentBatchLine[" + payslipId + "]";
    }
  }

  /**
   * What preparing a batch would record.
   *
   * @param legalEntityId legal entity
   * @param packageId package
   * @param packageVersion package version
   * @param period {@code YYYY-MM}
   * @param currency {@code CDF}
   * @param packageStatus package status
   * @param status {@code READY} or {@code BLOCKED}
   * @param blockers why not
   * @param warnings what to look at
   * @param lineCount payable lines
   * @param zeroNetExcludedCount zero-net payslips left out
   * @param methodCounts lines by method
   * @param totalNetMinor total approved net
   * @param expectedVersion latest batch version the preview saw
   * @param destinationsDigest digest of the destinations used, or {@code null}
   * @param previewDigest digest to send back, or {@code null} when blocked
   * @param expiresAt expiry, or {@code null} when blocked
   * @param lines the lines
   */
  @Schema(name = "PaymentBatchPreview")
  public record BatchPreview(
      UUID legalEntityId,
      UUID packageId,
      int packageVersion,
      String period,
      String currency,
      String packageStatus,
      String status,
      List<String> blockers,
      List<String> warnings,
      int lineCount,
      int zeroNetExcludedCount,
      MethodCounts methodCounts,
      long totalNetMinor,
      int expectedVersion,
      String destinationsDigest,
      String previewDigest,
      Instant expiresAt,
      List<BatchLine> lines) {

    /** Copies the lists. */
    public BatchPreview {
      blockers = List.copyOf(blockers);
      warnings = List.copyOf(warnings);
      lines = List.copyOf(lines);
    }
  }

  /**
   * The receipt of a prepared batch (no amount, no destination).
   *
   * @param batchId batch
   * @param packageId package
   * @param legalEntityId legal entity
   * @param version batch version
   * @param status {@code PREPARED}
   * @param period {@code YYYY-MM}
   * @param currency {@code CDF}
   * @param lineCount lines
   * @param preparedAt instant
   */
  @Schema(name = "PaymentBatchReceipt")
  public record BatchReceipt(
      UUID batchId,
      UUID packageId,
      UUID legalEntityId,
      int version,
      String status,
      String period,
      String currency,
      int lineCount,
      Instant preparedAt) {}

  /**
   * A batch summary.
   *
   * @param id batch
   * @param legalEntityId legal entity
   * @param packageId package
   * @param packageVersion package version
   * @param version batch version
   * @param status {@code PREPARED} or {@code CANCELLED}
   * @param period {@code YYYY-MM}
   * @param currency {@code CDF}
   * @param lineCount lines
   * @param zeroNetExcludedCount zero-net payslips left out
   * @param methodCounts lines by method
   * @param totalNetMinor total approved net
   * @param preparedAt prepared instant
   * @param cancelledAt cancel instant, or {@code null}
   * @param cancelReasonCode closed cancel reason, or {@code null}
   */
  @Schema(name = "PaymentBatchSummary")
  public record BatchSummary(
      UUID id,
      UUID legalEntityId,
      UUID packageId,
      int packageVersion,
      int version,
      String status,
      String period,
      String currency,
      int lineCount,
      int zeroNetExcludedCount,
      MethodCounts methodCounts,
      long totalNetMinor,
      Instant preparedAt,
      Instant cancelledAt,
      String cancelReasonCode) {

    @Override
    public String toString() {
      return "PaymentBatchSummary[" + id + "]";
    }
  }

  /**
   * A page of batches.
   *
   * @param items batches, newest first
   * @param nextCursor cursor, or {@code null}
   */
  @Schema(name = "PaymentBatchPage")
  public record BatchPage(List<BatchSummary> items, String nextCursor) {

    /** Copies the list. */
    public BatchPage {
      items = List.copyOf(items);
    }
  }

  /**
   * One batch with its evidence.
   *
   * @param batch the summary
   * @param batchDigest the batch digest
   * @param packageApprovalDigest the approval digest of the package
   * @param destinationsDigest the digest of the destinations used
   */
  @Schema(name = "PaymentBatchDetail")
  public record BatchDetail(
      BatchSummary batch,
      String batchDigest,
      String packageApprovalDigest,
      String destinationsDigest) {

    @Override
    public String toString() {
      return "PaymentBatchDetail";
    }
  }

  /**
   * A page of lines.
   *
   * @param items lines by employee ID
   * @param nextCursor cursor, or {@code null}
   */
  @Schema(name = "PaymentBatchLinePage")
  public record LinePage(List<BatchLine> items, String nextCursor) {

    /** Copies the list. */
    public LinePage {
      items = List.copyOf(items);
    }
  }

  /**
   * What cancelling a batch would do.
   *
   * @param legalEntityId legal entity
   * @param packageId package
   * @param batchId batch
   * @param batchVersion batch version
   * @param batchStatus current status
   * @param status {@code READY} or {@code BLOCKED}
   * @param blockers why not
   * @param reasonCode closed reason
   * @param lineCount lines
   * @param batchDigest batch digest
   * @param previewDigest digest to send back, or {@code null} when blocked
   * @param expiresAt expiry, or {@code null} when blocked
   */
  @Schema(name = "PaymentBatchCancelPreview")
  public record CancelPreview(
      UUID legalEntityId,
      UUID packageId,
      UUID batchId,
      int batchVersion,
      String batchStatus,
      String status,
      List<String> blockers,
      String reasonCode,
      int lineCount,
      String batchDigest,
      String previewDigest,
      Instant expiresAt) {

    /** Copies the list. */
    public CancelPreview {
      blockers = List.copyOf(blockers);
    }
  }

  /**
   * The receipt of a cancelled batch.
   *
   * @param batchId batch
   * @param packageId package
   * @param legalEntityId legal entity
   * @param version batch version
   * @param status {@code CANCELLED}
   * @param reasonCode closed reason
   * @param cancelledAt instant
   */
  @Schema(name = "PaymentBatchCancelReceipt")
  public record CancelReceipt(
      UUID batchId,
      UUID packageId,
      UUID legalEntityId,
      int version,
      String status,
      String reasonCode,
      Instant cancelledAt) {}
}
