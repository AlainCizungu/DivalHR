package com.divalhr.core.payroll.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * The preview digest of a provider submission (MVP-095B, D95B-5): the SHA-256 of a fixed-order text
 * binding the tenant, entity, batch version and digest, the provider profile, the counts, the total
 * and the expiry. It contains no destination value, so it is never an offline-guessing oracle.
 */
public final class PaymentSubmissionDigests {

  private PaymentSubmissionDigests() {}

  /**
   * The preview digest.
   *
   * @param tenantId tenant
   * @param legalEntityId legal entity
   * @param batchId batch
   * @param batchVersion batch version
   * @param batchDigest batch digest
   * @param providerCode provider profile code
   * @param keyVersion signing key version
   * @param lineCount lines
   * @param currency currency
   * @param totalMinor total net minor units
   * @param expiresAt expiry
   * @return the digest
   */
  public static String preview(
      UUID tenantId,
      UUID legalEntityId,
      UUID batchId,
      int batchVersion,
      String batchDigest,
      String providerCode,
      int keyVersion,
      int lineCount,
      String currency,
      long totalMinor,
      Instant expiresAt) {
    return PackageDigests.sha256(
        "DIVALHR-PAYMENT-SUBMISSION-PREVIEW\nversion=1\ntenant="
            + tenantId
            + "\nentity="
            + legalEntityId
            + "\nbatch="
            + batchId
            + "\nbatchVersion="
            + batchVersion
            + "\nbatchDigest="
            + batchDigest
            + "\nprovider="
            + providerCode
            + "\nkeyVersion="
            + keyVersion
            + "\nlines="
            + lineCount
            + "\ncurrency="
            + currency
            + "\ntotal="
            + totalMinor
            + "\nexpiresAt="
            + expiresAt
            + "\n");
  }
}
