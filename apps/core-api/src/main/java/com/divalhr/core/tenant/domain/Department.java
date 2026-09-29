package com.divalhr.core.tenant.domain;

import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A department of a site (MVP-002 Increment 2). A separate aggregate referencing its site by id.
 *
 * @param id aggregate id
 * @param tenantId owning tenant (from the verified token)
 * @param siteId parent site of the same tenant
 * @param code normalized code
 * @param name trimmed name
 * @param period effective period, contained in the site's
 * @param createdAt creation time
 * @param createdBy verified JWT subject (never returned)
 */
public record Department(
    UUID id,
    TenantId tenantId,
    UUID siteId,
    String code,
    String name,
    EffectivePeriod period,
    Instant createdAt,
    String createdBy)
    implements SiteUnit {

  /** Requires every component. */
  public Department {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(siteId, "siteId");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(period, "period");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(createdBy, "createdBy");
  }

  @Override
  public SiteUnitKind kind() {
    return SiteUnitKind.DEPARTMENT;
  }
}
