package com.divalhr.core.identity.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * An employee's DivalHR access link (MVP-022). Never the subject or the address.
 *
 * @param state {@code NOT_LINKED}, {@code ACTIVE}, {@code REVOCATION_SCHEDULED} or {@code REVOKED}
 * @param link the active link, or null
 */
@Schema(name = "EmployeeAccess")
public record EmployeeAccessResponse(
    String state, @JsonInclude(JsonInclude.Include.ALWAYS) Link link) {

  /**
   * An active link.
   *
   * @param id link
   * @param membershipId membership (personal-data reference)
   * @param role tenant role (Confidential)
   * @param linkedAt when it was linked
   * @param version link version
   */
  @Schema(name = "EmployeeAccessLink")
  public record Link(UUID id, UUID membershipId, String role, Instant linkedAt, long version) {}
}
