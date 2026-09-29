package com.divalhr.core.tenant.api;

import com.divalhr.core.tenant.domain.Team;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Response body mirroring {@code Team}; never includes the tenant or the author. Both parent ids
 * are always serialized and exactly one is non-null.
 *
 * @param id id
 * @param siteId the parent's site (derived)
 * @param departmentId parent department, or {@code null}
 * @param costCenterId parent cost center, or {@code null}
 * @param code code
 * @param name name
 * @param effectiveFrom first effective day
 * @param effectiveTo last effective day, or {@code null} when open-ended
 * @param createdAt UTC creation time
 */
@Schema(name = "Team")
public record TeamResponse(
    UUID id,
    UUID siteId,
    @JsonInclude(JsonInclude.Include.ALWAYS) UUID departmentId,
    @JsonInclude(JsonInclude.Include.ALWAYS) UUID costCenterId,
    String code,
    String name,
    LocalDate effectiveFrom,
    @JsonInclude(JsonInclude.Include.ALWAYS) LocalDate effectiveTo,
    Instant createdAt) {

  /**
   * Maps the aggregate.
   *
   * @param team aggregate
   * @return response
   */
  public static TeamResponse from(Team team) {
    return new TeamResponse(
        team.id(),
        team.siteId(),
        team.departmentId(),
        team.costCenterId(),
        team.code(),
        team.name(),
        team.period().from(),
        team.period().to(),
        team.createdAt());
  }
}
