package com.divalhr.core.tenant.api;

import com.divalhr.core.tenant.domain.LegalEntity;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Response body mirroring {@code LegalEntity}; never includes the tenant or the author.
 *
 * @param id id
 * @param code code
 * @param name name
 * @param countryCode country
 * @param effectiveFrom first effective day
 * @param effectiveTo last effective day, or {@code null} when open-ended
 * @param createdAt UTC creation time
 */
@Schema(name = "LegalEntity")
public record LegalEntityResponse(
    UUID id,
    String code,
    String name,
    String countryCode,
    LocalDate effectiveFrom,
    @JsonInclude(JsonInclude.Include.ALWAYS) LocalDate effectiveTo,
    Instant createdAt) {

  /**
   * Maps the aggregate.
   *
   * @param entity aggregate
   * @return response
   */
  public static LegalEntityResponse from(LegalEntity entity) {
    return new LegalEntityResponse(
        entity.id(),
        entity.code(),
        entity.name(),
        entity.countryCode(),
        entity.period().from(),
        entity.period().to(),
        entity.createdAt());
  }
}
