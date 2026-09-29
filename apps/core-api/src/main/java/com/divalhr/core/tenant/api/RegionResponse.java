package com.divalhr.core.tenant.api;

import com.divalhr.core.tenant.domain.Region;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Response body mirroring {@code Region}; never includes the tenant or the author.
 *
 * @param id id
 * @param legalEntityId parent legal entity
 * @param code code
 * @param name name
 * @param effectiveFrom first effective day
 * @param effectiveTo last effective day, or {@code null} when open-ended
 * @param createdAt UTC creation time
 */
@Schema(name = "Region")
public record RegionResponse(
    UUID id,
    UUID legalEntityId,
    String code,
    String name,
    LocalDate effectiveFrom,
    @JsonInclude(JsonInclude.Include.ALWAYS) LocalDate effectiveTo,
    Instant createdAt) {

  /**
   * Maps the aggregate.
   *
   * @param region aggregate
   * @return response
   */
  public static RegionResponse from(Region region) {
    return new RegionResponse(
        region.id(),
        region.legalEntityId(),
        region.code(),
        region.name(),
        region.period().from(),
        region.period().to(),
        region.createdAt());
  }
}
