package com.divalhr.core.identity.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Active members per role (MVP-012B). Mirrors {@code AccessReviewSummary}.
 *
 * @param byRole counts
 */
@Schema(name = "AccessReviewSummary")
public record AccessReviewSummaryResponse(ByRole byRole) {

  /**
   * Counts per role.
   *
   * @param tenantAdmin tenant administrators
   * @param employee employees
   */
  @JsonPropertyOrder({"tenant-admin", "employee"})
  public record ByRole(
      @JsonProperty("tenant-admin") long tenantAdmin, @JsonProperty("employee") long employee) {}
}
