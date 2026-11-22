package com.divalhr.core.integrations.partner.catalogue;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Request and response bodies of partner accounts and the product catalogue (MVP-120E). Names match
 * the contract schemas. No body carries contact data, a licence or registration reference, a note,
 * or an internal identifier of a tenant or employee read.
 */
public final class PartnerAccountBodies {

  private PartnerAccountBodies() {}

  /** A text in both languages. */
  @Schema(name = "PartnerBilingualText")
  public record Bilingual(String en, String fr) {}

  /** A currency with optional limits (decimal strings). */
  @Schema(name = "PartnerProductCurrencyLimit")
  public record CurrencyLimit(String currency, String minAmount, String maxAmount) {}

  /** Editable content of a product version. */
  @Schema(name = "PartnerProductContent")
  public record Content(
      Bilingual name,
      Bilingual summary,
      Bilingual eligibility,
      Bilingual disclosure,
      String infoUrl,
      LocalDate effectiveDate,
      LocalDate endDate,
      Integer minMonths,
      Integer maxMonths,
      List<String> countries,
      List<CurrencyLimit> currencies) {}

  /** An approved application and its partner. */
  @Schema(name = "PartnerReadyApplication")
  public record ReadyApplication(
      String applicationReference,
      String legalName,
      String tradingName,
      String countryOfRegistration,
      String primaryCategory,
      Instant approvedAt,
      String partnerReference) {}

  /** Page of ready applications. */
  @Schema(name = "PartnerReadyApplicationPage")
  public record ReadyApplicationPage(List<ReadyApplication> items, String nextCursor) {}

  /** Partner list entry. */
  @Schema(name = "PartnerSummary")
  public record PartnerSummary(
      String reference,
      String status,
      String legalName,
      String tradingName,
      String countryOfRegistration,
      String primaryCategory,
      int productCount,
      int activeProductCount,
      Instant createdAt,
      Instant updatedAt) {}

  /** Page of partners. */
  @Schema(name = "PartnerPage")
  public record PartnerPage(
      List<PartnerSummary> items, String nextCursor, Map<String, Integer> statusCounts) {}

  /** One partner. */
  @Schema(name = "PartnerDetail")
  public record PartnerDetail(
      String reference,
      String applicationReference,
      String status,
      int version,
      String legalName,
      String tradingName,
      String countryOfRegistration,
      String primaryCategory,
      List<String> additionalCategories,
      List<String> capabilities,
      String website,
      List<String> supportedLanguages,
      List<String> countries,
      List<String> allowedActions,
      int productCount,
      int activeProductCount,
      Instant createdAt,
      Instant updatedAt) {}

  /** Receipt of a partner creation. */
  @Schema(name = "PartnerCreateReceipt")
  public record CreateReceipt(boolean created, PartnerDetail partner) {}

  /** Receipt of a partner transition. */
  @Schema(name = "PartnerTransitionReceipt")
  public record TransitionReceipt(PartnerDetail partner, int eventSequence) {}

  /** Partner history entry. */
  @Schema(name = "PartnerHistoryEntry")
  public record HistoryEntry(
      int sequence,
      String eventType,
      String fromStatus,
      String toStatus,
      String reasonCode,
      boolean noteRecorded,
      Integer itemCount,
      String actorType,
      Instant occurredAt) {}

  /** Page of partner history. */
  @Schema(name = "PartnerHistoryPage")
  public record HistoryPage(List<HistoryEntry> items, String nextCursor) {}

  /** Product list entry. */
  @Schema(name = "PartnerProductSummary")
  public record ProductSummary(
      String productCode,
      String productType,
      String publicReference,
      int latestVersion,
      String latestStatus,
      Integer liveVersion,
      String liveStatus,
      Bilingual name) {}

  /** Products of a partner. */
  @Schema(name = "PartnerProductList")
  public record ProductList(List<ProductSummary> items) {}

  /** One product version. */
  @Schema(name = "PartnerProductVersion")
  public record ProductVersion(
      int version,
      String status,
      int rowVersion,
      Content content,
      List<String> allowedActions,
      Instant createdAt,
      Instant updatedAt) {}

  /** One product with all versions. */
  @Schema(name = "PartnerProductDetail")
  public record ProductDetail(
      String partnerReference,
      String partnerStatus,
      String productCode,
      String productType,
      String publicReference,
      List<ProductVersion> versions) {}

  /** Receipt of a product transition. */
  @Schema(name = "PartnerProductTransitionReceipt")
  public record ProductTransitionReceipt(ProductDetail product, Integer supersededVersion) {}

  /** Product history entry. */
  @Schema(name = "PartnerProductHistoryEntry")
  public record ProductHistoryEntry(
      int sequence,
      int version,
      String eventType,
      String fromStatus,
      String toStatus,
      String reasonCode,
      boolean noteRecorded,
      String actorType,
      Instant occurredAt) {}

  /** Page of product history. */
  @Schema(name = "PartnerProductHistoryPage")
  public record ProductHistoryPage(List<ProductHistoryEntry> items, String nextCursor) {}

  /** Safe catalogue partner. */
  @Schema(name = "CataloguePartner")
  public record CataloguePartner(String displayName, List<String> categories, int productCount) {}

  /** Safe catalogue partners. */
  @Schema(name = "CataloguePartnerList")
  public record CataloguePartnerList(
      List<CataloguePartner> items, String availability, boolean informationOnly) {}

  /** Safe catalogue product. */
  @Schema(name = "CatalogueProduct")
  public record CatalogueProduct(
      String publicReference,
      String partnerDisplayName,
      String productType,
      Bilingual name,
      Bilingual summary,
      Bilingual eligibility,
      Bilingual disclosure,
      String infoUrl,
      LocalDate effectiveDate,
      LocalDate endDate,
      Integer minMonths,
      Integer maxMonths,
      List<CurrencyLimit> currencies,
      boolean informationOnly) {}

  /** Safe catalogue product page. */
  @Schema(name = "CatalogueProductPage")
  public record CatalogueProductPage(
      List<CatalogueProduct> items,
      String nextCursor,
      String availability,
      boolean informationOnly) {}

  /** Body of a partner creation. */
  @Schema(name = "PartnerCreateCommand")
  public record CreateCommand(Boolean confirmed) {}

  /** Body of a partner transition. */
  @Schema(name = "PartnerTransitionCommand")
  public record TransitionCommand(
      String action,
      Integer expectedVersion,
      String reasonCode,
      String note,
      Boolean confirmed,
      List<String> countries) {}

  /** Body of a product creation. */
  @Schema(name = "PartnerProductCreateCommand")
  public record ProductCreateCommand(String productCode, String productType, Content content) {}

  /** Body of a draft save. */
  @Schema(name = "PartnerProductDraftCommand")
  public record ProductDraftCommand(
      Integer expectedVersion, Integer expectedRowVersion, Content content) {}

  /** Body of a product transition. */
  @Schema(name = "PartnerProductTransitionCommand")
  public record ProductTransitionCommand(
      String action,
      Integer version,
      Integer expectedRowVersion,
      String reasonCode,
      String note,
      Boolean confirmed) {}
}
