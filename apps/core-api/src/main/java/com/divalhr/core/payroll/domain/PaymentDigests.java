package com.divalhr.core.payroll.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The canonical digests of payment destinations and batches (MVP-095A, D95A-5). A digest is the
 * SHA-256 of a fixed-order text. Sensitive destination values enter only as keyed fingerprints, so
 * a digest is never an offline-guessing oracle for an account or phone number.
 */
public final class PaymentDigests {

  private PaymentDigests() {}

  /**
   * The preview digest of a destination.
   *
   * @param tenantId tenant
   * @param employeeId employee
   * @param expectedVersion latest recorded version, 0 when none
   * @param contentFingerprint keyed fingerprint of the destination content
   * @param effectiveFrom effective date
   * @param reason closed reason
   * @param expiresAt expiry
   * @return the digest
   */
  public static String destinationPreview(
      UUID tenantId,
      UUID employeeId,
      int expectedVersion,
      String contentFingerprint,
      LocalDate effectiveFrom,
      PaymentDestinationReason reason,
      Instant expiresAt) {
    return PackageDigests.sha256(
        "DIVALHR-PAYMENT-DESTINATION-PREVIEW\nversion=1\ntenant="
            + tenantId
            + "\nemployee="
            + employeeId
            + "\nexpectedVersion="
            + expectedVersion
            + "\ncontent="
            + contentFingerprint
            + "\neffectiveFrom="
            + effectiveFrom
            + "\nreason="
            + reason.name()
            + "\nexpiresAt="
            + expiresAt
            + "\n");
  }

  /**
   * The digest of the destination versions behind a set of lines.
   *
   * @param lines the lines, in employee order
   * @return the digest
   */
  public static String destinations(List<PaymentBatchEvaluator.Line> lines) {
    StringBuilder text = new StringBuilder("DIVALHR-PAYMENT-DESTINATIONS\nversion=1\n");
    for (PaymentBatchEvaluator.Line line : lines) {
      PaymentBatchEvaluator.Destination destination = line.destination();
      text.append(line.employeeId())
          .append('|')
          .append(destination == null ? "-" : destination.id())
          .append('|')
          .append(destination == null ? 0 : destination.sequence())
          .append('|')
          .append(
              destination == null || destination.fingerprint() == null
                  ? "-"
                  : destination.fingerprint())
          .append('\n');
    }
    return PackageDigests.sha256(text.toString());
  }

  /**
   * The digest of the lines themselves (identity, amount and masked destination of each).
   *
   * @param lines the lines, in employee order
   * @return the digest
   */
  public static String lines(List<PaymentBatchEvaluator.Line> lines) {
    StringBuilder text = new StringBuilder("DIVALHR-PAYMENT-LINES\nversion=1\n");
    for (PaymentBatchEvaluator.Line line : lines) {
      PaymentBatchEvaluator.Destination destination = line.destination();
      text.append(line.payslipId())
          .append('|')
          .append(line.employeeId())
          .append('|')
          .append(line.net())
          .append('|')
          .append(destination == null ? "-" : destination.method().name())
          .append('|')
          .append(
              destination == null || destination.providerCode() == null
                  ? "-"
                  : destination.providerCode())
          .append('|')
          .append(destination == null || destination.masked() == null ? "-" : destination.masked())
          .append('\n');
    }
    return PackageDigests.sha256(text.toString());
  }

  /**
   * The preview digest of a batch.
   *
   * @param tenantId tenant
   * @param legalEntityId legal entity
   * @param packageId package
   * @param packageVersion package version
   * @param approvalDigest package approval digest
   * @param expectedVersion highest batch version, 0 when none
   * @param destinationsDigest {@link #destinations}
   * @param linesDigest {@link #lines}
   * @param expiresAt expiry
   * @return the digest
   */
  public static String batchPreview(
      UUID tenantId,
      UUID legalEntityId,
      UUID packageId,
      int packageVersion,
      String approvalDigest,
      int expectedVersion,
      String destinationsDigest,
      String linesDigest,
      Instant expiresAt) {
    return PackageDigests.sha256(
        "DIVALHR-PAYMENT-BATCH-PREVIEW\nversion=1\ntenant="
            + tenantId
            + "\nentity="
            + legalEntityId
            + "\npackage="
            + packageId
            + "\npackageVersion="
            + packageVersion
            + "\napprovalDigest="
            + approvalDigest
            + "\nexpectedVersion="
            + expectedVersion
            + "\ndestinations="
            + destinationsDigest
            + "\nlines="
            + linesDigest
            + "\nexpiresAt="
            + expiresAt
            + "\n");
  }

  /**
   * The stored digest of a prepared batch.
   *
   * @param tenantId tenant
   * @param legalEntityId legal entity
   * @param packageId package
   * @param packageVersion package version
   * @param approvalDigest package approval digest
   * @param version batch version
   * @param destinationsDigest {@link #destinations}
   * @param linesDigest {@link #lines}
   * @return the digest
   */
  public static String batch(
      UUID tenantId,
      UUID legalEntityId,
      UUID packageId,
      int packageVersion,
      String approvalDigest,
      int version,
      String destinationsDigest,
      String linesDigest) {
    return PackageDigests.sha256(
        "DIVALHR-PAYMENT-BATCH\nversion=1\ntenant="
            + tenantId
            + "\nentity="
            + legalEntityId
            + "\npackage="
            + packageId
            + "\npackageVersion="
            + packageVersion
            + "\napprovalDigest="
            + approvalDigest
            + "\nbatchVersion="
            + version
            + "\ndestinations="
            + destinationsDigest
            + "\nlines="
            + linesDigest
            + "\n");
  }

  /**
   * The preview digest of a cancellation.
   *
   * @param tenantId tenant
   * @param legalEntityId legal entity
   * @param batchId batch
   * @param batchVersion batch version
   * @param batchDigest stored batch digest
   * @param reason closed reason
   * @param expiresAt expiry
   * @return the digest
   */
  public static String cancelPreview(
      UUID tenantId,
      UUID legalEntityId,
      UUID batchId,
      int batchVersion,
      String batchDigest,
      PaymentBatchCancelReason reason,
      Instant expiresAt) {
    return PackageDigests.sha256(
        "DIVALHR-PAYMENT-BATCH-CANCEL-PREVIEW\nversion=1\ntenant="
            + tenantId
            + "\nentity="
            + legalEntityId
            + "\nbatch="
            + batchId
            + "\nbatchVersion="
            + batchVersion
            + "\nbatchDigest="
            + batchDigest
            + "\nreason="
            + reason.name()
            + "\nexpiresAt="
            + expiresAt
            + "\n");
  }
}
