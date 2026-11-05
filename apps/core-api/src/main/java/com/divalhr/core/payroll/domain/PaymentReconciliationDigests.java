package com.divalhr.core.payroll.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * The preview digest and the request-ID fingerprint of a reconciliation (MVP-095C, ADR 0038 section
 * 8). Neither contains a destination, an amount or the raw request ID.
 */
public final class PaymentReconciliationDigests {

  private PaymentReconciliationDigests() {}

  /**
   * The preview digest: SHA-256 of a fixed-order text binding the tenant, entity, batch,
   * submission, its version and state, the provider and key version and the expiry.
   *
   * @param tenantId tenant
   * @param legalEntityId legal entity
   * @param batchId batch
   * @param submissionId submission
   * @param submissionVersion submission version
   * @param submissionState submission state
   * @param providerCode provider profile code
   * @param keyVersion signing key version
   * @param expiresAt expiry
   * @return the digest
   */
  public static String preview(
      UUID tenantId,
      UUID legalEntityId,
      UUID batchId,
      UUID submissionId,
      int submissionVersion,
      String submissionState,
      String providerCode,
      int keyVersion,
      Instant expiresAt) {
    return PackageDigests.sha256(
        "DIVALHR-PAYMENT-RECONCILIATION-PREVIEW\nversion=1\ntenant="
            + tenantId
            + "\nentity="
            + legalEntityId
            + "\nbatch="
            + batchId
            + "\nsubmission="
            + submissionId
            + "\nsubmissionVersion="
            + submissionVersion
            + "\nsubmissionState="
            + submissionState
            + "\nprovider="
            + providerCode
            + "\nkeyVersion="
            + keyVersion
            + "\nexpiresAt="
            + expiresAt
            + "\n");
  }

  /**
   * A short, non-reversible display identifier of the stable payment request ID: the first 12 hex
   * characters of the SHA-256 of a domain-separated text.
   *
   * @param requestId the stable request ID
   * @return 12 lower-case hex characters
   */
  public static String requestFingerprint(UUID requestId) {
    return PackageDigests.sha256(
            "DIVALHR-PAYMENT-REQUEST-FINGERPRINT\nversion=1\n" + requestId + "\n")
        .substring(0, 12);
  }
}
