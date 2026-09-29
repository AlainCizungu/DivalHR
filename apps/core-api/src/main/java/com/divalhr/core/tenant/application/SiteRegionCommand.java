package com.divalhr.core.tenant.application;

import com.divalhr.core.platform.tenancy.TenantId;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Validated site-region assignment: the site from the path and the region from the body.
 *
 * @param siteId site
 * @param regionId region
 */
public record SiteRegionCommand(UUID siteId, UUID regionId) {

  /**
   * Canonical fingerprint input: verified tenant, site and region. The path's site is part of the
   * payload identity, so one key cannot be reused for another site.
   *
   * @param tenant verified tenant
   * @return key-sorted map
   */
  public Map<String, Object> canonical(TenantId tenant) {
    Map<String, Object> map = new TreeMap<>();
    map.put("regionId", regionId.toString());
    map.put("siteId", siteId.toString());
    map.put("tenantId", tenant.toString());
    return map;
  }
}
