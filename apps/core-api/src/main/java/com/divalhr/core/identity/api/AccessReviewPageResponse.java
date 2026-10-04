package com.divalhr.core.identity.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A page of the access review (MVP-012B). Mirrors {@code AccessReviewPage} in {@code
 * docs/API-SPEC.yaml}. Contains confidential addresses: served only with {@code Cache-Control:
 * private, no-store} and never logged.
 *
 * @param data entries
 * @param nextCursor continuation, omitted on the last page
 */
@Schema(name = "AccessReviewPage")
public record AccessReviewPageResponse(
    List<Entry> data, @JsonInclude(JsonInclude.Include.NON_NULL) String nextCursor) {

  /** Defensively copies the entries. */
  public AccessReviewPageResponse {
    data = List.copyOf(data);
  }

  /**
   * One membership: active, or (MVP-022) revoked by a separation.
   *
   * @param membershipId membership
   * @param email confidential address, or null when not recorded
   * @param role tenant role
   * @param grantedAt membership creation
   * @param accessState {@code ACTIVE} or {@code REVOKED}
   * @param directScope granted scope (TENANT only until story S1)
   * @param effectiveScope effective scope
   * @param matchedUnit the filtered unit, or null
   */
  @Schema(name = "AccessReviewEntry")
  public record Entry(
      UUID membershipId,
      @JsonInclude(JsonInclude.Include.ALWAYS) String email,
      String role,
      Instant grantedAt,
      String accessState,
      DirectScope directScope,
      EffectiveScope effectiveScope,
      @JsonInclude(JsonInclude.Include.ALWAYS) MatchedUnit matchedUnit) {}

  /**
   * Direct scope.
   *
   * @param type {@code TENANT}
   */
  @Schema(name = "AccessReviewDirectScope")
  public record DirectScope(String type) {

    /** The only direct scope before story S1. */
    public static final DirectScope TENANT = new DirectScope("TENANT");
  }

  /**
   * Effective scope.
   *
   * @param type {@code TENANT}
   * @param coversAllLegalEntitiesAndSites always true for a tenant-wide membership
   */
  @Schema(name = "AccessReviewEffectiveScope")
  public record EffectiveScope(String type, boolean coversAllLegalEntitiesAndSites) {

    /** Organization-wide access. */
    public static final EffectiveScope TENANT = new EffectiveScope("TENANT", true);
  }

  /**
   * The unit the request filtered by.
   *
   * @param type {@code LEGAL_ENTITY} or {@code SITE}
   * @param id unit id
   * @param inheritance {@code INHERITED_FROM_TENANT}
   */
  @Schema(name = "AccessReviewMatchedUnit")
  public record MatchedUnit(String type, UUID id, String inheritance) {}
}
