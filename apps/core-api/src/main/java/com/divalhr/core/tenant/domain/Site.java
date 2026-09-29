package com.divalhr.core.tenant.domain;

import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Site aggregate. Its legal entity belongs to the same tenant and its period lies within the legal
 * entity's period. A site may have one region of the same legal entity (MVP-002 Increment 3A); when
 * it does, its period also lies within the region's period.
 *
 * @param id server-generated id
 * @param tenantId owning tenant (from the verified token only)
 * @param legalEntityId parent legal entity (stored explicitly, never inferred from the region)
 * @param regionId the site's region, or {@code null} for a site without a region
 * @param code normalized code, unique per tenant regardless of case
 * @param name display name (customer data)
 * @param timezone IANA time zone supported for the legal entity's country
 * @param period effective period
 * @param createdAt UTC creation time
 * @param createdBy verified JWT subject (never returned)
 */
public record Site(
    UUID id,
    TenantId tenantId,
    UUID legalEntityId,
    UUID regionId,
    String code,
    String name,
    String timezone,
    EffectivePeriod period,
    Instant createdAt,
    String createdBy) {

  /** Requires every component except the optional {@code regionId}. */
  public Site {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(legalEntityId, "legalEntityId");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(timezone, "timezone");
    Objects.requireNonNull(period, "period");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(createdBy, "createdBy");
  }

  /**
   * Returns this site with a region; every other component is unchanged.
   *
   * @param region region id
   * @return the site with that region
   */
  public Site withRegion(UUID region) {
    return new Site(
        id, tenantId, legalEntityId, region, code, name, timezone, period, createdAt, createdBy);
  }
}
