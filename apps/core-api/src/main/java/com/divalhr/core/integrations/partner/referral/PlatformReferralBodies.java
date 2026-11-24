package com.divalhr.core.integrations.partner.referral;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Response bodies of the read-only platform referral operations (MVP-120F, D120F-5). */
public final class PlatformReferralBodies {

  private PlatformReferralBodies() {}

  /** The employee's contact fields, masked. No name, no free text. */
  @Schema(name = "PlatformPartnerReferralContact")
  public record Contact(String emailMasked, String phoneMasked) {}

  /** The operational view of one referral. */
  @Schema(name = "PlatformPartnerReferral")
  public record Referral(
      String referralId,
      UUID organizationId,
      String partnerDisplayName,
      String productType,
      List<String> categories,
      String state,
      int version,
      Instant createdAt,
      Instant updatedAt,
      String lastOutcome,
      int attemptCount,
      Contact contact) {}

  /** A page of the operational queue. */
  @Schema(name = "PlatformPartnerReferralPage")
  public record Page(List<Referral> items, String nextCursor) {}

  /** The append-only history of one referral. */
  @Schema(name = "PartnerReferralHistory")
  public record History(String referralId, List<PartnerReferralBodies.Event> events) {}
}
