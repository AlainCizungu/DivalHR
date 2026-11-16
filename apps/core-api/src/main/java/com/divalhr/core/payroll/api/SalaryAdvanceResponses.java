package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.api.CompensationResponses.MoneyView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * MVP-120A response bodies. Every response carries the permanent notice code {@code
 * DIVALHR_NOT_LENDER}. None carries a salary, a reference net, an employment date, a note (except
 * the administrators-only internal note) or any word of lending. Amounts are canonical decimal text
 * with their currency.
 */
public final class SalaryAdvanceResponses {

  /** The permanent responsibility notice code. */
  public static final String NOTICE = "DIVALHR_NOT_LENDER";

  private SalaryAdvanceResponses() {}

  /**
   * One immutable policy version.
   *
   * @param policyId policy
   * @param sequence version in its chain
   * @param currency currency
   * @param code stable code
   * @param effectiveFrom first day in force
   * @param effectiveTo last day in force, or {@code null}
   * @param enabled whether employees may request
   * @param minTenureMonths minimum completed months
   * @param maxPercent whole percent of the reference net
   * @param absoluteMax optional absolute maximum
   * @param minRequest minimum request
   * @param maxOpenRequests open request limit
   * @param waitingDays waiting days
   * @param consentVersion consent document version
   * @param partnerCode synthetic partner code
   * @param status SCHEDULED, IN_FORCE, ENDED or CANCELLED
   * @param createdAt creation instant
   * @param cancelledAt cancellation instant, or {@code null}
   */
  @Schema(name = "SalaryAdvancePolicyEntry")
  public record PolicyEntry(
      UUID policyId,
      int sequence,
      String currency,
      String code,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      boolean enabled,
      int minTenureMonths,
      int maxPercent,
      MoneyView absoluteMax,
      MoneyView minRequest,
      int maxOpenRequests,
      int waitingDays,
      String consentVersion,
      String partnerCode,
      String status,
      Instant createdAt,
      Instant cancelledAt) {}

  /**
   * The last sequence of a chain.
   *
   * @param currency currency
   * @param version last sequence, 0 when none
   */
  @Schema(name = "SalaryAdvanceChainVersion")
  public record ChainVersion(String currency, int version) {}

  /**
   * The policy timeline of a legal entity.
   *
   * @param legalEntityId legal entity
   * @param today the tenant business date
   * @param notice permanent notice code
   * @param versions chain versions
   * @param items every version
   */
  @Schema(name = "SalaryAdvancePolicyView")
  public record PolicyView(
      UUID legalEntityId,
      LocalDate today,
      String notice,
      List<ChainVersion> versions,
      List<PolicyEntry> items) {}

  /**
   * The previewed policy.
   *
   * @param policy the validated command, echoed
   * @param before the chain before
   * @param after the chain after
   * @param blockers closed blocker codes
   * @param version chain version the preview saw
   * @param previewDigest digest, or {@code null} when blocked
   * @param expiresAt expiry, or {@code null} when blocked
   * @param notice permanent notice code
   */
  @Schema(name = "SalaryAdvancePolicyPreview")
  public record PolicyPreview(
      PolicyCommandView policy,
      List<PolicyEntry> before,
      List<PolicyEntry> after,
      List<String> blockers,
      int version,
      String previewDigest,
      Instant expiresAt,
      String notice) {}

  /**
   * The validated policy command, echoed.
   *
   * @param currency currency
   * @param code code
   * @param effectiveFrom first day
   * @param effectiveTo last day, or {@code null}
   * @param enabled enabled
   * @param minTenureMonths minimum months
   * @param maxPercent percent
   * @param absoluteMax optional absolute maximum
   * @param minRequest minimum request
   * @param maxOpenRequests open limit
   * @param waitingDays waiting days
   * @param consentVersion consent version
   * @param partnerCode partner code
   */
  @Schema(name = "SalaryAdvancePolicyCommand")
  public record PolicyCommandView(
      String currency,
      String code,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      boolean enabled,
      int minTenureMonths,
      int maxPercent,
      MoneyView absoluteMax,
      MoneyView minRequest,
      int maxOpenRequests,
      int waitingDays,
      String consentVersion,
      String partnerCode) {}

  /**
   * The receipt of a recorded policy.
   *
   * @param policyId policy
   * @param sequence version
   * @param currency currency
   * @param effectiveFrom first day
   * @param version chain version after
   * @param notice permanent notice code
   */
  @Schema(name = "SalaryAdvancePolicyReceipt")
  public record PolicyReceipt(
      UUID policyId,
      int sequence,
      String currency,
      LocalDate effectiveFrom,
      int version,
      String notice) {}

  /**
   * The canonical consent disclosure as stable codes.
   *
   * @param version version
   * @param contentSha256 digest of the canonical JSON
   * @param purpose purpose code
   * @param shared categories that may be shared in a later story
   * @param notShared categories explicitly not shared
   * @param externalSharing {@code NONE_IN_THIS_STEP}
   * @param withdrawal {@code BEFORE_EXTERNAL_HANDOFF}
   * @param retention {@code NO_DELETION_SCHEDULE_YET}
   */
  @Schema(name = "SalaryAdvanceConsentDocument")
  public record ConsentDocument(
      String version,
      String contentSha256,
      String purpose,
      List<String> shared,
      List<String> notShared,
      String externalSharing,
      String withdrawal,
      String retention) {}

