package com.divalhr.core.people.leave.domain;

/**
 * The authority a leave decision was taken under (MVP-041E, V22). The policy route stays the
 * immutable route of the request's policy version; the authority says who decided under it.
 */
public enum DecisionAuthority {
  /** The request's manager, under the {@code MANAGER} route. */
  MANAGER,
  /** A tenant administrator, under the {@code TENANT_ADMIN} route. */
  TENANT_ADMIN,
  /**
   * A tenant administrator resolving a {@code MANAGER}-routed request that no qualifying manager
   * covered on its first day (a routing exception).
   */
  TENANT_ADMIN_OVERRIDE
}
