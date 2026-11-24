package com.divalhr.core.integrations.partner.referral;

import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.Bilingual;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.CatalogueProduct;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** Response bodies of the consented partner referral API (MVP-120F). */
public final class PartnerReferralBodies {

  /** Always true: a referral is a consented introduction only (D120F-8). */
  public static final Notice NOTICE = new Notice(true, true, true);

  private PartnerReferralBodies() {}

  /** Body of a preview (documentation of the strict contract; parsed from the raw request). */
  @Schema(name = "PartnerReferralPreviewRequest")
  public record PreviewRequest(
      String productId,
      String language,
      boolean includePhone,
      String phone,
      boolean includeOrganizationName) {}

  /** The notice every referral view carries. */
  @Schema(name = "PartnerReferralNotice")
  public record Notice(boolean referralOnly, boolean noDecision, boolean noMoneyMovement) {}

  /** An eligible product with its partner categories. */
  @Schema(name = "PartnerReferralProduct")
  public record Product(CatalogueProduct product, List<String> categories, boolean referralOpen) {}

  /** A page of eligible products. */
  @Schema(name = "PartnerReferralProductPage")
  public record ProductPage(List<Product> items, String nextCursor, String availability) {}

  /** The consent wording version. */
  @Schema(name = "PartnerReferralConsentDocument")
  public record ConsentDocument(String version, String language) {}

  /** One field that would be disclosed. */
  @Schema(name = "PartnerReferralDisclosedValue")
  public record DisclosedValue(
      String field, String source, String purpose, String value, boolean assignedAtSubmission) {}

  /** Exactly what would be disclosed. */
  @Schema(name = "PartnerReferralPreview")
  public record Preview(
      String productId,
      String partnerDisplayName,
      String productType,
      List<String> categories,
      String language,
      ConsentDocument consent,
      List<DisclosedValue> fields,
      List<String> neverShared,
      List<String> blockers,
      String previewDigest,
      Instant expiresAt,
      Notice notice) {}

  /** The consent affirmation of a submission. */
  @Schema(name = "PartnerReferralConsentAffirmation")
  public record ConsentAffirmation(String version, boolean affirmed) {}

  /** Body of a submission (documentation of the strict contract; parsed from the raw request). */
  @Schema(name = "SubmitPartnerReferral")
  public record SubmitRequest(
      String productId,
      String language,
      boolean includePhone,
      String phone,
      boolean includeOrganizationName,
      String previewDigest,
      Instant expiresAt,
      ConsentAffirmation consent) {}

  /** Body of a withdrawal (documentation of the strict contract; parsed from the raw request). */
  @Schema(name = "WithdrawPartnerReferral")
  public record WithdrawRequest(int expectedVersion, boolean confirmed) {}

  /** One append-only lifecycle event. */
  @Schema(name = "PartnerReferralEvent")
  public record Event(
      int sequence,
      String fromState,
      String toState,
      String actorRole,
      String outcome,
      Instant occurredAt) {}

  /** A referral in a list. */
  @Schema(name = "PartnerReferralSummary")
  public record Summary(
      String referralId,
      String state,
      int version,
      boolean withdrawable,
      String productId,
      Bilingual productName,
      String partnerDisplayName,
      String productType,
      List<String> categories,
      Instant createdAt,
      Instant updatedAt) {}

  /** The employee's own referral. */
  @Schema(name = "PartnerReferralView")
  public record View(
      String referralId,
      String state,
      int version,
      boolean withdrawable,
      String productId,
      Bilingual productName,
      String partnerDisplayName,
      String productType,
      List<String> categories,
      String language,
      Instant createdAt,
      Instant updatedAt,
      String lastOutcome,
      String consentVersion,
      List<String> disclosedFields,
      List<Event> events,
      Notice notice) {}

  /** A page of the employee's referrals. */
  @Schema(name = "PartnerReferralPage")
  public record Page(List<Summary> items, String nextCursor) {}
}
