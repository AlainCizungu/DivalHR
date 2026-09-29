package com.divalhr.core.tenant.api;

import com.divalhr.core.tenant.domain.Site;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Response body mirroring {@code Site}; never includes the tenant or the author.
 *
 * @param id id
 * @param legalEntityId parent legal entity
 * @param code code
 * @param name name
 * @param timezone time zone
 * @param effectiveFrom first effective day
 * @param effectiveTo last effective day, or {@code null} when open-ended
 * @param createdAt UTC creation time
 */
@Schema(name = "Site")
public record SiteResponse(
    UUID id,
    UUID legalEntityId,
    String code,
    String name,
    String timezone,
    LocalDate effectiveFrom,
    @JsonInclude(JsonInclude.Include.ALWAYS) LocalDate effectiveTo,
    Instant createdAt) {

  /**
   * Maps the aggregate.
   *
   * @param site aggregate
   * @return response
   */
  public static SiteResponse from(Site site) {
    return new SiteResponse(
        site.id(),
        site.legalEntityId(),
        site.code(),
        site.name(),
        site.timezone(),
        site.period().from(),
        site.period().to(),
        site.createdAt());
  }
}
