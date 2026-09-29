package com.divalhr.core.tenant.domain;

import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.util.UUID;

/**
 * Shared shape of the two site-child aggregates. {@link Department} and {@link CostCenter} remain
 * distinct aggregate types with their own identities; this interface only exposes the common value
 * objects used by shared mechanics.
 */
public sealed interface SiteUnit permits Department, CostCenter {

  /**
   * Which aggregate this is.
   *
   * @return kind
   */
  SiteUnitKind kind();

  /**
   * Aggregate id.
   *
   * @return id
   */
  UUID id();

  /**
   * Owning tenant.
   *
   * @return tenant
   */
  TenantId tenantId();

  /**
   * Parent site.
   *
   * @return site id
   */
  UUID siteId();

  /**
   * Normalized code.
   *
   * @return code
   */
  String code();

  /**
   * Trimmed name.
   *
   * @return name
   */
  String name();

  /**
   * Effective period.
   *
   * @return period
   */
  EffectivePeriod period();

  /**
   * Creation time.
   *
   * @return timestamp
   */
  Instant createdAt();

  /**
   * Verified subject of the creator (never returned).
   *
   * @return subject
   */
  String createdBy();

  /**
   * Creates the aggregate of the given kind.
   *
   * @param kind kind
   * @param id id
   * @param tenantId tenant
   * @param siteId parent site
   * @param code normalized code
   * @param name trimmed name
   * @param period effective period
   * @param createdAt creation time
   * @param createdBy verified subject
   * @return department or cost center
   */
  static SiteUnit of(
      SiteUnitKind kind,
      UUID id,
      TenantId tenantId,
      UUID siteId,
      String code,
      String name,
      EffectivePeriod period,
      Instant createdAt,
      String createdBy) {
    return switch (kind) {
      case DEPARTMENT ->
          new Department(id, tenantId, siteId, code, name, period, createdAt, createdBy);
      case COST_CENTER ->
          new CostCenter(id, tenantId, siteId, code, name, period, createdAt, createdBy);
    };
  }
}