  /**
   * The frozen policy reference of an eligibility entry.
   *
   * @param policyId policy
   * @param sequence version
   * @param maxOpenRequests open limit
   * @param waitingDays waiting days
   * @param consentVersion consent version
   * @param partnerCode partner code
   */
  @Schema(name = "SalaryAdvancePolicyRef")
  public record PolicyRef(
      UUID policyId,
      int sequence,
      int maxOpenRequests,
      int waitingDays,
      String consentVersion,
      String partnerCode) {}

  /**
   * The deterministic employer-policy result for one currency.
   *
   * @param currency currency
   * @param status ELIGIBLE or NOT_ELIGIBLE
   * @param reasons closed reason codes
   * @param policy policy reference, or {@code null}
   * @param referencePeriod {@code YYYY-MM}, or {@code null}
   * @param ceiling employer-policy ceiling, or {@code null}
   * @param minRequest minimum request, or {@code null}
   * @param openRequests open requests
   * @param waitingUntil first day a request is allowed again, or {@code null}
   * @param consent the disclosure to show, or {@code null}
   */
  @Schema(name = "SalaryAdvanceEligibilityEntry")
  public record EligibilityEntry(
      String currency,
      String status,
      List<String> reasons,
      PolicyRef policy,
      String referencePeriod,
      MoneyView ceiling,
      MoneyView minRequest,
      int openRequests,
      LocalDate waitingUntil,
      ConsentDocument consent) {}

  /**
   * The employee's eligibility in both currencies.
   *
   * @param asOf business date
   * @param notice permanent notice code
   * @param items one entry per currency
   */
  @Schema(name = "SalaryAdvanceEligibilityView")
  public record EligibilityView(LocalDate asOf, String notice, List<EligibilityEntry> items) {}

  /**
   * The previewed request.
   *
   * @param eligibility the entry the preview evaluated
   * @param blockers closed blocker codes
   * @param previewDigest digest, or {@code null} when blocked
   * @param expiresAt expiry, or {@code null} when blocked
   * @param notice permanent notice code
   */
  @Schema(name = "SalaryAdvanceRequestPreview")
  public record RequestPreview(
      EligibilityEntry eligibility,
      List<String> blockers,
      String previewDigest,
      Instant expiresAt,
      String notice) {}

  /**
   * One history entry.
   *
   * @param sequence 1 to 3
   * @param fromStatus previous status, or {@code null}
   * @param toStatus new status
   * @param actorRole EMPLOYEE or TENANT_ADMIN
   * @param occurredAt instant
   * @param reasonCode refusal reason, or {@code null}
   */
  @Schema(name = "SalaryAdvanceLifecycleEntry")
  public record LifecycleEntry(
      int sequence,
      String fromStatus,
      String toStatus,
      String actorRole,
      Instant occurredAt,
      String reasonCode) {}

  /**
   * The immutable consent evidence.
   *
   * @param version version
   * @param language en or fr
   * @param consentedAt instant
   * @param disclosureSha256 digest
   */
  @Schema(name = "SalaryAdvanceConsentEvidence")
  public record ConsentEvidence(
      String version, String language, Instant consentedAt, String disclosureSha256) {}

  /**
   * A request in a list.
   *
   * @param requestId request
   * @param status status
   * @param version row version
   * @param currency currency
   * @param requested requested amount
   * @param submittedAt instant
   * @param employeeId employee (administrators only)
   * @param employeeNumber employee number (administrators only)
   * @param notice permanent notice code
   */
  @Schema(name = "SalaryAdvanceRequestSummary")
  public record RequestSummary(
      UUID requestId,
      String status,
      int version,
      String currency,
      MoneyView requested,
      Instant submittedAt,
      UUID employeeId,
      String employeeNumber,
      String notice) {}

  /**
   * A page of requests.
   *
   * @param items the page
   * @param nextCursor opaque cursor, or {@code null}
   */
  @Schema(name = "SalaryAdvanceRequestPage")
  public record RequestPage(List<RequestSummary> items, String nextCursor) {}

  /**
   * One request with its frozen evidence.
   *
   * @param requestId request
   * @param status status
   * @param version row version
   * @param currency currency
   * @param requested requested amount
   * @param ceiling frozen employer-policy ceiling
   * @param referencePeriod {@code YYYY-MM}
   * @param policySequence frozen policy version
   * @param partnerCode synthetic partner code
   * @param tenureMonths completed months at submission
   * @param submittedAt instant
   * @param consent consent evidence
   * @param lifecycle history
   * @param refusalReason refusal reason, or {@code null}
   * @param internalNote administrators-only note; always {@code null} for the employee
   * @param employeeId employee (administrators only)
   * @param employeeNumber employee number (administrators only)
   * @param notice permanent notice code
   */
  @Schema(name = "SalaryAdvanceRequestView")
  public record RequestView(
      UUID requestId,
      String status,
      int version,
      String currency,
      MoneyView requested,
      MoneyView ceiling,
      String referencePeriod,
      int policySequence,
      String partnerCode,
      int tenureMonths,
      Instant submittedAt,
      ConsentEvidence consent,
      List<LifecycleEntry> lifecycle,
      String refusalReason,
      String internalNote,
      UUID employeeId,
      String employeeNumber,
      String notice) {}
}
