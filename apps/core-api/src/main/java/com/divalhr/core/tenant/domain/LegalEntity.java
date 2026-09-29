package com.divalhr.core.tenant.domain;

import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Legal entity aggregate, owned by one tenant.
 *
 * @param id server-generated id
 * @param tenantId owning tenant (from the verified token only)
 * @param code normalized code, unique per tenant regardless of case
 * @param name display name (customer data: never logged, audited or published)
 * @param countryCode ISO 3166-1 alpha-2
 * @param period effective period
 * @param createdAt UTC creation time
 * @param createdBy verified JWT subject (never returned)
 */
public record LegalEntity(
    UUID id,
    TenantId tenantId,
    String code,
    String name,
    String countryCode,
    EffectivePeriod period,
    Instant createdAt,
    String createdBy) {

  /** Requires every component. */
  public LegalEntity {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(countryCode, "countryCode");
    Objects.requireNonNull(period, "period");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(createdBy, "createdBy");
  }
}
