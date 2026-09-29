package com.divalhr.core.tenant.api;

import com.divalhr.core.tenant.domain.SiteUnit;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Public view of a department; never includes the tenant or the creator.
 *
 * @param id id
 * @param siteId parent site
 * @param code normalized code
 * @param name name
 * @param effectiveFrom first day (inclusive)
 * @param effectiveTo last day (inclusive), or {@code null} when open-ended
 * @param createdAt creation time
 */
@Schema(name = "Department")
public record DepartmentResponse(
    UUID id,
    UUID siteId,
    String code,
    String name,
    LocalDate effectiveFrom,
    @JsonInclude(JsonInclude.Include.ALWAYS) LocalDate effectiveTo,
    Instant createdAt) {

  /**
   * Maps the aggregate.
   *
   * @param unit department
   * @return response
   */
  public static DepartmentResponse from(SiteUnit unit) {
    return new DepartmentResponse(
        unit.id(),
        unit.siteId(),
        unit.code(),
        unit.name(),
        unit.period().from(),
        unit.period().to(),
        unit.createdAt());
  }
}
