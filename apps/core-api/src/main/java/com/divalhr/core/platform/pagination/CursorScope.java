package com.divalhr.core.platform.pagination;

import com.divalhr.core.platform.tenancy.TenantId;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a cursor is bound to: the list operation, the verified tenant and the filters. A cursor
 * issued for one scope is rejected in any other.
 *
 * @param operation list operation name
 * @param tenant verified tenant
 * @param filters filter values (for example the parent legal entity)
 */
public record CursorScope(String operation, TenantId tenant, Map<String, String> filters) {

  /** Copies filters in sorted order so the binding is deterministic. */
  public CursorScope {
    filters = Map.copyOf(new TreeMap<>(filters));
  }

  /**
   * Canonical text of the binding (hashed into the cursor; never logged).
   *
   * @return canonical binding
   */
  String canonical() {
    StringBuilder text = new StringBuilder(operation).append('|').append(tenant);
    new TreeMap<>(filters).forEach((k, v) -> text.append('|').append(k).append('=').append(v));
    return text.toString();
  }
}
