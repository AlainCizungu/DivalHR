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
 * @param regionId optional region of the same legal entity, or {@code null}
 * @param code normalized code
 * @param name trimmed name
 * @param timezone IANA time zone
 * @param period effective period
 */
public record SiteCommand(
    UUID legalEntityId,
    UUID regionId,
    String code,
    String name,
    String timezone,
    EffectivePeriod period) {

  /**
   * Canonical fingerprint input, including the verified tenant. {@code regionId} is included only
   * when present, so a request without a region has exactly the fingerprint it had before regions
   * existed and pre-existing idempotency keys keep replaying (Issue #21).
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
    if (regionId != null) {
      map.put("regionId", regionId.toString());
    }
    map.put("tenantId", tenant.toString());
    map.put("timezone", timezone);
    return map;
  }
}
