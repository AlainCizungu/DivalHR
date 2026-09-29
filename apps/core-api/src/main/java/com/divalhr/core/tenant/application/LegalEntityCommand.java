package com.divalhr.core.tenant.application;

import com.divalhr.core.platform.tenancy.TenantId;
import com.divalhr.core.tenant.domain.EffectivePeriod;
import java.util.Map;
import java.util.TreeMap;

/**
 * Validated, normalized legal-entity command.
 *
 * @param code normalized code
 * @param name trimmed name
 * @param countryCode country
 * @param period effective period
 */
public record LegalEntityCommand(
    String code, String name, String countryCode, EffectivePeriod period) {

  /**
   * Canonical fingerprint input, including the verified tenant (Issue #12 review, decision 6).
   *
   * @param tenant verified tenant
   * @return key-sorted map
   */
  public Map<String, Object> canonical(TenantId tenant) {
    Map<String, Object> map = new TreeMap<>();
    map.put("code", code);
    map.put("countryCode", countryCode);
    map.put("effectiveFrom", period.from().toString());
    map.put("effectiveTo", period.to() == null ? "" : period.to().toString());
    map.put("name", name);
    map.put("tenantId", tenant.toString());
    return map;
  }
}
