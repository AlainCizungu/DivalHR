package com.divalhr.core.integrations.partner.referral;

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
}
