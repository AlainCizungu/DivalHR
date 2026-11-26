package com.divalhr.core.integrations.partner.privacy;

import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.Checklist;
import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.Rule;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/**
 * The request bodies of the privacy contract, declared once for the OpenAPI drift test. The
 * services parse the raw body strictly themselves (unknown fields, wrong types and lengths are
 * refused with field-only problems); these records describe the same shapes and are never bound by
 * Jackson.
 */
final class PrivacyCommandBodies {

  private PrivacyCommandBodies() {}

  /** An employee request. */
  @Schema(name = "CreatePrivacyRequest")
  record CreateRequest(String requestType, String referralId) {}

  /** Cancels an employee request. */
  @Schema(name = "CancelPrivacyRequest")
  record CancelRequest(int expectedVersion) {}

  /** Creates a policy version. */
  @Schema(name = "CreatePrivacyPolicy")
  record CreatePolicy(
      String countryCode,
      String wordingVersion,
      String sourceReference,
      String reviewDate,
      String effectiveFrom,
      String effectiveTo,
      List<Rule> rules) {}

  /** Replaces the checklist. */
  @Schema(name = "PutPrivacyPolicyChecklist")
  record PutChecklist(int expectedVersion, Checklist checklist) {}

  /** Moves a policy. */
  @Schema(name = "TransitionPrivacyPolicy")
  record TransitionPolicy(int expectedVersion, String toState) {}

  /** Moves a request. */
  @Schema(name = "TransitionPrivacyRequest")
  record TransitionRequest(
      int expectedVersion,
      String toState,
      String reasonCode,
      String employeeMessage,
      String internalNote) {}

  /** Places a hold. */
  @Schema(name = "PlacePrivacyHold")
  record PlaceHold(
      String reasonCode, String reviewDate, String externalReference, String internalNote) {}

  /** Releases a hold. */
  @Schema(name = "ReleasePrivacyHold")
  record ReleaseHold(String internalNote) {}

  /** Previews a policy application. */
  @Schema(name = "PreviewPrivacyPolicyApplication")
  record PreviewApplication(String countryCode) {}

  /** Confirms a preview. */
  @Schema(name = "CommitPrivacyOperation")
  record Commit(String previewId, String previewDigest) {}

  /** Previews a purge. */
  @Schema(name = "PreviewPrivacyPurge")
  record PreviewPurge(List<String> referralIds, String action) {}

  /** The access package, described for the contract. */
  @Schema(name = "PrivacyAccessPackage")
  record AccessPackage(
      Map<String, Object> manifest,
      List<Map<String, Object>> referrals,
      List<Map<String, Object>> requests) {}
}
