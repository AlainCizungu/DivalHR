package com.divalhr.core.tenant.api;

import com.divalhr.core.tenant.domain.Organization;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response body mirroring {@code Organization} in docs/API-SPEC.yaml.
 *
 * @param id organization (and tenant) ID
 * @param name name
 * @param countryCode country
 * @param defaultLocale locale
 * @param timezone time zone
 * @param currencies currencies
 * @param status status
 * @param createdAt UTC creation time
 */
@Schema(name = "Organization")
public record OrganizationResponse(
    UUID id,
    String name,
    String countryCode,
    String defaultLocale,
    String timezone,
    List<String> currencies,
    String status,
    Instant createdAt) {

  /** Defensively copies currencies. */
  public OrganizationResponse {
    currencies = List.copyOf(currencies);
  }

  /**
   * Maps the aggregate.
   *
   * @param organization aggregate
   * @return response
   */
  public static OrganizationResponse from(Organization organization) {
    return new OrganizationResponse(
        organization.id(),
        organization.name(),
        organization.countryCode(),
        organization.defaultLocale(),
        organization.timezone(),
        organization.currencies(),
        organization.status().name(),
        organization.createdAt());
  }
}
