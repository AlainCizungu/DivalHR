package com.divalhr.core.integrations.partner.privacy;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Response bodies of the LAUNCH-001 privacy API. No personal value is ever carried. */
public final class PrivacyBodies {

  private PrivacyBodies() {}

  /** One rule for one data class. */
  @Schema(name = "PrivacyPolicyRule")
  public record Rule(
      String dataClass, String purpose, String action, String trigger, Integer durationDays) {}

  /** The governance checklist. */
  @Schema(name = "PrivacyPolicyChecklist")
  public record Checklist(
      boolean jurisdictionIdentified,
      boolean sourceReferenceRecorded,
      boolean counselReviewRecorded,
      boolean dataClassesCovered,
      boolean lifecycleTriggerDefined,
      boolean durationsOrHoldSet,
      boolean employeeWordingVersionRecorded,
      boolean rollbackReviewComplete,
      String reviewReference) {

    /** Whether every item is true and the review reference is recorded. */
    boolean complete() {
      return jurisdictionIdentified
          && sourceReferenceRecorded
          && counselReviewRecorded
          && dataClassesCovered
          && lifecycleTriggerDefined
          && durationsOrHoldSet
          && employeeWordingVersionRecorded
          && rollbackReviewComplete
          && reviewReference != null
          && !reviewReference.isBlank();
    }
  }

  /** A policy version. */
  @Schema(name = "PrivacyPolicyView")
  public record PolicyView(
      String id,
      String countryCode,
      String state,
      int version,
      String wordingVersion,
      String sourceReference,
      LocalDate reviewDate,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      List<Rule> rules,
      Checklist checklist,
      Instant createdAt,
      Instant updatedAt) {}

  /** A page of policy versions. */
  @Schema(name = "PrivacyPolicyPage")
  public record PolicyPage(List<PolicyView> items, String nextCursor) {}

  /** One policy history event. */
  @Schema(name = "PrivacyPolicyEvent")
  public record PolicyEvent(int sequence, String fromState, String toState, Instant occurredAt) {}

  /** The append-only policy history. */
  @Schema(name = "PrivacyPolicyHistory")
  public record PolicyHistory(String policyId, List<PolicyEvent> events) {}

  /** One request history event. */
  @Schema(name = "PrivacyRequestEvent")
  public record RequestEvent(
      int sequence, String fromState, String toState, String reasonCode, Instant occurredAt) {}

  /** An employee's own request. */
  @Schema(name = "PrivacyRequestView")
  public record RequestView(
      String id,
      String requestType,
      String referralId,
      String state,
      int version,
      String decisionReason,
      String employeeMessage,
      Instant submittedAt,
      Instant updatedAt,
      boolean exportAvailable,
      List<RequestEvent> history) {}

  /** A page of an employee's own requests. */
  @Schema(name = "PrivacyRequestPage")
  public record RequestPage(List<RequestView> items, String nextCursor) {}

  /** A hold on a referral. The note is never returned; only whether one was recorded. */
  @Schema(name = "PrivacyHoldView")
  public record HoldView(
      String id,
      String referralId,
      String reasonCode,
      LocalDate reviewDate,
      String externalReference,
      boolean noteRecorded,
      String state,
      Instant placedAt,
      Instant releasedAt) {}

  /**
   * A request as platform privacy operations see it: masked contact, no note, no private choice.
   */
  @Schema(name = "PlatformPrivacyRequest")
  public record PlatformRequest(
      String id,
      String requestType,
      String state,
      int version,
      java.util.UUID organizationId,
      String referralId,
      String contactMasked,
      String decisionReason,
      String employeeMessage,
      boolean notePresent,
      Instant submittedAt,
      Instant updatedAt,
      List<RequestEvent> history,
      List<HoldView> holds) {}

  /** A page of requests for platform privacy operations. */
  @Schema(name = "PlatformPrivacyRequestPage")
  public record PlatformRequestPage(List<PlatformRequest> items, String nextCursor) {}

  /** One country of a policy-application preview. */
  @Schema(name = "PrivacyPolicyApplicationPreviewItem")
  public record ApplicationItem(String countryCode, String policyId, int pendingReferrals) {}

  /** What assigning the ACTIVE policies would do. Nothing is changed by a preview. */
  @Schema(name = "PrivacyPolicyApplicationPreview")
  public record ApplicationPreview(
      String previewId,
      String previewDigest,
      Instant expiresAt,
      int totalPending,
      List<ApplicationItem> items) {}

  /** The evidence of a policy application. */
  @Schema(name = "PrivacyPolicyApplicationResult")
  public record ApplicationResult(
      String applicationId, String previewId, int applied, Instant completedAt) {}

  /** Whether one data class of a referral is eligible, with one bounded refusal. */
  @Schema(name = "PrivacyPurgeClassDecision")
  public record ClassDecision(String dataClass, boolean eligible, String refusal) {}

  /** One referral of a purge preview. */
  @Schema(name = "PrivacyPurgePreviewItem")
  public record PurgePreviewItem(
      String referralId, List<ClassDecision> classes, List<String> holdIds) {}

  /** What a confirmation would do, with no personal value. */
  @Schema(name = "PrivacyPurgePreview")
  public record PurgePreview(
      String previewId,
      String previewDigest,
      Instant expiresAt,
      String action,
      int eligibleClasses,
      List<PurgePreviewItem> items) {}

  /** The outcome of one purged class. */
  @Schema(name = "PrivacyPurgeClassResult")
  public record PurgeClassResult(String dataClass, String outcome, int rows) {}

  /** The outcome for one referral. */
  @Schema(name = "PrivacyPurgeItemResult")
  public record PurgeItemResult(String referralId, List<PurgeClassResult> classes) {}

  /** The immutable, value-free evidence of a purge. */
  @Schema(name = "PrivacyPurgeResult")
  public record PurgeResult(
      String purgeId,
      String previewId,
      String action,
      Instant completedAt,
      List<PurgeItemResult> items) {}
}
