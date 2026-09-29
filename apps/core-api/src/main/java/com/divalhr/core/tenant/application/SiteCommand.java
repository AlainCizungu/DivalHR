package com.divalhr.core.tenant.application;

import com.divalhr.core.platform.tenancy.TenantId;
import com.divalhr.core.tenant.domain.EffectivePeriod;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Validated, normalized site command.
 *
 * @param legalEntityId parent legal entity
 * @param code normalized code
 * @param name trimmed name
 * @param timezone IANA time zone
 * @param period effective period
 */
public record SiteCommand(
    UUID legalEntityId, String code, String name, String timezone, EffectivePeriod period) {

  /**
   * Canonical fingerprint input, including the verified tenant.
   *
   * @param tenant verified tenant
   * @return key-sorted map
   */
  public Map<String, Object> canonical(TenantId tenant) {
    Map<String, Object> map = new TreeMap<>();
    map.put("code", code);
    map.put("effectiveFrom", period.from().toString());
    map.put("effectiveTo", period.to() == null ? "" : period.to().toString());
    map.put("legalEntityId", legalEntityId.toString());
    map.put("name", name);
    map.put("tenantId", tenant.toString());
    map.put("timezone", timezone);
    return map;
  }
}
