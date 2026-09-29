package com.divalhr.core.tenant.application;

import com.divalhr.core.platform.tenancy.TenantId;
import com.divalhr.core.tenant.domain.EffectivePeriod;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Validated, normalized department or cost-center command.
 *
 * @param siteId parent site
 * @param code normalized code
 * @param name trimmed name
 * @param period effective period
 */
public record SiteUnitCommand(UUID siteId, String code, String name, EffectivePeriod period) {

  /**
   * Canonical fingerprint input: verified tenant, parent site, normalized code, trimmed name and
   * ISO dates (an open end is the empty string).
   *
   * @param tenant verified tenant
   * @return key-sorted map
   */
  public Map<String, Object> canonical(TenantId tenant) {
    Map<String, Object> map = new TreeMap<>();
    map.put("code", code);
    map.put("effectiveFrom", period.from().toString());
    map.put("effectiveTo", period.to() == null ? "" : period.to().toString());
    map.put("name", name);
    map.put("siteId", siteId.toString());
    map.put("tenantId", tenant.toString());
    return map;
  }
}
