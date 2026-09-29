package com.divalhr.core.tenant.domain;

import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Team aggregate (MVP-002 Increment 3B): the lowest organizational unit, beneath exactly one
 * department or cost center of the same tenant and site. Its period lies within the parent's.
 *
 * @param id server-generated id
 * @param tenantId owning tenant (from the verified token only)
 * @param siteId the parent's site (derived, never requested)
 * @param parentKind parent type
 * @param parentId parent id
 * @param code normalized code, unique per tenant regardless of case
 * @param name display name (customer data)
 * @param period effective period
 * @param createdAt UTC creation time
 * @param createdBy verified JWT subject (never returned)
 */
public record Team(
    UUID id,
    TenantId tenantId,
    UUID siteId,
    TeamParentKind parentKind,
    UUID parentId,
    String code,
    String name,
    EffectivePeriod period,
    Instant createdAt,
    String createdBy) {

  /** Requires every component. */
  public Team {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(siteId, "siteId");
    Objects.requireNonNull(parentKind, "parentKind");
    Objects.requireNonNull(parentId, "parentId");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(period, "period");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(createdBy, "createdBy");
  }

  /**
   * The parent department, if the team belongs to one.
   *
   * @return department id or {@code null}
   */
  public UUID departmentId() {
    return switch (parentKind) {
      case DEPARTMENT -> parentId;
      case COST_CENTER -> null;
    };
  }

  /**
   * The parent cost center, if the team belongs to one.
   *
   * @return cost center id or {@code null}
   */
  public UUID costCenterId() {
    return switch (parentKind) {
      case DEPARTMENT -> null;
      case COST_CENTER -> parentId;
    };
  }
}
