package com.divalhr.core.payroll.application;

import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.util.UUID;

/**
 * The stateless preview digest of MVP-120B (ADR 0042 §3): a SHA-256 over a versioned canonical text
 * binding the tenant, the caller's own employee, the request and its version, the handoff consent
 * version and digest, the payload digest and the expiry. A commit recomputes it from the current
 * state; any difference, or an expired preview, is stale. The digest is only compared, never
 * logged, and never carries an amount in clear text.
 */
final class SalaryAdvanceHandoffDigests {

  private SalaryAdvanceHandoffDigests() {}

  /**
   * The preview digest.
   *
   * @param tenant verified tenant
   * @param employeeId the caller's own employee
   * @param requestId the employer-confirmed request
   * @param requestVersion its row version
   * @param consentVersion the handoff consent version
   * @param consentSha256 the handoff consent digest
   * @param payloadSha256 the digest of the canonical payload shape (amount-bound, application id
   *     excluded)
   * @param expiresAt the preview expiry
   * @return the digest
   */
  static String preview(
      TenantId tenant,
      UUID employeeId,
      UUID requestId,
      int requestVersion,
      String consentVersion,
      String consentSha256,
      String payloadSha256,
      Instant expiresAt) {
    String text =
        "DIVALHR-SALARY-ADVANCE-HANDOFF\nversion=1\ntenant="
            + tenant
            + "\nemployee="
            + employeeId
            + "\nrequest="
            + requestId
            + ";"
            + requestVersion
            + "\nconsent="
            + consentVersion
            + ";"
            + consentSha256
            + "\npayload="
            + payloadSha256
            + "\nexpires="
            + expiresAt
            + '\n';
    return Fingerprints.sha256(text);
  }
}
