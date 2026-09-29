package com.divalhr.core.tenant.domain;

import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Site aggregate. Its legal entity belongs to the same tenant and its period lies within the legal
 * entity's period.
 *
 * @param id server-generated id
 * @param tenantId owning tenant (from the verified token only)
 * @param legalEntityId parent legal entity
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
    String code,
    String name,
    String timezone,
    EffectivePeriod period,
    Instant createdAt,
    String createdBy) {

  /** Requires every component. */
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
}
