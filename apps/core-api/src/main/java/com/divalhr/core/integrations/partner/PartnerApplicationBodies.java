package com.divalhr.core.integrations.partner;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Request and response bodies of the partner-application API (MVP-120D). Property names equal the
 * design contract exactly ({@code ApiContractDriftTest}). Responses never carry an internal id.
 */
public final class PartnerApplicationBodies {

  private PartnerApplicationBodies() {}

  /**
   * Documentation shape of the anonymous submission body. The controller reads the raw bytes and
   * validates them strictly, so this type exists only to publish the schema.
   */
  @Schema(name = "PartnerApplicationSubmission")
  public record Submission(
      String legalName,
      String tradingName,
      String countryOfRegistration,
      List<String> countriesOfOperation,
      String registrationReference,
      String primaryCategory,
      List<String> additionalCategories,
      List<String> requestedCapabilities,
      String website,
      String contactName,
      String contactRole,
      String businessEmail,
      String businessPhone,
      List<String> supportedLanguages,
      String apiAvailability,
      String regulatorName,
      String licenceReference,
      String serviceSummary,
      String privacyContactEmail,
      Boolean acknowledgment,
      String privacyNoticeVersion,
      String consentVersion,
      String locale,
      String faxNumber) {}

  /** The safe receipt of a submission. */
  @Schema(name = "PartnerApplicationReceipt")
  public record Receipt(String reference, Instant submittedAt, String status, String nextStep) {}

  /** One queue row. */
  @Schema(name = "PartnerApplicationSummary")
  public record Summary(
      String reference,
      String status,
      int version,
      String primaryCategory,
      List<String> additionalCategories,
      List<String> requestedCapabilities,
      String legalName,
      String countryOfRegistration,
      Instant submittedAt,
      Instant updatedAt) {}

  /** A page of the queue. */
  @Schema(name = "PartnerApplicationPage")
  public record Page(List<Summary> items, String nextCursor, Map<String, Integer> statusCounts) {}

  /** Confidential contact block. */
  @Schema(name = "PartnerApplicationContact")
  public record Contact(
      String contactName,
      String businessEmail,
      String businessPhone,
      String registrationReference,
      String licenceReference,
      String privacyContactEmail) {}

  /** Consent versions. */
  @Schema(name = "PartnerApplicationConsent")
  public record Consent(
      String privacyNoticeVersion, String consentVersion, String locale, Instant acceptedAt) {}

  /** One checklist entry. */
  @Schema(name = "PartnerApplicationCheck")
  public record Check(
      String checkCode,
      boolean mandatory,
      String result,
      int version,
      Boolean performedByCaller,
      Instant updatedAt,
      String note) {}

  /** One application in detail. */
  @Schema(name = "PartnerApplicationDetail")
  public record Detail(
      String reference,
      String status,
      int version,
      String primaryCategory,
      List<String> additionalCategories,
      List<String> requestedCapabilities,
      String legalName,
      String tradingName,
      String countryOfRegistration,
      List<String> countriesOfOperation,
      String website,
      String contactRole,
      List<String> supportedLanguages,
      String apiAvailability,
      String regulatorName,
      String serviceSummary,
      String submissionLocale,
      Instant submittedAt,
      Instant updatedAt,
      Contact contact,
      Consent consent,
      List<Check> checklist,
      List<String> allowedTransitions,
      List<String> approvalBlockedBy,
      int possibleDuplicates) {}

  /** One history entry. */
  @Schema(name = "PartnerApplicationHistoryEntry")
  public record HistoryEntry(
      int sequence,
      String eventType,
      String fromStatus,
      String toStatus,
      String checkCode,
      String checkResult,
      String reasonCode,
      String actorType,
      Boolean performedByCaller,
      Instant occurredAt) {}

  /** A page of history. */
  @Schema(name = "PartnerApplicationHistoryPage")
  public record HistoryPage(List<HistoryEntry> items, String nextCursor) {}

  /** Body of a check update. */
  @Schema(name = "PartnerApplicationCheckCommand")
  public record CheckCommand(String result, Integer expectedVersion, String note) {}

  /** Body of a status transition. */
  @Schema(name = "PartnerApplicationTransitionCommand")
  public record TransitionCommand(
      String toStatus, Integer expectedVersion, String reasonCode, Boolean confirmed) {}

  /** Receipt of a transition. */
  @Schema(name = "PartnerApplicationTransitionReceipt")
  public record TransitionReceipt(
      String reference, String status, int version, int eventSequence, Instant occurredAt) {}
}
