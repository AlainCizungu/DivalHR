package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.application.SalaryAdvanceCommands.PolicyDraft;
import com.divalhr.core.payroll.domain.CompensationCurrency;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The stateless preview digests of MVP-120A (ADR 0007, ADR 0041): a SHA-256 over a versioned
 * canonical text. A commit recomputes the digest from the current state; any difference, or an
 * expired preview, is stale. A digest is only compared and hashed, never logged, audited with its
 * input or returned beside an amount other than the one the caller sent.
 */
final class SalaryAdvanceDigests {

  private SalaryAdvanceDigests() {}

  /**
   * The digest of a policy preview: tenant, legal entity, the full policy, the chain version and
   * the expiry.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param draft the policy
   * @param chainVersion the last sequence of the currency chain
   * @param expiresAt the preview expiry
   * @return the digest
   */
  static String policy(
      TenantId tenant, UUID legalEntityId, PolicyDraft draft, int chainVersion, Instant expiresAt) {
    Map<String, Object> values = new TreeMap<>();
    draft.canonical(values);
    StringBuilder text = new StringBuilder("DIVALHR-SALARY-ADVANCE-POLICY\nversion=1\n");
    text.append("tenant=").append(tenant).append('\n');
    text.append("entity=").append(legalEntityId).append('\n');
    values.forEach((key, value) -> text.append(key).append('=').append(value).append('\n'));
    text.append("chain=").append(chainVersion).append('\n');
    text.append("expires=").append(expiresAt).append('\n');
    return Fingerprints.sha256(text.toString());
  }

  /**
   * The digest of a request preview: tenant, employee, the frozen policy, payroll reference and
   * consent version, the amount and the expiry.
   *
   * @param tenant verified tenant
   * @param employeeId the caller's own employee
   * @param currency currency
   * @param amountMinor requested amount
   * @param policyId the policy in force
   * @param policySequence its sequence
   * @param payslipId the payroll reference
   * @param ceilingMinor the ceiling
   * @param consentVersion the consent version
   * @param expiresAt the preview expiry
   * @return the digest
   */
  static String request(
      TenantId tenant,
      UUID employeeId,
      CompensationCurrency currency,
      long amountMinor,
      UUID policyId,
      int policySequence,
      UUID payslipId,
      long ceilingMinor,
      String consentVersion,
      Instant expiresAt) {
    String text =
        "DIVALHR-SALARY-ADVANCE-REQUEST\nversion=1\ntenant="
            + tenant
            + "\nemployee="
            + employeeId
            + "\ncurrency="
            + currency
            + "\namount="
            + amountMinor
            + "\npolicy="
            + policyId
            + ';'
            + policySequence
            + "\npayslip="
            + payslipId
            + "\nceiling="
            + ceilingMinor
            + "\nconsent="
            + consentVersion
            + "\nexpires="
            + expiresAt
            + '\n';
    return Fingerprints.sha256(text);
  }
}
